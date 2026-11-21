package com.scanwhirl.app.ui.backup

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
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import org.junit.After
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
import java.io.ByteArrayInputStream
import java.io.File

/**
 * T34: the full backup report's warnings must reach the person, not just the log.
 *
 * Merged T29 made `FullBackup.write()` report that a Vault whose setup is unfinished is left out
 * of the archive, but Backup & restore only used `isSuccess`, so the "Backup ready" sheet claimed
 * a complete backup while the Vault files and key were absent. The sheet now shows those
 * sentences below the size note. A finished Vault keeps the sheet unchanged. The restore path
 * already appended its report's warnings; this test pins that too, so a future edit cannot drop
 * the deferred-Vault sentence there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BackupWarningSheetTest {

    private lateinit var app: Application
    private var controller: ActivityController<BackupActivity>? = null

    private val password = "correct horse battery"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
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

    private fun vaultStore(): VaultStore = VaultStore(
        app,
        ScanRepository(app),
        FakeVaultKeys(),
        VaultPasscodeGate(),
        2_000
    )

    /** A fictional library document in the shape the app writes (no real user media). */
    private fun doc(id: String, name: String) {
        val dir = File(File(app.filesDir, "documents"), id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        File(dir, "page_000.jpg").writeBytes(ByteArray(256) { it.toByte() })
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 60_000, condition: () -> Boolean): Boolean {
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

    private fun titles(): String = ShadowDialog.getShownDialogs()
        .joinToString(", ") { it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty() }

    /** The bottom sheet whose title is [title], waiting for it to be shown. */
    private fun awaitSheet(title: String, timeoutMs: Long = 60_000): Dialog {
        var found: Dialog? = null
        val ok = await(timeoutMs) {
            found = ShadowDialog.getShownDialogs().lastOrNull {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
            }
            found != null
        }
        assertTrue("the '$title' sheet should be showing; seen: [${titles()}]", ok)
        return found!!
    }

    /** The size note's fixed text after its size placeholder (the size itself varies). */
    private fun sizeNoteTail(): String =
        app.getString(R.string.backup_ready_note, "\u0001").substringAfter("\u0001")

    private fun sheetText(dialog: Dialog): String =
        dialog.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()

    /** Backup & restore -> Backup now -> a backup password -> Continue. */
    private fun createBackup(): Dialog {
        val activity = launch()
        val now = activity.findViewById<TextView>(R.id.backupNow)
        assertNotNull("the Backup now button is on the screen", now)
        now.performClick()
        // A Vault that owes its four-character check still answers isSetUp(), so the password
        // route is the secondary button of the "Protect the backup" sheet.
        awaitSheet(app.getString(R.string.backup_choose))
            .findViewById<TextView>(R.id.swSheetSecondary).performClick()
        val passwordSheet = awaitSheet(app.getString(R.string.backup_password_title))
        passwordSheet.findViewById<EditText>(R.id.backupPassword).setText(password)
        passwordSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        return awaitSheet(app.getString(R.string.backup_ready))
    }

    @Test
    fun `a pending Vault says on the Backup ready sheet that it is not in the archive`() {
        val vault = vaultStore()
        vault.setup("246810") // the one-time recovery code's check is still owed: pending
        assertTrue("the test needs a pending Vault", vault.isConfirmationPending())
        BackupActivity.vaultOverride = vault
        doc("doc_lib", "Library doc")

        val ready = createBackup()

        val message = sheetText(ready)
        val omitted = app.getString(R.string.vault_pending_backup_omitted)
        assertTrue(
            "the person must be told the Vault is missing; sheet said: [$message]",
            message.contains(omitted)
        )
        val sizeNote = message.lines().first()
        assertTrue("the size note stays first; sheet said: [$message]", sizeNote.endsWith(sizeNoteTail()))
        assertFalse("a small backup never reads as 0 MB; sheet said: [$message]", sizeNote.startsWith("0"))
        assertTrue(
            "the warning sits below the size note; sheet said: [$message]",
            message.indexOf(omitted) > message.indexOf(sizeNote)
        )
    }

    @Test
    fun `a finished Vault leaves the Backup ready sheet free of warnings`() {
        val vault = vaultStore()
        vault.setup("246810")
        vault.markConfirmationPassed()
        assertFalse("the test needs a finished Vault", vault.isConfirmationPending())
        BackupActivity.vaultOverride = vault
        doc("doc_vault", "Insurance")
        vault.addDocument("doc_vault")

        val ready = createBackup()

        val message = sheetText(ready)
        assertFalse(
            "no omitted-Vault sentence on a complete backup; sheet said: [$message]",
            message.contains(app.getString(R.string.vault_pending_backup_omitted))
        )
        assertTrue(
            "the size note is still the whole message; sheet said: [$message]",
            message.lines().first().endsWith(sizeNoteTail()) && !message.startsWith("0")
        )
    }

    @Test
    fun `a restore into a pending Vault says on the result sheet that the Vault part waits`() {
        // The other phone: a finished Vault with one entry, written under a backup password.
        val oldVault = vaultStore()
        oldVault.setup("246810")
        oldVault.markConfirmationPassed()
        doc("doc_vault", "Insurance")
        oldVault.addDocument("doc_vault")
        val archive = File(app.cacheDir, "source-for-restore.swbackup")
        FullBackup(app, oldVault).write(archive, password, FullBackup.Wrap.PASSWORD)
        assertTrue("the archive carries the Vault key", FullBackup(app).header(archive).hasVaultKey)

        // This phone: a Vault whose four-character check is still owed.
        cleanFiles()
        VaultSession.close()
        val pending = vaultStore()
        pending.setup("135790")
        assertTrue("the test needs a pending Vault", pending.isConfirmationPending())
        BackupActivity.vaultOverride = pending

        val uri = Uri.parse("content://scanwhirl-test/backup.swbackup")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(archive.readBytes()))

        val activity = launch()
        activity.findViewById<TextView>(R.id.backupRestore).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Restore opens the file picker", request)
        shadowOf(activity).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )

        val secretSheet = awaitSheet(app.getString(R.string.backup_restore))
        secretSheet.findViewById<EditText>(R.id.backupPassword).setText(password)
        secretSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()

        // B1 expectation change: this restore really leaves the Vault part waiting (a warning),
        // so the honest title is "Restore finished with warnings", not "Backup restored". The
        // old title would claim a complete restore; the assertion below still checks the warning.
        val restored = awaitSheet(app.getString(R.string.b1_restore_warning_title))
        val message = sheetText(restored)
        val deferred = app.getString(R.string.vault_pending_restore_deferred)
        assertTrue(
            "the person must be told the Vault part was not restored; sheet said: [$message]",
            message.contains(deferred)
        )
        archive.delete()
    }
}
