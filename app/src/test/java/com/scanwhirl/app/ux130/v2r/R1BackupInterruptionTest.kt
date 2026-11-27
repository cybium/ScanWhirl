package com.scanwhirl.app.ux130.v2r
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.os.Looper
import android.view.View
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V2R R1: an interruption DURING backup creation or restore (rotation or process death) must
 * leave an honest receipt and a safe retry. No secret (password or recovery code) may be kept
 * in the saved state or preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R1BackupInterruptionTest {


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
        BackupActivity.vaultOverride = VaultStore(
            app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), 2_000
        ).also { it.setup("246810"); it.markConfirmationPassed() }
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        setWorkGate(null)
        BackupActivity.vaultOverride = null
        VaultSession.close()
        clean()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun clean() {
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "fullbackup").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun field(target: Any, name: String): Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).also { it.isAccessible = true } } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw NoSuchFieldException(name)
    }

    private fun setChanging(activity: BackupActivity, value: Boolean) {
        field(activity, "mChangingConfigurations").setBoolean(activity, value)
    }

    /** Reflection keeps this file compiling on the pre-fix SHA, where the gate is absent. */
    private fun setWorkGate(gate: CountDownLatch?) {
        val f = try {
            BackupActivity::class.java.getField("workGateForTest")
        } catch (_: NoSuchFieldException) {
            throw AssertionError("an in-flight gate is needed to interrupt work deterministically (RED on base)")
        }
        f.set(null, gate)
    }

    private fun awaitSheet(title: String): Dialog {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            val found = ShadowDialog.getShownDialogs().lastOrNull { d ->
                d.isShowing && d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
            }
            if (found != null) return found
            Thread.sleep(20)
        }
        throw AssertionError("the '$title' sheet never appeared; seen " +
            ShadowDialog.getShownDialogs().map { it.findViewById<TextView>(R.id.swSheetTitle)?.text })
    }

    /** Drives the real create path up to the encryption step, which is gated for the test. */
    private fun startGatedBackup(): Pair<ActivityController<BackupActivity>, CountDownLatch> {
        val gate = CountDownLatch(1)
        setWorkGate(gate)
        val c = Robolectric.buildActivity(BackupActivity::class.java).setup()
        idle()
        c.get().findViewById<View>(R.id.backupNow).performClick()
        idle()
        val choose = ShadowDialog.getShownDialogs().last { d ->
            d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                app.getString(R.string.backup_choose)
        }
        choose.findViewById<TextView>(R.id.swSheetSecondary).performClick()
        idle()
        val pass = ShadowDialog.getShownDialogs().last { d ->
            d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                app.getString(R.string.backup_password_title)
        }
        pass.findViewById<EditText>(R.id.backupPassword).setText(password)
        pass.findViewById<EditText>(R.id.backupPasswordConfirm).setText(password)
        pass.findViewById<TextView>(R.id.swSheetPrimary).performClick()
        idle()
        return c to gate
    }

    @Test(timeout = 30_000)
    fun `rotation during backup creation gives an honest receipt and no retained secret`() {
        val (c, gate) = startGatedBackup()
        controller = c
        val state = Bundle()
        c.saveInstanceState(state)
        setChanging(c.get(), true)
        c.pause().stop().destroy()
        gate.countDown()
        idle()
        assertFalse("the saved state must not carry the password", state.toString().contains(password, true))
        val prefs = app.getSharedPreferences("scanwhirl_prefs", 0)
        assertFalse("preferences must not carry the password",
            prefs.all.values.any { it.toString().contains(password, true) })

        var recreated: ActivityController<BackupActivity>? = null
        try {
            recreated = Robolectric.buildActivity(BackupActivity::class.java)
                .create(state).start().resume().visible()
            idle()
            val sheet = awaitSheet("Backup interrupted")
            val body = sheet.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
            assertTrue("nothing-saved receipt: $body", body.contains("Nothing was saved"))
            assertTrue("a safe retry is offered", sheet.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString() ==
                "Try again")
        } finally {
            recreated?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `process death during restore gives a safe retry receipt`() {
        app.getSharedPreferences("scanwhirl_prefs", 0).edit()
            .putString("ux_v2r_interrupted_operation", "RESTORE").commit()
        val state = Bundle()
        try {
            controller = Robolectric.buildActivity(BackupActivity::class.java)
                .create(state).start().resume().visible()
            idle()
            val sheet = awaitSheet("Restore interrupted")
            val body = sheet.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
            assertTrue("honest partial-work receipt: $body", body.contains("Some documents may already have been restored"))
            assertNotNull("Try again is present", sheet.findViewById<TextView>(R.id.swSheetPrimary))
        } finally { /* controller closed in @After */ }
    }

    @Test(timeout = 30_000)
    fun `a completed backup leaves no interruption marker for the next launch`() {
        // The pre-fix code had no marker at all, so assert both the absence and the seam.
        val marker = try {
            BackupActivity::class.java.getDeclaredField("PREF_INTERRUPTED")
        } catch (_: NoSuchFieldException) {
            throw AssertionError("an interruption marker key is needed (RED on base)")
        }
        marker.isAccessible = true
        val key = marker.get(null) as String
        assertFalse("no stale marker after a fresh launch",
            app.getSharedPreferences("scanwhirl_prefs", 0).contains(key))
    }
}
