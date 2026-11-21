package com.scanwhirl.app.ux130.uxg

import android.app.Application
import android.os.Bundle
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Field

/**
 * UX lane UXG — G2: the encrypted pending archive must survive a configuration change,
 * be re-offered after recreation, and be deleted only on a real finish.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class UxgG2RotationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        File(app.cacheDir, "fullbackup").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        BackupActivity.vaultOverride = null
        VaultSession.close()
        File(app.cacheDir, "fullbackup").deleteRecursively()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun vault(): VaultStore = VaultStore(
        app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(), 2_000
    ).also { it.setup("246810"); it.markConfirmationPassed() }

    private fun field(target: Any, name: String): Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun setChanging(activity: BackupActivity, value: Boolean) {
        // android.app.Activity.isChangingConfigurations() reads this framework field.
        field(activity, "mChangingConfigurations").setBoolean(activity, value)
    }

    private fun pendingFile(): File = File(app.cacheDir, "fullbackup/uxg-pending.swbackup")

    @Test(timeout = 30_000)
    fun `rotation keeps encrypted pending archive and re-offers save share, finish deletes`() {
        val file = pendingFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(2048) { 0x5A })

        BackupActivity.vaultOverride = vault()
        var rotateController: ActivityController<BackupActivity>? = null
        var recreatedController: ActivityController<BackupActivity>? = null
        try {
            rotateController = Robolectric.buildActivity(BackupActivity::class.java).setup()
            val first = rotateController.get()
            field(first, "pending").set(first, file)
            field(first, "pendingWarnings").set(first, listOf("Vault was left out."))
            assertTrue("precondition: archive exists", file.isFile)

            // A configuration change: save state, mark rotation, destroy.
            val state = Bundle()
            rotateController.saveInstanceState(state)
            setChanging(first, true)
            rotateController.pause().stop().destroy()
            assertTrue("G2 RED: rotation deleted the encrypted archive", file.isFile)

            // Recreate with the saved state; the save/share offer must come back.
            recreatedController = Robolectric.buildActivity(BackupActivity::class.java)
                .create(state).start().resume().visible()
            idle()
            val recreated = recreatedController.get()
            assertEquals("G2 RED: pending archive was not restored", file,
                field(recreated, "pending").get(recreated))
            val ready = ShadowDialog.getShownDialogs().lastOrNull { d ->
                d.isShowing && d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    d.context.getString(R.string.backup_ready)
            }
            assertNotNull("G2 RED: the save/share offer was not re-shown", ready)

            // A real finish releases the cached bytes.
            setChanging(recreated, false)
            recreatedController.pause().stop().destroy()
            assertTrue("G2 RED: a real finish must delete the cached archive", !file.isFile)
        } finally {
            recreatedController?.close()
            rotateController?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `plain destroy without configuration change deletes the pending archive`() {
        val file = pendingFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64) { 1 })

        BackupActivity.vaultOverride = vault()
        var c: ActivityController<BackupActivity>? = null
        try {
            c = Robolectric.buildActivity(BackupActivity::class.java).setup()
            val activity = c.get()
            field(activity, "pending").set(activity, file)
            setChanging(activity, false)
            c.pause().stop().destroy()
            assertTrue("G2 RED: non-configuration destroy must delete the archive", !file.isFile)
        } finally {
            c?.close()
        }
    }
}
