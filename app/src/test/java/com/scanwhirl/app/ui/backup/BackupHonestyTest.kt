package com.scanwhirl.app.ui.backup

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.VaultSession
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.io.OutputStream

/**
 * UI-W8 (lane 8, recovery surfaces): Backup & restore must be honest about what it has
 * actually done, and must be usable without a system Back gesture.
 *
 *  - A visible, labelled Back control closes the screen (it has only the system gesture
 *    today, and the screen is FLAG_SECURE so nobody can discover a hidden gesture on a
 *    screenshot either).
 *  - "Backup ready" describes the encrypted file in ScanWhirl's cache. Only after the
 *    bytes really reached the chosen destination may the screen say "Saved to <name>".
 *  - Sharing hands the file to another app; ScanWhirl cannot see whether that app wrote
 *    it, so sharing never claims "last backup: today".
 *  - A failed write changes nothing and keeps the claim honest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class BackupHonestyTest {

    private lateinit var app: Application
    private var controller: ActivityController<BackupActivity>? = null

    private val password = "correct horse battery"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        cleanFiles()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        BackupActivity.vaultOverride = null
        // androidx FileProvider caches its path roots per authority in a static map; under
        // Robolectric every test gets a fresh cacheDir, so a stale entry from an earlier test
        // makes getUriForFile fail with "Failed to find configured root". Reset it.
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

    private fun awaitSheet(title: String, timeoutMs: Long = 60_000): Dialog {
        var found: Dialog? = null
        val ok = await(timeoutMs) {
            found = ShadowDialog.getShownDialogs().lastOrNull {
                it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
            }
            found != null
        }
        assertTrue("the '$title' sheet should be showing", ok)
        return found!!
    }

    /** A ready archive, then the "Backup ready" sheet. */
    private fun createBackup(): Dialog {
        val activity = launch()
        activity.findViewById<TextView>(R.id.backupNow).performClick()
        awaitSheet(app.getString(R.string.backup_choose))
            .findViewById<TextView>(R.id.swSheetPrimary).performClick()
        val passwordSheet = awaitSheet(app.getString(R.string.backup_password_title))
        passwordSheet.findViewById<EditText>(R.id.backupPassword).setText(password)
        passwordSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        return awaitSheet(app.getString(R.string.backup_ready))
    }

    private fun lastBackupPref(): Long =
        app.getSharedPreferences("scanwhirl_prefs", 0)
            .getLong(FullBackup.PREF_LAST_BACKUP, 0L)

    private fun awaitToast(timeoutMs: Long = 20_000): String? {
        var text: String? = null
        await(timeoutMs) {
            text = ShadowToast.getTextOfLatestToast()
            text != null
        }
        return text
    }

    @Test
    fun `backup and restore shows a visible Back control that closes it`() {
        val activity = launch()
        val back = findBack(activity)
        assertNotNull(
            "the screen needs a visible Back control; it has only the system gesture " +
                "(views: ${labels(activity)})",
            back
        )
        val minTarget = (48 * activity.resources.displayMetrics.density).toInt()
        assertTrue(
            "Back keeps a 48dp touch target (${back!!.height}px)",
            back.height >= minTarget
        )
        assertTrue("Back is visible", back.isShown)
        back.performClick()
        idle()
        assertTrue("Back closes Backup & restore", activity.isFinishing)
    }

    @Test
    fun `a saved backup names where it went, and only then records today`() {
        val ready = createBackup()
        val uri = Uri.parse("content://scanwhirl-test/chosen-backup.swbackup")
        val sink = java.io.ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)
        assertEquals("nothing is recorded before the save", 0L, lastBackupPref())

        ready.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()
        val activity = controller!!.get()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Save to device opens the phone's save dialog", request)
        ShadowToast.reset()
        shadowOf(activity).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )

        val toast = awaitToast()
        assertNotNull("the save reports a result", toast)
        assertTrue(
            "the result names where the file went — got: [$toast]",
            toast!!.startsWith(app.getString(R.string.backup_saved_to, "").trim())
        )
        assertTrue(
            "the result names the destination or says it was unknown — got: [$toast]",
            toast.contains("chosen-backup.swbackup") ||
                toast.contains(app.getString(R.string.backup_saved_name_unknown))
        )
        assertTrue("the bytes reached the destination", sink.size() > 0)
        assertEquals(
            "the whole archive arrived",
            "SWFULLB1",
            String(sink.toByteArray().copyOfRange(0, 8), Charsets.UTF_8)
        )
        assertTrue("today is recorded only after the write", lastBackupPref() > 0L)
    }

    @Test
    fun `sharing never claims a backup was saved`() {
        val ready = createBackup()
        assertEquals("nothing is recorded before sharing", 0L, lastBackupPref())
        ready.findViewById<TextView>(R.id.swSheetSecondary).performClick()
        idle()
        assertEquals(
            "the share sheet may be cancelled, so sharing cannot record a saved backup",
            0L,
            lastBackupPref()
        )
    }

    @Test
    fun `a failed write keeps the screen honest`() {
        val ready = createBackup()
        val uri = Uri.parse("content://scanwhirl-test/full-disk.swbackup")
        shadowOf(app.contentResolver).registerOutputStream(uri, object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("simulated full disk")
            override fun write(b: ByteArray, off: Int, len: Int): Unit =
                throw java.io.IOException("simulated full disk")
        })

        ready.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()
        val activity = controller!!.get()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Save to device opens the phone's save dialog", request)
        ShadowToast.reset()
        shadowOf(activity).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().setData(uri)
        )

        val toast = awaitToast()
        assertEquals(
            "a refused write is reported as a failure",
            app.getString(R.string.backup_failed),
            toast
        )
        assertEquals("no saved backup is recorded", 0L, lastBackupPref())
    }

    @Test
    fun `the backup screen stays protected from screenshots`() {
        val activity = launch()
        assertTrue(
            "FLAG_SECURE on Backup & restore must stay unchanged",
            com.scanwhirl.app.util.SecureView.isSecure(activity.window)
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun findBack(activity: Activity): View? {
        val expected = activity.getString(R.string.backup_back)
        val match = mutableListOf<View>()
        fun walk(v: View) {
            if (v.isClickable && (v.contentDescription?.toString() == expected ||
                    (v is TextView && v.text?.toString() == expected))
            ) {
                match += v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window!!.decorView)
        return match.firstOrNull()
    }

    private fun labels(activity: Activity): String {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.text?.isNotBlank() == true) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window!!.decorView)
        return out.joinToString(" | ")
    }
}
