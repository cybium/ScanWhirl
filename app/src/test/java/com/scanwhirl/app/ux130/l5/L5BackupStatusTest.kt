package com.scanwhirl.app.ux130.l5
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.backup.BackupActivity
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
import java.io.File

/** L5-05 — one honest summary, a separate Vault status and visible progress for both jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L5BackupStatusTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private var controller: ActivityController<BackupActivity>? = null
    private val password = "correct horse battery"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        clean()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        BackupActivity.vaultOverride = null
        VaultSession.close()
        clean()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun clean() {
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        File(app.cacheDir, "fullbackup").deleteRecursively()
    }

    private fun vault(ready: Boolean): VaultStore = VaultStore(
        app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), 2_000
    ).also {
        if (ready) { it.setup("246810"); it.markConfirmationPassed() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(ready: Boolean = true): BackupActivity {
        BackupActivity.vaultOverride = vault(ready)
        controller = Robolectric.buildActivity(BackupActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun byId(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else activity.findViewById(id)
    }

    private fun sheet(title: String): Dialog {
        val found = ShadowDialog.getShownDialogs().lastOrNull {
            it.isShowing && it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
        }
        assertNotNull("the '$title' sheet is showing; seen ${ShadowDialog.getShownDialogs().map {
            it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
        }}", found)
        return found!!
    }

    @Test
    fun `landing has one summary and an accurate separate Vault status`() {
        val activity = launch(ready = true)
        val summary = byId(activity, "uxL5BackupSummary")
        assertNotNull("one summary replaces the long explanation (RED on base)", summary)
        assertEquals("Back up scans, signatures and settings.", (summary as TextView).text.toString())
        val status = byId(activity, "uxL5BackupVaultStatus")
        assertNotNull("Vault readiness is its own status (RED on base)", status)
        assertEquals("Vault: ready to include.", (status as TextView).text.toString())
        // The freshness line is still present and still honest.
        assertNotNull(activity.findViewById<View>(R.id.backupLastLine))
    }

    @Test
    fun `a pending Vault says it is not included yet`() {
        val activity = launch(ready = false)
        val status = byId(activity, "uxL5BackupVaultStatus") as TextView?
        assertNotNull(status)
        assertEquals("Vault: not included until setup is finished.", status!!.text.toString())
    }

    @Test
    fun `Create backup and Restore backup are named 48dp Buttons`() {
        val activity = launch()
        val create = activity.findViewById<TextView>(R.id.backupNow)
        val restore = activity.findViewById<TextView>(R.id.backupRestore)
        assertEquals("Create backup", create.text.toString())
        assertEquals("Restore backup", restore.text.toString())
        for (button in listOf(create, restore)) {
            assertTrue(
                "${button.text} 48dp target: ${button.minimumHeight}px of " +
                    "${(48f * activity.resources.displayMetrics.density).toInt()}px",
                button.minimumHeight >= (48f * activity.resources.displayMetrics.density).toInt()
            )
            val info = AccessibilityNodeInfo.obtain()
            button.onInitializeAccessibilityNodeInfo(info)
            assertEquals("Button role", android.widget.Button::class.java.name, info.className.toString())
        }
    }

    @Test
    fun `backup runs behind the shared progress sheet and freezes duplicate actions`() {
        val activity = launch()
        val method = BackupActivity::class.java.methods.firstOrNull {
            it.name == "workInFlightForTest" && it.parameterCount == 0
        }
        assertNotNull("a busy seam for the frozen duplicate guard (RED on base)", method)

        activity.findViewById<View>(R.id.backupNow).performClick()
        idle()
        sheet(app.getString(R.string.backup_choose))
            .findViewById<TextView>(R.id.swSheetSecondary).performClick()
        idle()
        val passwordSheet = sheet(app.getString(R.string.backup_password_title))
        passwordSheet.findViewById<EditText>(R.id.backupPassword).setText(password)
        passwordSheet.findViewById<EditText>(R.id.backupPasswordConfirm).setText(password)
        passwordSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()

        val busy = method!!.invoke(activity)
        assertTrue("the busy seam reports a Boolean", busy is Boolean)
        val progressId = app.resources.getIdentifier("swProgressRoot", "id", app.packageName)
        assertTrue("the shared progress sheet owns the visible work", progressId != 0)
        assertNotNull(
            "progress is up, not a bare Toast",
            ShadowDialog.getShownDialogs().lastOrNull { it.findViewById<View>(progressId) != null }
        )
    }

    /**
     * Drives the real create path with a fictional failure injected at the start of its coroutine.
     * The seam is read reflectively so this file still compiles on the pre-fix SHA, where it is
     * absent (that is the RED state).
     */
    private fun assertFailureDismissesProgress(error: Throwable) {
        val seam = try {
            BackupActivity::class.java.getField("failWorkForTest")
        } catch (missing: NoSuchFieldException) {
            throw AssertionError("a failure seam is needed to prove the explicit finally", missing)
        }
        @Suppress("UNCHECKED_CAST")
        val previous = seam.get(null) as? Throwable
        seam.set(null, error)
        try {
            val activity = launch()
            activity.findViewById<View>(R.id.backupNow).performClick()
            idle()
            sheet(app.getString(R.string.backup_choose))
                .findViewById<TextView>(R.id.swSheetSecondary).performClick()
            idle()
            val passwordSheet = sheet(app.getString(R.string.backup_password_title))
            passwordSheet.findViewById<EditText>(R.id.backupPassword).setText(password)
            passwordSheet.findViewById<EditText>(R.id.backupPasswordConfirm).setText(password)
            passwordSheet.findViewById<TextView>(R.id.swSheetPrimary).performClick()
            idle()
            // Let the launched coroutine start, hit the injected throw and run its cleanup.
            val deadline = System.currentTimeMillis() + 5_000
            val busyMethod = BackupActivity::class.java.getMethod("workInFlightForTest")
            while (System.currentTimeMillis() < deadline && busyMethod.invoke(activity) as Boolean) {
                idle()
                Thread.sleep(10)
            }
            assertFalse("the busy flag is cleared on failure", busyMethod.invoke(activity) as Boolean)
            val progressId = app.resources.getIdentifier("swProgressRoot", "id", app.packageName)
            assertTrue("the progress id exists", progressId != 0)
            assertFalse(
                "the progress sheet is dismissed on failure, not left on screen",
                ShadowDialog.getShownDialogs().any {
                    it.isShowing && it.findViewById<View>(progressId) != null
                }
            )
        } finally {
            seam.set(null, previous)
        }
    }

    @Test
    fun `an exception during backup creation dismisses progress`() =
        assertFailureDismissesProgress(IllegalStateException("fictional backup failure"))

    @Test
    fun `cancellation during backup creation dismisses progress`() =
        assertFailureDismissesProgress(java.util.concurrent.CancellationException("fictional cancellation"))
}
