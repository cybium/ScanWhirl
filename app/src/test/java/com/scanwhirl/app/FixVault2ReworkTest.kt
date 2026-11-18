package com.scanwhirl.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T18-FIXVAULT2 rework, round 2 (independent security review H1-H4 + LOW).
 *
 * H1: setup must not be usable before the four-character check; the pending state is a
 *     non-secret marker, so Back/close/reopen cannot drop it, and no migration path runs
 *     while it is set.
 * H2: a replacement recovery code is written only after the check passes; abandoning it
 *     keeps the old code working.
 * H3: a recreation on a locked Vault must never redisplay the retained code.
 * H4: a Vault PDF opens private (FLAG_SECURE) even when the screenshot preference is off.
 * LOW: the setup result's toString never prints the code.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FixVault2ReworkTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var keys: FakeVaultKeys
    private lateinit var store: VaultStore
    private var now = 0L
    private var controller: ActivityController<VaultActivity>? = null
    private var pagesController: ActivityController<PdfViewerActivity>? = null
    private var defaultPagesFactory: ((ParcelFileDescriptor) -> PdfViewerActivity.PdfPages)? = null

    private val setupTitle = "Set up your Vault"
    private val codeTitle = "Save this recovery code"
    private val confirmTitle = "Type 4 characters"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        keys = FakeVaultKeys()
        store = VaultStore(
            context = app,
            repo = repo,
            keys = keys,
            gate = VaultPasscodeGate(clock = { now }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        defaultPagesFactory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages(1) }
        Durable.beforeFileSync = {}
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        pagesController?.close()
        pagesController = null
        defaultPagesFactory?.let { PdfViewerActivity.pagesFactory = it }
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        Durable.beforeFileSync = {}
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
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
        // A progress dialog is also a sheet without a title: only kit sheets count here.
        val dialogs = ShadowDialog.getShownDialogs()
            .filterIsInstance<BottomSheetDialog>()
            .filter { it.findViewById<View>(R.id.swSheetTitle) != null }
        assertTrue("a kit sheet should be showing", dialogs.isNotEmpty())
        return dialogs.last()
    }

    private fun shownCount(): Int {
        idle()
        return ShadowDialog.getShownDialogs().size
    }

    private fun openedSince(before: Int): List<BottomSheetDialog> {
        idle()
        return ShadowDialog.getShownDialogs().drop(before).mapNotNull { it as? BottomSheetDialog }
    }

    private fun titleOf(dialog: BottomSheetDialog): String =
        (dialog.findViewById<View>(R.id.swSheetTitle) as? TextView)?.text?.toString().orEmpty()

    private fun awaitNewSheetSince(before: Int, title: String): BottomSheetDialog? {
        await { openedSince(before).any { titleOf(it) == title } }
        return openedSince(before).lastOrNull { titleOf(it) == title }
    }

    private fun right4(code: String): String = VaultCrypto.normalizeRecoveryCode(code).take(4)

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    private class Reached(val activity: VaultActivity, val code: String, val confirm: BottomSheetDialog)

    /** Legacy PIN (optional) -> set up -> the one-time code -> the four-character check. */
    private fun reachConfirmation(passcode: String = "246810", legacyPin: String? = null): Reached {
        val activity = launch()
        if (legacyPin != null) {
            val legacy = sheet()
            legacy.need<EditText>(R.id.vaultLegacyPin).setText(legacyPin)
            legacy.need<View>(R.id.swSheetPrimary).performClick()
            idle()
        }
        val setup = sheet()
        assertEquals(setupTitle, titleOf(setup))
        setup.need<EditText>(R.id.vaultPasscodeInput).setText(passcode)
        setup.need<EditText>(R.id.vaultPasscodeConfirm).setText(passcode)
        setup.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("setup finishes on a background thread", await { !setup.isShowing })
        idle()
        val recovery = sheet()
        assertEquals(codeTitle, titleOf(recovery))
        val code = recovery.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        recovery.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        val confirm = sheet()
        assertEquals(confirmTitle, titleOf(confirm))
        return Reached(activity, code, confirm)
    }

    private fun finishConfirmation(reached: Reached) {
        reached.confirm.need<EditText>(R.id.vaultRecoveryInput).setText(right4(reached.code))
        reached.confirm.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the check passes", await { !reached.confirm.isShowing })
        idle()
    }

    /** The whole real setup, confirmed: the store is then a normal, usable Vault. */
    private fun configureVault(passcode: String = "246810"): String {
        val reached = reachConfirmation(passcode)
        finishConfirmation(reached)
        return reached.code
    }

    // ------------------------------------------------------------------
    // H1: pending completion survives close/Back/reopen and blocks the Vault
    // ------------------------------------------------------------------

    @Test
    fun `a setup closed before the check keeps the Vault blocked and asks for a new code`() {
        val reached = reachConfirmation()
        // Back / close: the activity goes away without a Bundle and without the check.
        controller!!.pause().stop().destroy()
        controller = null

        val before = shownCount()
        val reopened = launch()
        val codeSheet = awaitNewSheetSince(before, codeTitle)
        assertNotNull(
            "reopening a half-finished setup must ask for a new code (never use the Vault unconfirmed)",
            codeSheet
        )
        val newCode = codeSheet!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        assertNotEquals("the old code is gone, so a NEW one is shown", reached.code, newCode)

        // Normal Vault actions are refused at the data layer, not only hidden in the UI.
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Budget")
        assertFalse(
            "a pending Vault must not accept new files",
            runCatching { store.addDocument(id) }.isSuccess
        )
        assertFalse(
            "a pending Vault must not open its entries",
            runCatching { store.openDocument("anything") }.isSuccess
        )
        assertEquals(
            "the screen says the setup is unfinished",
            "Finish setting up: type 4 characters of your recovery code.",
            reopened.need<TextView>(R.id.vaultBanner).text.toString()
        )

        // The user can still finish: confirm the new code, and the normal Vault comes back.
        val before2 = shownCount()
        codeSheet.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        val confirm = awaitNewSheetSince(before2, confirmTitle)
        assertNotNull("the replacement code is checked too", confirm)
        confirm!!.need<EditText>(R.id.vaultRecoveryInput).setText(right4(newCode))
        confirm.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the check finishes the setup", await { !confirm.isShowing })
        assertTrue("the Vault is usable after the check", await { runCatching { store.addDocument(id) }.isSuccess })
    }

    @Test
    fun `a pending setup blocks every migration path until the check passes`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Old protected file")
        repo.setVaulted(id, true)
        Prefs.setVaultPin(app, "1234")
        val reached = reachConfirmation(legacyPin = "1234")
        controller!!.pause().stop().destroy()
        controller = null

        val before = shownCount()
        val reopened = launch()
        assertNotNull("a pending setup asks for a new code", awaitNewSheetSince(before, codeTitle))

        assertTrue(
            "the manual migration must refuse while the check is owed",
            runCatching { store.migrateLegacyVault() }.isFailure
        )
        assertTrue("the old file is still hidden, not moved", repo.listVaultDocuments().isNotEmpty())

        // The options sheet does not offer the migration either.
        reopened.need<View>(R.id.vaultMore).performClick()
        idle()
        val options = sheet()
        val rows = texts(options.need<ViewGroup>(R.id.swSheetContent)).mapNotNull { it.text?.toString() }
        assertFalse("no 'Move old PIN files' row while pending: $rows", rows.contains("Move old PIN files"))
        assertFalse("no 'Change passcode' row while pending: $rows", rows.contains("Change passcode"))
        assertFalse("no 'Backup file' row while pending: $rows", rows.contains("Backup file"))
    }

    @Test
    fun `rotation while the setup write is in flight ends in the check, not the setup sheet`() {
        val activity = launch()
        val setup = sheet()
        assertEquals(setupTitle, titleOf(setup))
        setup.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setup.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Durable.beforeFileSync = { file ->
            if (file.name == "recovery.bin.tmp") {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        setup.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the setup write is in flight", entered.await(10, TimeUnit.SECONDS))

        // Let the write finish while the rotation's onCreate waits for the store lock.
        val releaser = Thread {
            Thread.sleep(300)
            release.countDown()
        }
        releaser.start()
        val before = shownCount()
        controller!!.recreate()
        idle()
        Durable.beforeFileSync = {}
        releaser.join(2_000)

        val opened = openedSince(before)
        assertTrue(
            "a rotation during the write must not dead-end on the setup sheet again, sheets: " +
                opened.map { titleOf(it) },
            opened.none { titleOf(it) == setupTitle }
        )
        assertNotNull(
            "the half-finished setup asks for a new code instead",
            awaitNewSheetSince(before, codeTitle)
        )
    }

    // ------------------------------------------------------------------
    // H1b: one dispatch, bound to the current flow instance
    // ------------------------------------------------------------------

    @Test
    fun `a stale confirmation sheet cannot dispatch after a recreation`() {
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var prompts = 0
        // Answering "no" keeps the fingerprint wrap uncreated, so the counter reflects calls.
        VaultActivity.fingerprintStarter = { prompts++; false }

        val reached = reachConfirmation()
        val before = shownCount()
        controller!!.recreate()
        idle()
        val restored = openedSince(before).lastOrNull { titleOf(it) == confirmTitle }
        assertNotNull("the check is restored after the rotation", restored)

        // The destroyed activity's sheet still holds its listener. It must be stale.
        reached.confirm.need<EditText>(R.id.vaultRecoveryInput).setText(right4(reached.code))
        reached.confirm.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertEquals("a stale sheet must not run the protected action", 0, prompts)

        // The sheet of the current instance is the one that may finish.
        restored!!.need<EditText>(R.id.vaultRecoveryInput).setText(right4(reached.code))
        restored.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the current sheet dispatches once", await { prompts == 1 })
        assertEquals("exactly one dispatch for one finish", 1, prompts)
    }

    @Test
    fun `two Finish taps write the replacement code at most once`() {
        configureVault()
        val activity = controller!!.get()
        activity.need<View>(R.id.vaultMore).performClick()
        idle()
        texts(sheet().need<ViewGroup>(R.id.swSheetContent))
            .first { it.text?.toString() == "Recovery code" }
            .performClick()
        idle()
        val before = shownCount()
        sheet().need<View>(R.id.swSheetPrimary).performClick()   // Make a new code
        val codeSheet = awaitNewSheetSince(before, codeTitle)
        assertNotNull("a new code is prepared", codeSheet)
        val newCode = codeSheet!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        codeSheet.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        val confirm = sheet()
        assertEquals(confirmTitle, titleOf(confirm))

        // Count the actual wrap writes from here on: the check must write exactly one.
        val writes = java.util.concurrent.atomic.AtomicInteger()
        Durable.beforeFileSync = { file -> if (file.name == "recovery.bin.tmp") writes.incrementAndGet() }
        confirm.need<EditText>(R.id.vaultRecoveryInput).setText(right4(newCode))
        confirm.need<View>(R.id.swSheetPrimary).performClick()
        confirm.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the check finishes", await { !confirm.isShowing })
        Durable.beforeFileSync = {}
        assertEquals("two taps must not write the new wrap twice", 1, writes.get())
    }

    @Test
    fun `an abandoned replacement leaves the old recovery code working`() {
        val oldCode = configureVault()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Passport")
        store.addDocument(id)
        val backup = File(app.cacheDir, "vault-backup.swvault")
        store.exportBackup(backup)

        // A restored phone: entries + recovery wrap, no passcode wrap (as after a restore).
        controller?.destroy()
        controller = null
        store.wipeAll()
        val restored = VaultStore(
            context = app,
            repo = repo,
            keys = keys,
            gate = VaultPasscodeGate(clock = { now }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = restored
        val imported = restored.importBackup(backup, oldCode)
        assertTrue("the backup restores from the code alone", imported.isNotEmpty())
        assertFalse("a restored Vault has no passcode wrap", File(app.filesDir, "vault2/passcode.bin").exists())

        // Start a replacement and abandon it before the four-character check.
        val activity = launch()
        activity.need<View>(R.id.vaultMore).performClick()
        idle()
        texts(sheet().need<ViewGroup>(R.id.swSheetContent))
            .first { it.text?.toString() == "Recovery code" }
            .performClick()
        idle()
        val before = shownCount()
        sheet().need<View>(R.id.swSheetPrimary).performClick()   // Make a new code
        val codeSheet = awaitNewSheetSince(before, codeTitle)
        assertNotNull("the new code is shown", codeSheet)
        val newCode = codeSheet!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        assertNotEquals(oldCode, newCode)

        controller!!.destroy()
        controller = null
        VaultSession.close()

        assertTrue(
            "the OLD code must still open the Vault: the new wrap is not written before the check",
            restored.unlockWithRecovery(oldCode)
        )
        assertTrue("its files are still there", restored.listEntries().isNotEmpty())
        VaultSession.close()
        assertFalse("the unconfirmed new code must not work", restored.unlockWithRecovery(newCode))
    }

    // ------------------------------------------------------------------
    // H3: never redisplay a retained code while locked
    // ------------------------------------------------------------------

    @Test
    fun `a locked recreation never shows the retained code and asks for a new one after unlocking`() {
        val reached = reachConfirmation()
        store.lock()
        VaultSession.close()

        val before = shownCount()
        controller!!.recreate()
        idle()
        val opened = openedSince(before)
        assertTrue("the unlock sheet comes first", opened.isNotEmpty())
        assertFalse(
            "a locked Vault must never redisplay the retained code: " + opened.map { titleOf(it) },
            opened.any { titleOf(it) == codeTitle }
        )
        assertEquals("Unlock Vault", titleOf(opened.last()))

        val afterUnlock = shownCount()
        opened.last().need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        opened.last().need<View>(R.id.swSheetPrimary).performClick()

        val codeSheet = awaitNewSheetSince(afterUnlock, codeTitle)
        assertNotNull("after unlocking, a NEW code is required", codeSheet)
        assertNotEquals(
            "the old retained code is never shown again",
            reached.code,
            codeSheet!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        )
    }

    // ------------------------------------------------------------------
    // H4: Vault PDFs are private regardless of the screenshot preference
    // ------------------------------------------------------------------

    @Test
    fun `a Vault PDF viewer blocks screenshots with the preference off`() {
        Prefs.setBlockDocScreenshots(app, false)
        configureVault()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Statement")
        store.addDocument(id)
        val opened = store.openDocument(id)

        // The Vault route: its own path, marked private by the intent.
        val explicit = Intent(app, PdfViewerActivity::class.java)
            .putExtra("pdf_path", opened.pdfFile.absolutePath)
            .putExtra("pdf_title", opened.name)
            .putExtra("pdf_private", true)
        pagesController = Robolectric.buildActivity(PdfViewerActivity::class.java, explicit).setup()
        idle()
        assertTrue(
            "a Vault PDF must block screenshots even with the user preference off",
            SecureView.isSecure(pagesController!!.get().window)
        )
        pagesController!!.pause().stop().destroy()
        pagesController = null

        // Defence in depth: a decrypted Vault copy is private by its location too.
        val byPath = Intent(app, PdfViewerActivity::class.java)
            .putExtra("pdf_path", opened.pdfFile.absolutePath)
            .putExtra("pdf_title", opened.name)
        pagesController = Robolectric.buildActivity(PdfViewerActivity::class.java, byPath).setup()
        idle()
        assertTrue(
            "a file under vault_open is private even without the marker",
            SecureView.isSecure(pagesController!!.get().window)
        )
    }

    @Test
    fun `opening a Vault entry hands the viewer the private marker`() {
        configureVault()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Tax paper")
        store.addDocument(id)
        // Reopen the screen so the list is painted with the added file.
        controller!!.destroy()
        controller = null
        val activity = launch()
        idle()

        val list = activity.need<ViewGroup>(R.id.vaultList)
        assertEquals("the added file is listed", 1, list.childCount)
        list.getChildAt(0).performClick()

        assertTrue("opening starts the viewer", await { shadowOf(activity).peekNextStartedActivity() != null })
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(PdfViewerActivity::class.java.name, started.component?.className)
        assertTrue(
            "the Vault route must be marked private for the viewer",
            started.getBooleanExtra("pdf_private", false)
        )
    }

    @Test
    fun `the Vault window blocks screenshots while unlocked`() {
        configureVault()
        assertTrue(
            "the unlocked Vault window is still FLAG_SECURE",
            SecureView.isSecure(controller!!.get().window)
        )
    }

    // ------------------------------------------------------------------
    // LOW: no code in toString
    // ------------------------------------------------------------------

    @Test
    fun `the setup result never prints the recovery code`() {
        val result = store.setup("246810")
        assertTrue("the code is there for the caller", result.recoveryCode.isNotBlank())
        assertFalse(
            "toString must not leak the code: $result",
            result.toString().contains(result.recoveryCode)
        )
        assertTrue("it says the value is redacted: $result", result.toString().contains("redacted"))
    }
}
