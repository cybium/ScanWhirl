package com.scanwhirl.app.ui.backup
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.backup.BackupResourceException
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

/**
 * Lane B1: backup/restore honesty. Every test here runs on the unfixed code first (RED) and
 * must pass after the fix (GREEN) without weakening any older backup test.
 *
 * The defects are about *words matching reality*:
 *  - a skipped Vault entry must never be called a document that "was already here";
 *  - cancelling or failing to save must not throw away the ready archive;
 *  - a mid-publish failure must report the documents that really landed, not "nothing changed";
 *  - a failed rename is a failure, not an "already here" document;
 *  - a malformed code/password gets its targeted message before any KDF;
 *  - a result with skips/warnings must not be titled "Backup restored";
 *  - Hindi uses one name for the recovery code;
 *  - two backups in one minute must not overwrite each other.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class B1BackupHonestyTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private var controller: ActivityController<BackupActivity>? = null
    private val password = "correct horse battery"

    private val docsDir get() = File(app.filesDir, "documents")
    private val vaultDir get() = File(app.filesDir, "vault2")

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        BackupActivity.vaultOverride = null
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        BackupActivity.vaultOverride = null
        VaultSession.close()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun cleanFiles() {
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        File(app.cacheDir, "fullbackup").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun launch(): BackupActivity {
        controller = Robolectric.buildActivity(BackupActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun awaitSheet(title: String, timeoutMs: Long = 20_000): Dialog {
        var found: Dialog? = null
        val ok = await(timeoutMs) {
            found = ShadowDialog.getShownDialogs().lastOrNull {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
            }
            found != null
        }
        assertTrue("the '$title' sheet should be showing; seen [${titles()}]", ok)
        return found!!
    }

    /** The result sheet, whichever honest title the fix gives it. */
    private fun awaitResultSheet(timeoutMs: Long = 20_000): Dialog {
        var found: Dialog? = null
        val restoreTitle = app.getString(R.string.backup_restore)
        val ok = await(timeoutMs) {
            found = ShadowDialog.getShownDialogs().lastOrNull {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() != restoreTitle &&
                    it.findViewById<TextView>(R.id.swSheetMessage) != null
            }
            found != null
        }
        assertTrue("a restore result sheet should be showing; seen [${titles()}]", ok)
        return found!!
    }

    private fun titles(): String = ShadowDialog.getShownDialogs().joinToString(", ") {
        it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty()
    }

    private fun sheetText(dialog: Dialog): String =
        dialog.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()

    private fun sheetTitle(dialog: Dialog): String =
        dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty()

    private fun doc(id: String, name: String): File {
        val dir = File(docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        File(dir, "page_000.jpg").writeBytes(ByteArray(128) { it.toByte() })
        return dir
    }

    private fun vaultStore(iterations: Int = 2_000): VaultStore = VaultStore(
        app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), iterations
    )

    /** A normal, fully set-up Vault: the one-time code's four-character check has passed. */
    private fun VaultStore.setupConfirmed(passcode: String): VaultStore.Setup =
        setup(passcode).also { markConfirmationPassed() }

    private fun createBackup(): Dialog {
        val activity = launch()
        activity.findViewById<TextView>(R.id.backupNow).performClick()
        awaitSheet(app.getString(R.string.backup_choose))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        awaitSheet(app.getString(R.string.backup_password_title))
            .findViewById<EditText>(R.id.backupPassword).setText(password)
        awaitSheet(app.getString(R.string.backup_password_title))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        return awaitSheet(app.getString(R.string.backup_ready))
    }

    private fun pickArchive(archive: File) {
        val uri = Uri.parse("content://scanwhirl-test/b1-backup.swbackup")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(archive.readBytes()))
        val activity = controller?.get() ?: launch()
        activity.findViewById<TextView>(R.id.backupRestore).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Restore opens the file picker", request)
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        idle()
    }

    // ------------------------------------------------------------------ #8

    @Test
    fun `two backups created in the same minute get different archive names`() {
        val names = (1..5).map { FullBackup(app).newArchiveFile().name }
        assertEquals("five archives need five names, even inside one minute", 5, names.toSet().size)
    }

    // ------------------------------------------------------------------ #7

    @Test
    fun `Hindi uses one name for the recovery code`() {
        val hi = File("src/main/res/values-hi/strings_backup.xml").readText(Charsets.UTF_8)
        assertFalse("'बहाली कोड' must not be a second name for the recovery code", hi.contains("बहाली कोड"))
        assertTrue("the rest of the screen calls it 'वापस खोलने का कोड'", hi.contains("वापस खोलने का कोड"))
    }

    // ------------------------------------------------------------------ #2

    @Test
    fun `cancelling Save to device keeps the ready archive and offers the choices again`() {
        createBackup()
        val activity = controller!!.get()
        awaitSheet(app.getString(R.string.backup_ready))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Save to device opens the phone's save dialog", request)

        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, Intent())

        awaitSheet(app.getString(R.string.backup_ready), 5_000)
        assertTrue(
            "the cached archive survives a cancel",
            File(app.cacheDir, "fullbackup").listFiles().orEmpty().any { it.name.endsWith(".swbackup") }
        )
    }

    @Test
    fun `a failed Save to device keeps the cached file and offers retry or share`() {
        createBackup()
        val uri = Uri.parse("content://scanwhirl-test/full-disk-b1.swbackup")
        shadowOf(app.contentResolver).registerOutputStream(uri, object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("simulated full disk")
            override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("simulated full disk")
        })
        val activity = controller!!.get()
        awaitSheet(app.getString(R.string.backup_ready))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Save to device opens the phone's save dialog", request)

        ShadowToast.reset()
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))

        var toast: String? = null
        await(5_000) { toast = ShadowToast.getTextOfLatestToast(); toast != null }
        assertEquals("the failure is reported", app.getString(R.string.backup_failed), toast)
        awaitSheet(app.getString(R.string.backup_ready), 5_000)
        assertTrue(
            "the cached archive survives a failed write",
            File(app.cacheDir, "fullbackup").listFiles().orEmpty().any { it.name.endsWith(".swbackup") }
        )
    }

    // ------------------------------------------------------------------ #5

    @Test
    fun `a malformed recovery code is rejected before a restore is attempted`() {
        val code = VaultCrypto.formatRecoveryCode(VaultCrypto.generateRecoveryCode())
        doc("code_doc", "Code doc")
        val archive = File(app.cacheDir, "b1-code.swbackup")
        FullBackup(app).write(archive, code, FullBackup.Wrap.RECOVERY_CODE)
        cleanFiles()
        pickArchive(archive)

        val secretSheet = awaitSheet(app.getString(R.string.backup_restore))
        secretSheet.findViewById<EditText>(R.id.swSheetInput).setText("ABCD")
        ShadowToast.reset()
        secretSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()

        assertFalse(
            "a short code must not reach the restore: [${titles()}]",
            ShadowDialog.getShownDialogs().any {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.backup_failed_title)
            }
        )
        assertTrue("the person can correct the code in the same sheet", secretSheet.isShowing)
        assertEquals(
            "the targeted message names the recovery code",
            app.getString(R.string.backup_code_wrong),
            ShadowToast.getTextOfLatestToast()
        )
        archive.delete()
    }

    @Test
    fun `a too short backup password is rejected before a restore is attempted`() {
        doc("pw_doc", "Password doc")
        val archive = File(app.cacheDir, "b1-password.swbackup")
        FullBackup(app).write(archive, password, FullBackup.Wrap.PASSWORD)
        cleanFiles()
        pickArchive(archive)

        val secretSheet = awaitSheet(app.getString(R.string.backup_restore))
        secretSheet.findViewById<EditText>(R.id.backupPassword).setText("short")
        secretSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()

        assertFalse(
            "a short password must not reach the restore: [${titles()}]",
            ShadowDialog.getShownDialogs().any {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.backup_failed_title)
            }
        )
        assertTrue("the password sheet stays open for a retry", secretSheet.isShowing)
        archive.delete()
    }

    // ------------------------------------------------------------------ #1 and #6

    @Test
    fun `a skipped vault entry is not called an already here document and the title admits warnings`() {
        val oldVault = vaultStore()
        val setup = oldVault.setupConfirmed("246810")
        val code = setup.recoveryCode
        doc("vault_doc", "Insurance")
        oldVault.addDocument("vault_doc")
        VaultSession.close()
        val archive = File(app.cacheDir, "b1-vault.swbackup")
        FullBackup(app, oldVault).write(archive, code, FullBackup.Wrap.RECOVERY_CODE)

        // This phone already has an entry with the same id: the Vault entry is skipped.
        cleanFiles()
        VaultSession.close()
        val newVault = vaultStore()
        newVault.setupConfirmed("135790")
        doc("vault_doc", "Unrelated local document")
        newVault.addDocument("vault_doc")
        BackupActivity.vaultOverride = newVault
        pickArchive(archive)

        awaitSheet(app.getString(R.string.backup_restore))
            .findViewById<EditText>(R.id.swSheetInput).setText(code)
        awaitSheet(app.getString(R.string.backup_restore))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()

        val result = awaitResultSheet()
        assertFalse(
            "a skipped Vault entry is not a document that 'was already here': [${sheetText(result)}]",
            sheetText(result).contains("already here")
        )
        assertFalse(
            "a result with a skip cannot be titled 'Backup restored': [${sheetTitle(result)}]",
            sheetTitle(result) == app.getString(R.string.backup_restored)
        )
        archive.delete()
    }

    // ------------------------------------------------------------------ #4

    @Test
    fun `a document whose publish fails is not counted as already here`() {
        doc("broken_doc", "Broken")
        val archive = File(app.cacheDir, "b1-broken.swbackup")
        FullBackup(app).write(archive, password, FullBackup.Wrap.PASSWORD)

        // Make the documents directory impossible to rename into, so publishing really fails.
        docsDir.deleteRecursively()
        docsDir.writeText("a file, not a directory")

        val report = FullBackup(app).restore(archive, password)
        assertEquals("the failed document is not one that was already here", 0, report.skipped)
        archive.delete()
    }

    // ------------------------------------------------------------------ #3

    @Test
    fun `a failure part way through publishing returns a partial report instead of throwing`() {
        repeat(3) { doc("partial_$it", "Doc $it") }
        val archive = File(app.cacheDir, "b1-partial.swbackup")
        FullBackup(app).write(archive, password, FullBackup.Wrap.PASSWORD)
        docsDir.deleteRecursively()

        // Once a document has landed, refuse to continue. Real disk full behaves the same way.
        val limits = FullBackup.RestoreLimits(checkCancelled = {
            if (File(app.filesDir, "documents").listFiles().orEmpty().isNotEmpty()) {
                throw BackupResourceException(app)
            }
        })
        val report = FullBackup(app, null, limits).restore(archive, password)

        assertTrue("some documents really were restored", report.documents >= 1)
        assertTrue("the restore stopped part way", report.documents < 3)
        assertTrue(
            "the report says the restore was partial: ${report.warnings}",
            report.warnings.isNotEmpty()
        )
        archive.delete()
    }
}
