package com.scanwhirl.app
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.tools.VaultFlow
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.ui.backup.BackupActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.SecureView
import com.scanwhirl.app.util.VaultPinHash
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultCredentialThreadTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo by lazy { ScanRepository(app) }
    private val prefs get() = app.getSharedPreferences("scanwhirl_prefs", 0)
    private val pinRecord get() = prefs.getString("vault_pin_sha256", null)

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

    private fun homeFlow(activity: MainActivity, onOpen: (Boolean) -> Unit = {}): VaultFlow =
        VaultFlow(activity, repo, activity, {}, onOpen, {})

    private fun recoverySheet(activity: BackupActivity): Dialog {
        activity.findViewById<View>(R.id.backupNow).performClick()
        sheet().findViewById<View>(R.id.swSheetPrimary).performClick()
        return sheet()
    }

    private class HeldWorker {
        val calls = ConcurrentLinkedQueue<Thread>()
        private val release = CountDownLatch(1)
        fun probe() {
            calls.add(Thread.currentThread())
            check(release.await(30, TimeUnit.SECONDS)) { "Test did not release the credential worker" }
        }
        fun release() = release.countDown()
    }

    private fun submitHolding(
        activity: AppCompatActivity, dialog: Dialog, secret: String, worker: HeldWorker,
        inputId: Int = R.id.swSheetInput
    ): Job {
        val scopeJob = checkNotNull(activity.lifecycleScope.coroutineContext[Job])
        val before = scopeJob.children.toSet()
        submit(dialog, secret, inputId)
        await("the worker entered the KDF") { worker.calls.isNotEmpty() }
        val primary = dialog.findViewById<View>(R.id.swSheetPrimary)
        assertFalse("submit is disabled while working", primary.isEnabled)
        assertTrue("working indicator is visible",
            (primary.parent as android.view.ViewGroup).let { root ->
                (0 until root.childCount).map(root::getChildAt)
                    .filterIsInstance<ProgressBar>().single().visibility == View.VISIBLE
            })
        assertTrue("credentials are on a secure window", SecureView.isSecure(dialog.window))
        val input = dialog.findViewById<EditText>(inputId)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.importantForAutofill)
        assertFalse("the field is not saved or editable during the request", input.isSaveEnabled || input.isEnabled)
        primary.performClick() // A stale click delivered directly to the handler must also be ignored.
        return (scopeJob.children.toSet() - before).single()
    }

    @Test(timeout = 60_000) fun `legacy Home verification derives once off main`() {
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

    @Test(timeout = 60_000) fun `legacy Home save derives once off main`() {
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

    @Test(timeout = 60_000) fun `Vault legacy verification derives once off main`() {
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

    @Test(timeout = 60_000) fun `Backup recovery verification derives once off main for a wrong valid code`() {
        val gate = VaultPasscodeGate()
        val store = VaultStore(app, repo, FakeVaultKeys(), gate)
        val code = VaultCrypto.normalizeRecoveryCode(store.setup("246810").recoveryCode)
        store.lock()
        gate.onWrong()
        BackupActivity.vaultOverride = store
        Robolectric.buildActivity(BackupActivity::class.java).setup().use { controller ->
            val recovery = recoverySheet(controller.get())
            val (calls, probe) = recorder()
            VaultCrypto.derivationProbe = probe
            val wrong = (if (code[0] == 'A') 'B' else 'A') + code.drop(1)
            assertTrue(VaultCrypto.isRecoveryCode(wrong))
            val elapsed = submit(recovery, wrong)
            await("wrong code is reported") {
                ShadowToast.getTextOfLatestToast()?.toString() == app.getString(R.string.backup_code_wrong)
            }
            assertTrue("wrong code allows a retry", recovery.isShowing)
            assertEquals("wrong recovery code neither resets nor spends a passcode attempt", 1, gate.failures)
            assertFalse(store.isUnlocked())
            assertEquals("Backup check click occupied main for ${elapsed}ms", listOf(false), calls.toList())
        }
    }

    @Test(timeout = 60_000) fun `an incorrect SHA-256 PIN is cheap and a correct one upgrades it once`() {
        val old = VaultPinHash.legacySha256("2580")
        prefs.edit().putString("vault_pin_sha256", old).apply()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var opened = false
            val flow = homeFlow(controller.get()) { opened = it }
            val (calls, probe) = recorder()
            VaultPinHash.derivationProbe = probe
            flow.openVaultWithPin()
            val wrong = sheet()
            submit(wrong, "1111")
            await("wrong PIN finishes") { !wrong.isShowing }
            assertTrue("wrong PIN must not invoke PBKDF2 against the old SHA-256 record", calls.isEmpty())
            assertEquals(old, pinRecord)
            assertFalse(opened)

            val samples = List(21) {
                val start = System.nanoTime()
                assertFalse(VaultPinHash.verify(old, "1111"))
                System.nanoTime() - start
            }
            println("Old PIN SHA-256-only verification: median ${samples.sorted()[10] / 1_000}us on JVM")

            flow.openVaultWithPin()
            submit(sheet(), "2580")
            await("correct PIN opens and upgrades") { opened }
            assertEquals(listOf(false), calls.toList())
            assertTrue("legacy PIN upgraded after the check", pinRecord!!.startsWith("pbkdf2$"))
        }
    }

    @Test(timeout = 60_000) fun `dismiss during PIN saving leaves the prior PIN unchanged`() {
        Prefs.setVaultPin(app, "2580")
        val old = pinRecord
        val worker = HeldWorker()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var saved = false
            val activity = controller.get()
            homeFlow(activity).promptSetVaultPin { saved = true }
            VaultPinHash.derivationProbe = worker::probe
            val dialog = sheet()
            val job = try {
                submitHolding(activity, dialog, "2468", worker).also { dialog.dismiss() }
            } finally {
                worker.release()
            }
            await("dismissed job finishes") { job.isCompleted }
            assertTrue(job.isCancelled)
            assertEquals(1, worker.calls.size)
            assertEquals(old, pinRecord)
            assertFalse(saved)
        }
    }

    @Test(timeout = 60_000) fun `destroy during a successful legacy upgrade leaves the old record intact`() {
        val old = VaultPinHash.legacySha256("2580")
        prefs.edit().putString("vault_pin_sha256", old).apply()
        VaultActivity.storeOverride = VaultStore(app, repo, FakeVaultKeys())
        val worker = HeldWorker()
        Robolectric.buildActivity(VaultActivity::class.java).setup().use { controller ->
            val dialog = sheet()
            VaultPinHash.derivationProbe = worker::probe
            val job = try {
                submitHolding(controller.get(), dialog, "2580", worker, R.id.vaultLegacyPin)
                    .also { controller.pause().stop().destroy() }
            } finally {
                worker.release()
            }
            await("destroyed job finishes") { job.isCompleted }
            assertTrue(job.isCancelled)
            assertEquals(1, worker.calls.size)
            assertEquals(old, pinRecord)
            assertFalse(dialog.isShowing)
        }
    }

    @Test(timeout = 60_000) fun `dismiss while checking the right recovery code never unlocks or creates a backup`() {
        val gate = VaultPasscodeGate()
        val store = VaultStore(app, repo, FakeVaultKeys(), gate, iterations = 2_000)
        val code = store.setup("246810").recoveryCode
        store.lock()
        gate.onWrong()
        BackupActivity.vaultOverride = store
        val worker = HeldWorker()
        Robolectric.buildActivity(BackupActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val dialog = recoverySheet(activity)
            VaultCrypto.derivationProbe = worker::probe
            val job = try {
                submitHolding(activity, dialog, code, worker).also { dialog.dismiss() }
            } finally {
                worker.release()
            }
            await("dismissed recovery job finishes") { job.isCompleted }
            assertTrue(job.isCancelled)
            assertEquals(1, worker.calls.size)
            assertFalse(store.isUnlocked())
            assertEquals(1, gate.failures)
            assertTrue(File(app.cacheDir, "fullbackup").listFiles().isNullOrEmpty())
        }
    }

    @Test(timeout = 60_000) fun `successful recovery check resets the gate and then derives the separate archive key`() {
        val gate = VaultPasscodeGate()
        val store = VaultStore(app, repo, FakeVaultKeys(), gate, iterations = 2_000)
        val code = store.setup("246810").recoveryCode
        store.markConfirmationPassed()
        store.lock()
        gate.onWrong()
        BackupActivity.vaultOverride = store
        Robolectric.buildActivity(BackupActivity::class.java).setup().use { controller ->
            val recovery = recoverySheet(controller.get())
            val (calls, probe) = recorder()
            VaultCrypto.derivationProbe = probe
            submit(recovery, code)
            val title = app.getString(R.string.backup_ready)
            await("backup is ready after the recovery check") {
                ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetTitle)?.text == title
            }
            assertEquals("one Vault check plus one archive encryption derivation", listOf(false, false), calls.toList())
            assertTrue(store.isUnlocked())
            assertEquals(0, gate.failures)
        }
    }
}
