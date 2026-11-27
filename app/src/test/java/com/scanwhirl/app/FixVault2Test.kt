package com.scanwhirl.app
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * T18-FIXVAULT2, POL-002 follow-ups from JUDGE_fixcopy (docs/DECISIONS.md):
 *
 * 1. A failed recovery-code save logs the exception CLASS only. Its message can carry a path or
 *    a file name, so the message and the throwable never reach logcat.
 * 2. Rotation (and process recreation) must not drop the four-character check that closes the
 *    one-time recovery-code flow; the protected action (moving old files in, finishing setup)
 *    never runs without it.
 * 3. Every Vault sheet has its own window: each one that shows or accepts a passcode, PIN or
 *    recovery code needs FLAG_SECURE, not only the activity window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FixVault2Test {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore
    private var now = 0L
    private var controller: ActivityController<VaultActivity>? = null

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        store = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(clock = { now }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        ShadowLog.clear()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            idle()
            if (condition()) return true
            if (System.nanoTime() >= deadline) {
                idle()
                return condition()
            }
            Thread.sleep(10)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> BottomSheetDialog.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Activity.need(id: Int): T = findViewById<View>(id) as T

    private fun launch(): VaultActivity {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun sheet(): BottomSheetDialog {
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("a sheet should be showing", dialog)
        assertTrue("the latest sheet must still be open", dialog.isShowing)
        return dialog as BottomSheetDialog
    }

    private fun titleOf(dialog: BottomSheetDialog): String =
        (dialog.findViewById<View>(R.id.swSheetTitle) as? TextView)?.text?.toString().orEmpty()

    /** The newest kit sheet with [title] opened since [before] (progress dialogs have no title). */
    private fun awaitNewSheetSince(before: Int, title: String): BottomSheetDialog? {
        await { openedSince(before).any { titleOf(it) == title } }
        return openedSince(before).lastOrNull { titleOf(it) == title }
    }

    /**
     * The dialogs opened after a recreation. Robolectric keeps the destroyed activity's dialogs
     * in its list (as a real process would drop their windows), so "what is new" must be counted:
     * a stale sheet must never stand in for the restored one.
     */
    private fun openedSince(before: Int): List<BottomSheetDialog> {
        idle()
        // A progress sheet has no kit title: keep only the kit sheets this test can inspect.
        return ShadowDialog.getShownDialogs().drop(before)
            .mapNotNull { it as? BottomSheetDialog }
            .filter { it.findViewById<View>(R.id.swSheetTitle) != null }
    }

    private fun shownCount(): Int {
        idle()
        return ShadowDialog.getShownDialogs().size
    }

    private fun assertSecure(dialog: BottomSheetDialog, what: String) {
        assertNotNull("$what has its own window", dialog.window)
        assertTrue(
            "$what must block screenshots and the recents card (FLAG_SECURE)",
            SecureView.isSecure(dialog.window)
        )
    }

    /** Drives the real setup sheet until the one-time recovery code is on screen. */
    private fun reachRecoveryCode(): BottomSheetDialog {
        val setup = sheet()
        setup.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setup.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")
        setup.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("setup finishes on a background thread", await { !setup.isShowing })
        idle()
        val recovery = sheet()
        assertEquals("Save this recovery code", titleOf(recovery))
        return recovery
    }

    /** Legacy PIN -> new setup -> the one-time code -> the four-character check. Returns the code. */
    private fun reachConfirmationWithLegacyFile(docName: String): String {
        val id = DocFixtures.saved(repo, app, pages = 1, name = docName)
        repo.setVaulted(id, true)
        Prefs.setVaultPin(app, "1234")

        launch()
        val legacy = sheet()
        assertEquals("Old PIN Vault", titleOf(legacy))
        legacy.need<EditText>(R.id.vaultLegacyPin).setText("1234")
        legacy.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the old PIN check finishes", await { !legacy.isShowing })

        val recovery = reachRecoveryCode()
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        saveRecoveryFileForTest(controller!!.get(), recovery)
        recovery.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertEquals("Type 4 characters", titleOf(sheet()))
        return code
    }

    /** Four characters that cannot match [code]'s first four. */
    private fun wrongFour(code: String): String =
        VaultCrypto.normalizeRecoveryCode(code).take(4).map { if (it == '0') '1' else '0' }.joinToString("")

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun tapOption(sheet: BottomSheetDialog, label: String) {
        val rows = texts(sheet.need<ViewGroup>(R.id.swSheetContent)).filter { it.text?.toString() == label }
        assertTrue("the options sheet must list '$label'", rows.isNotEmpty())
        rows.first().performClick()
        idle()
    }

    // ------------------------------------------------------------------
    // 1. Exception logging never includes the message
    // ------------------------------------------------------------------

    @Test
    fun `a failed recovery save logs the exception class only, never its message`() {
        launch()
        val recovery = reachRecoveryCode()
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        recovery.need<TextView>(R.id.vaultRecoveryCopy).performClick()
        idle()
        val started = shadowOf(controller!!.get()).peekNextStartedActivityForResult()
        assertNotNull("the system picker starts", started)

        // A provider that fails with a message shaped like a real one (a path with a document
        // name): it must never reach logcat.
        val sentinel = "/storage/emulated/0/Documents/Aadhaar card of Asha.pdf"
        val target = Uri.parse("content://com.scanwhirl.app.test/recovery.txt")
        shadowOf(app.contentResolver).registerOutputStream(
            target,
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException(sentinel)
                }
            }
        )
        ShadowLog.clear()
        shadowOf(controller!!.get()).callOnActivityResult(
            started!!.requestCode,
            Activity.RESULT_OK,
            Intent().setData(target)
        )
        assertTrue(
            "the save fails with the fixed message",
            await {
                (ShadowDialog.getLatestDialog() as? BottomSheetDialog)
                    ?.findViewById<TextView>(R.id.swSheetMessage)
                    ?.text?.toString() == app.getString(R.string.vault_recovery_file_failed)
            }
        )

        val lines = ShadowLog.getLogsForTag("VaultActivity").mapNotNull { it.msg }
        val line = lines.firstOrNull { it.contains("saving the recovery code file failed") }
        assertNotNull("the failure must still be logged for diagnosis, logs: $lines", line)
        assertTrue(
            "the log names the exception class so a real failure is diagnosable: $line",
            line!!.contains(IOException::class.java.name)
        )
        assertFalse("the exception message must never be logged: $line", line.contains(sentinel))
        assertFalse("the recovery code must never be logged: $line", line.contains(code))
        val item = ShadowLog.getLogsForTag("VaultActivity")
            .first { it.msg?.contains("saving the recovery code file failed") == true }
        assertNull(
            "the throwable itself must not be logged: its message and stack can hold paths and names",
            item.throwable
        )
        assertTrue(
            "no log line anywhere carries the exception message",
            ShadowLog.getLogs().none { (it.msg ?: "").contains(sentinel) }
        )
    }

    // ------------------------------------------------------------------
    // 2. The four-character check survives rotation / process recreation
    // ------------------------------------------------------------------

    @Test
    fun `rotation during the four-character check keeps it required and the old files stay put`() {
        val code = reachConfirmationWithLegacyFile("Old protected file")
        assertTrue("the old Vault still hides its file", repo.listVaultDocuments().isNotEmpty())
        assertTrue("nothing is in the new Vault yet", store.listEntries().isEmpty())

        val before = shownCount()
        controller!!.recreate()
        idle()
        val restored = openedSince(before)
        assertEquals(
            "the four-character check must be on screen again after a rotation, not silently dropped",
            1,
            restored.size
        )
        assertEquals(
            "the restored sheet is the check, not something else",
            "Type 4 characters",
            titleOf(restored.last())
        )
        val check = restored.last()
        assertSecure(check, "the four-character check sheet")
        assertTrue(
            "the protected action (moving the old files) must not run without the check",
            store.listEntries().isEmpty()
        )
        assertTrue(repo.listVaultDocuments().isNotEmpty())

        // A wrong four characters never finishes the flow.
        check.need<EditText>(R.id.vaultRecoveryInput).setText(wrongFour(code))
        check.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertTrue("the sheet stays open on a mismatch", check.isShowing)
        assertTrue("nothing moved after a wrong check", store.listEntries().isEmpty())

        // The right four characters are the only way the protected action runs.
        check.need<EditText>(R.id.vaultRecoveryInput)
            .setText(VaultCrypto.normalizeRecoveryCode(code).take(4))
        check.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue(
            "the check lets the old files move in",
            await { repo.listVaultDocuments().isEmpty() && store.listEntries().size == 1 }
        )
    }

    @Test
    fun `rotation during the recovery code sheet brings the same code back`() {
        launch()
        val recovery = reachRecoveryCode()
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()

        val before = shownCount()
        controller!!.recreate()
        idle()
        val restored = openedSince(before)
        assertEquals(
            "the one-time code sheet must come back after a rotation",
            1,
            restored.size
        )
        val codeSheet = restored.last()
        assertEquals("Save this recovery code", titleOf(codeSheet))
        assertEquals(
            "the one-time code must be the same one, not a new or empty sheet",
            code,
            codeSheet.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        )
        assertSecure(codeSheet, "the restored recovery-code sheet")
    }

    @Test
    fun `process recreation cannot finish the check silently and asks for a new code`() {
        val oldCode = reachConfirmationWithLegacyFile("Old protected file")

        val before = shownCount()
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(VaultActivity::class.java).create(state).start().resume()
        idle()

        // The non-secret pending marker survives the process: a NEW code must be shown.
        val codeSheet = awaitNewSheetSince(before, "Save this recovery code")
        assertNotNull("the lost check must be answered with a new code", codeSheet)
        assertNotEquals(
            "the old in-memory code is never resurrected",
            oldCode,
            codeSheet!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        )
        assertTrue(
            "the protected action must not run without the check",
            store.listEntries().isEmpty()
        )
        assertTrue(repo.listVaultDocuments().isNotEmpty())
        assertSecure(codeSheet, "the new-code sheet")
    }

    @Test
    fun `process recreation with a locked Vault asks for a new code after unlocking`() {
        reachConfirmationWithLegacyFile("Old protected file")
        store.lock()

        val before = shownCount()
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(VaultActivity::class.java).create(state).start().resume()
        idle()

        // A fresh process is locked: only the unlock sheet, never a code.
        val opened = openedSince(before)
        assertEquals("a locked Vault shows only the unlock sheet", 1, opened.size)
        assertEquals("Unlock Vault", titleOf(opened.last()))
        assertFalse(
            "no code may be on screen before authentication",
            opened.any { titleOf(it) == "Save this recovery code" }
        )

        val after = shownCount()
        opened.last().need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        opened.last().need<View>(R.id.swSheetPrimary).performClick()
        assertNotNull(
            "after unlocking, the pending setup asks for a new code",
            awaitNewSheetSince(after, "Save this recovery code")
        )
    }

    // ------------------------------------------------------------------
    // 3. Every Vault sheet that shows or accepts a secret has FLAG_SECURE
    // ------------------------------------------------------------------

    @Test
    fun `the setup, recovery code and confirmation sheets are secure`() {
        launch()
        val setup = sheet()
        assertEquals("Set up your Vault", titleOf(setup))
        assertSecure(setup, "the setup passcode sheet")

        val recovery = reachRecoveryCode()
        assertSecure(recovery, "the recovery-code display sheet")

        saveRecoveryFileForTest(controller!!.get(), recovery)

        recovery.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        val confirm = sheet()
        assertEquals("Type 4 characters", titleOf(confirm))
        assertSecure(confirm, "the recovery-code confirmation sheet")
    }

    @Test
    fun `the unlock and recovery-code unlock sheets are secure`() {
        store.setup("246810")
        store.markConfirmationPassed()
        store.lock()
        launch()

        val unlock = sheet()
        assertEquals("Unlock Vault", titleOf(unlock))
        assertSecure(unlock, "the passcode unlock sheet")

        unlock.need<View>(R.id.vaultUseRecovery).performClick()
        idle()
        val recovery = sheet()
        assertEquals("Use recovery code", titleOf(recovery))
        assertSecure(recovery, "the recovery-code unlock sheet")
    }

    @Test
    fun `the old PIN sheet is secure`() {
        Prefs.setVaultPin(app, "1234")
        launch()
        val legacy = sheet()
        assertEquals("Old PIN Vault", titleOf(legacy))
        assertSecure(legacy, "the old 4-digit PIN sheet")
    }

    @Test
    fun `the options sheet and the new passcode sheet are secure`() {
        store.setup("246810")
        store.markConfirmationPassed()
        val activity = launch()

        activity.need<View>(R.id.vaultMore).performClick()
        idle()
        val options = sheet()
        assertEquals("Vault options", titleOf(options))
        assertSecure(options, "the Vault options sheet")

        tapOption(options, app.getString(R.string.vault_change_passcode))
        val passcode = sheet()
        assertEquals("New passcode", titleOf(passcode))
        assertSecure(passcode, "the new passcode sheet")
    }

    @Test
    fun `the restore sheet is secure`() {
        store.setup("246810")
        store.markConfirmationPassed()
        val activity = launch()
        activity.need<View>(R.id.vaultMore).performClick()
        idle()
        tapOption(sheet(), app.getString(R.string.vault_restore_title))

        val started = shadowOf(activity).peekNextStartedActivityForResult()
        assertNotNull("the restore picker starts", started)
        val backup = Uri.parse("content://com.scanwhirl.app.test/backup.swvault")
        shadowOf(app.contentResolver)
            .registerInputStream(backup, ByteArrayInputStream(ByteArray(16)))
        shadowOf(activity).callOnActivityResult(
            started!!.requestCode,
            Activity.RESULT_OK,
            Intent().setData(backup)
        )

        assertTrue(
            "the restore sheet opens after the file is picked",
            await {
                (ShadowDialog.getLatestDialog() as? BottomSheetDialog)
                    ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.vault_restore_title)
            }
        )
        assertSecure(sheet(), "the restore recovery-code sheet")
    }

    @Test
    fun `the add files sheet is secure because it lists private names`() {
        store.setup("246810")
        store.markConfirmationPassed()
        val activity = launch()
        activity.need<View>(R.id.vaultAddFiles).performClick()
        idle()
        val note = sheet()
        assertEquals("Add to Vault", titleOf(note))
        assertSecure(note, "the add-files sheet")
    }
}
