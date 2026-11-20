package com.scanwhirl.app

import android.app.Dialog
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.tools.VaultFlow
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.ui.backup.BackupActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.VaultPinHash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultCredentialThreadTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo by lazy { ScanRepository(app) }

    @After fun cleanup() {
        VaultPinHash.derivationProbe = null
        VaultCrypto.derivationProbe = null
        VaultActivity.storeOverride = null
        BackupActivity.vaultOverride = null
        VaultSession.close()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(message, condition())
    }

    private fun sheet(): Dialog = checkNotNull(ShadowDialog.getLatestDialog())
        .also { assertTrue("expected a visible credential sheet", it.isShowing) }

    private fun submit(sheet: Dialog, secret: String, input: Int = R.id.swSheetInput): Long {
        sheet.findViewById<EditText>(input).setText(secret)
        val start = System.nanoTime()
        sheet.findViewById<View>(R.id.swSheetPrimary).performClick()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun recorder(): Pair<ConcurrentLinkedQueue<Boolean>, () -> Unit> {
        val calls = ConcurrentLinkedQueue<Boolean>()
        return calls to { calls.add(Thread.currentThread() == Looper.getMainLooper().thread); Unit }
    }

    @Test fun `legacy Home verification derives once off main`() {
        Prefs.setVaultPin(app, "2580")
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var opened = false
            val flow = VaultFlow(activity, repo, activity, {}, { opened = it }, {})
            flow.openVaultWithPin()
            val (calls, probe) = recorder()
            VaultPinHash.derivationProbe = probe
            val elapsed = submit(sheet(), "2580")
            await("correct PIN opens Home Vault") { opened }
            assertEquals("Home check click occupied main for ${elapsed}ms", listOf(false), calls.toList())
        }
    }

    @Test fun `legacy Home save derives once off main`() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var saved = false
            VaultFlow(activity, repo, activity, {}, {}, {}).promptSetVaultPin { saved = true }
            val (calls, probe) = recorder()
            VaultPinHash.derivationProbe = probe
            val elapsed = submit(sheet(), "2580")
            await("save delivers success") { saved }
            assertTrue(Prefs.hasVaultPin(app))
            assertEquals("Home save click occupied main for ${elapsed}ms", listOf(false), calls.toList())
        }
    }

    @Test fun `Vault legacy verification derives once off main`() {
        Prefs.setVaultPin(app, "2580")
        VaultActivity.storeOverride = VaultStore(app, repo, FakeVaultKeys())
        Robolectric.buildActivity(VaultActivity::class.java).setup().use {
            val legacy = sheet()
            val (calls, probe) = recorder()
            VaultPinHash.derivationProbe = probe
            val elapsed = submit(legacy, "2580", R.id.vaultLegacyPin)
            await("correct old PIN continues to setup") { !legacy.isShowing }
            assertEquals("Vault legacy check click occupied main for ${elapsed}ms", listOf(false), calls.toList())
        }
    }

    @Test fun `Backup recovery verification derives once off main for a wrong valid code`() {
        val store = VaultStore(app, repo, FakeVaultKeys())
        val code = VaultCrypto.normalizeRecoveryCode(store.setup("246810").recoveryCode)
        store.lock()
        BackupActivity.vaultOverride = store
        Robolectric.buildActivity(BackupActivity::class.java).setup().use { controller ->
            controller.get().findViewById<View>(R.id.backupNow).performClick()
            sheet().findViewById<View>(R.id.swSheetPrimary).performClick()
            val recovery = sheet()
            val (calls, probe) = recorder()
            VaultCrypto.derivationProbe = probe
            val wrong = (if (code[0] == 'A') 'B' else 'A') + code.drop(1)
            assertTrue(VaultCrypto.isRecoveryCode(wrong))
            val elapsed = submit(recovery, wrong)
            await("wrong code is reported") {
                ShadowToast.getTextOfLatestToast()?.toString() == app.getString(R.string.backup_code_wrong)
            }
            assertTrue("wrong code allows a retry", recovery.isShowing)
            assertEquals("Backup check click occupied main for ${elapsed}ms", listOf(false), calls.toList())
        }
    }
}
