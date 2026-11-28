package com.scanwhirl.app.ui.backup

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
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
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Round-2 B1 fixes in BackupActivity, each RED on ab616ae6 and GREEN after the fix:
 *  - R03 F1: rotation at the restore-secret / Vault-unlock sheet deletes the picked copy and
 *    drops the restore. Only a path may be saved (never the secret) and the sheet must return.
 *  - R03 F2: runRestore began its operation before the busy guard, clobbering a live backup marker.
 *  - R05 F1: the backup share sent EXTRA_STREAM without ClipData, so the read grant was lost.
 *
 * Positive controls cover an ordinary restore, ordinary backup creation/share, and the real-exit
 * delete that rotation must NOT turn into a leak.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class B1BackupFixTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private var controller: ActivityController<BackupActivity>? = null
    private val password = "correct horse battery"

    private val docsDir get() = File(app.filesDir, "documents")

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

    private fun awaitResultSheet(): Dialog {
        val restoreTitle = app.getString(R.string.backup_restore)
        var found: Dialog? = null
        val ok = await {
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

    private fun sheetTitle(dialog: Dialog): String =
        dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty()

    private fun field(target: Any, name: String): Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).also { it.isAccessible = true } }
            catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun setChanging(activity: BackupActivity, value: Boolean) {
        field(activity, "mChangingConfigurations").setBoolean(activity, value)
    }

    private fun restoreSourceOf(activity: BackupActivity): File? = field(activity, "restoreSource").get(activity) as? File

    private fun invokeRestore(activity: BackupActivity, file: File, secret: String, allowPrompt: Boolean) {
        val method: Method = BackupActivity::class.java.getDeclaredMethod(
            "runRestore", File::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(activity, file, secret, allowPrompt)
    }

    private fun vaultStore(iterations: Int = 2_000): VaultStore = VaultStore(
        app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), iterations
    )

    private fun VaultStore.setupConfirmed(passcode: String): VaultStore.Setup =
        setup(passcode).also { markConfirmationPassed() }

    private fun doc(id: String, name: String): File {
        val dir = File(docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        return dir
    }

    /** A valid password-wrapped archive with one document, ready for the SAF picker. */
    private fun makeArchive(name: String): File {
        doc("fix_doc", "Fix doc")
        val archive = File(app.cacheDir, name)
        FullBackup(app).write(archive, password, FullBackup.Wrap.PASSWORD)
        cleanFiles()
        return archive
    }

    /** Drives the real pick -> BoundedCopy -> secret-sheet path. */
    private fun pickArchive(activity: BackupActivity, archive: File) {
        val uri = Uri.parse("content://scanwhirl-test/${archive.name}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(archive.readBytes()))
        activity.findViewById<TextView>(R.id.backupRestore).performClick()
        idle()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Restore opens the file picker", request)
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        // BoundedCopy.copy is suspend: the picked copy is handed to restoreSource only after
        // that coroutine completes. One idle() races it and left restoreSourceOf() null.
        assertTrue("the picked copy is handed to the restore sheet", await {
            restoreSourceOf(activity) != null
        })
        idle()
    }

    // ------------------------------------------------------------------ R03 F1

    @Test(timeout = 30_000)
    fun `rotation at the restore secret sheet keeps the picked copy and completes the restore`() {
        val archive = makeArchive("b1-fix-rotate.swbackup")
        try {
            val original = Robolectric.buildActivity(BackupActivity::class.java).setup()
            controller = original
            pickArchive(original.get(), archive)
            awaitSheet(app.getString(R.string.backup_restore))
            val copy = restoreSourceOf(original.get())!!
            assertTrue("the picker made a private copy", copy.name.startsWith("picked-backup-"))
            assertTrue(copy.isFile)

            val state = Bundle()
            original.saveInstanceState(state)
            assertFalse("saved state must not carry the password", state.toString().contains(password, true))
            assertTrue("only the copy's path is saved", state.toString().contains(copy.name))

            setChanging(original.get(), true)
            original.pause().stop().destroy()
            assertTrue("rotation must not delete the picked backup", copy.isFile)

            var recreated: ActivityController<BackupActivity>? = null
            try {
                recreated = Robolectric.buildActivity(BackupActivity::class.java)
                    .create(state).start().resume().visible()
                idle()
                val fresh = recreated.get()
                assertEquals("the new screen owns the same copy", copy, restoreSourceOf(fresh))
                assertTrue("the copy is still on disk after recreation", copy.isFile)
                awaitSheet(app.getString(R.string.backup_restore))
                    .findViewById<EditText>(R.id.backupPassword).setText(password)
                awaitSheet(app.getString(R.string.backup_restore))
                    .findViewById<TextView>(R.id.swSheetPrimary).performClick()
                assertEquals(
                    "the restore was not dropped by rotation",
                    app.getString(R.string.backup_restored),
                    sheetTitle(awaitResultSheet())
                )
                assertTrue("the document really came back", File(docsDir, "fix_doc").isDirectory)
            } finally {
                recreated?.close()
            }
        } finally {
            archive.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `rotation at the Vault unlock prompt keeps the picked copy and re-offers the secret`() {
        val oldVault = vaultStore()
        val code = oldVault.setupConfirmed("246810").recoveryCode
        val archive = File(app.cacheDir, "b1-fix-keyed.swbackup")
        FullBackup(app, oldVault).write(archive, code, FullBackup.Wrap.RECOVERY_CODE)
        cleanFiles()
        VaultSession.close()

        // Base fix (compilation): setupConfirmed() returns a Setup result, not the store.
        // Keep the store so the same phone can be locked before the restore prompt.
        val thisPhone = vaultStore()
        thisPhone.setupConfirmed("135790")
        thisPhone.lock()
        BackupActivity.vaultOverride = thisPhone
        try {
            val original = Robolectric.buildActivity(BackupActivity::class.java).setup()
            controller = original
            pickArchive(original.get(), archive)
            awaitSheet(app.getString(R.string.backup_restore))
                .findViewById<EditText>(R.id.swSheetInput).setText(code)
            awaitSheet(app.getString(R.string.backup_restore))
                .findViewById<TextView>(R.id.swSheetPrimary).performClick()
            idle()
            assertNotNull(
                "the keyed backup prompts for Vault unlock",
                shadowOf(original.get()).nextStartedActivityForResult
            )
            val copy = restoreSourceOf(original.get())!!
            assertTrue(copy.isFile)

            val state = Bundle()
            original.saveInstanceState(state)
            assertFalse("the recovery code is never saved", state.toString().contains(code, true))
            setChanging(original.get(), true)
            original.pause().stop().destroy()
            assertTrue("rotation at the unlock prompt must not delete the copy", copy.isFile)

            var recreated: ActivityController<BackupActivity>? = null
            try {
                recreated = Robolectric.buildActivity(BackupActivity::class.java)
                    .create(state).start().resume().visible()
                idle()
                assertEquals("the new screen owns the copy", copy, restoreSourceOf(recreated.get()))
                awaitSheet(app.getString(R.string.backup_restore))
            } finally {
                recreated?.close()
            }
        } finally {
            archive.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `a real finish still deletes the picked copy waiting at the secret sheet`() {
        val archive = makeArchive("b1-fix-finish.swbackup")
        try {
            val c = Robolectric.buildActivity(BackupActivity::class.java).setup()
            controller = c
            pickArchive(c.get(), archive)
            awaitSheet(app.getString(R.string.backup_restore))
            val copy = restoreSourceOf(c.get())!!
            assertTrue(copy.isFile)
            c.get().finish()
            c.destroy()
            assertFalse("a real exit is not a rotation: the copy is deleted", copy.isFile)
        } finally {
            archive.delete()
        }
    }

    // ------------------------------------------------------------------ R03 F2

    @Test(timeout = 30_000)
    fun `a restore requested while another operation is busy is refused with feedback`() {
        val activity = launch()
        field(activity, "busy").setBoolean(activity, true)
        assertTrue("the test really holds the busy lock", activity.workInFlightForTest())
        val file = File(app.cacheDir, "b1-fix-busy.swbackup").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        ShadowToast.reset()
        invokeRestore(activity, file, password, allowPrompt = false)
        idle()

        assertEquals(
            "the person is told the app is busy (an existing string is reused)",
            app.getString(R.string.progress_title),
            ShadowToast.getTextOfLatestToast()
        )
        val marker = app.getSharedPreferences("scanwhirl_prefs", 0)
            .getString("ux_v2r_interrupted_operation", null)
        assertFalse("the busy guard must run before beginOperation: marker = $marker", marker == "RESTORE")
        assertFalse("the dropped restore releases its private copy", file.isFile)
    }

    // ------------------------------------------------------------------ positive controls

    @Test(timeout = 30_000)
    fun `a normal restore still completes`() {
        val archive = makeArchive("b1-fix-normal-restore.swbackup")
        try {
            val activity = launch()
            pickArchive(activity, archive)
            // The picker hands off on lifecycleScope: wait for the private copy
            // before asserting it (the assertion itself is unchanged).
            assertTrue("the picker made a private copy", await { restoreSourceOf(activity) != null })
            val copy = restoreSourceOf(activity)!!
            awaitSheet(app.getString(R.string.backup_restore))
                .findViewById<EditText>(R.id.backupPassword).setText(password)
            awaitSheet(app.getString(R.string.backup_restore))
                .findViewById<TextView>(R.id.swSheetPrimary).performClick()
            assertEquals(
                app.getString(R.string.backup_restored),
                sheetTitle(awaitResultSheet())
            )
            assertTrue(File(docsDir, "fix_doc").isDirectory)
            assertFalse("the copy is released when the restore ends", copy.isFile)
        } finally {
            archive.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `a normal backup still works and its share carries ClipData with a read grant`() {
        val activity = launch()
        activity.findViewById<View>(R.id.backupNow).performClick()
        awaitSheet(app.getString(R.string.backup_choose))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        val sheet = awaitSheet(app.getString(R.string.backup_password_title))
        sheet.findViewById<EditText>(R.id.backupPassword).setText(password)
        sheet.findViewById<EditText>(R.id.backupPasswordConfirm).setText(password)
        sheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        val ready = awaitSheet(app.getString(R.string.backup_ready))
        val cached = File(app.cacheDir, "fullbackup").listFiles().orEmpty()
            .firstOrNull { it.name.endsWith(".swbackup") }
        assertNotNull("the archive was created", cached)

        ready.findViewById<TextView>(R.id.swSheetSecondary).performClick()
        idle()
        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("share opens a chooser", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("the chooser wraps the ACTION_SEND intent", send)
        @Suppress("DEPRECATION")
        val uri = send!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the archive is attached", uri)
        val clip: ClipData = send.clipData
            ?: throw AssertionError("backup share must carry ClipData for the Android 12+ sharesheet")
        assertEquals(1, clip.itemCount)
        assertEquals("ClipData must carry the very same URI", uri, clip.getItemAt(0).uri)
        assertEquals("the archive's name is the clip label", cached!!.name, clip.description.label.toString())
        assertTrue("share needs a read grant", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("no write grant may be given", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
