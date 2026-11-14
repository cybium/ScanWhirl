package com.scanwhirl.app.ui.vault

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultKeyChallenge
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultSessionPolicy
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.Prefs
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

/**
 * v2 UI: 04_vault — the setup/one-time-code flow, passcode, recovery-code and phone-lock
 * unlock, adding a library document, and the SAF backup request. The encryption itself is
 * covered by the Phase A [VaultStore] tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultActivityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var keys: FakeVaultKeys
    private lateinit var store: VaultStore
    private var now = 0L
    private var controller: ActivityController<VaultActivity>? = null

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
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        Faults.resetAll()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): VaultActivity {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun sheet(): BottomSheetDialog {
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("a sheet should be showing", dialog)
        return dialog as BottomSheetDialog
    }

    private fun primary(sheet: BottomSheetDialog): View = sheet.need(R.id.swSheetPrimary)

    private fun await(condition: () -> Boolean): Boolean {
        repeat(150) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** findViewById with an exact type; the platform method is nullable on this SDK. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Dialog.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Activity.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Test
    fun `setup shows the recovery code once and finishes after typing four characters`() {
        val activity = launch()
        val setupSheet = sheet()
        assertEquals("Set up your Vault", setupSheet.need<TextView>(R.id.swSheetTitle).text)

        setupSheet.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setupSheet.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")
        primary(setupSheet).performClick()
        idle()

        val recoverySheet = sheet()
        assertEquals("Save this recovery code", recoverySheet.need<TextView>(R.id.swSheetTitle).text)
        val code = recoverySheet.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        assertEquals(24, VaultCrypto.normalizeRecoveryCode(code).length)
        assertTrue(store.isSetUp())

        primary(recoverySheet).performClick()
        idle()
        val confirmSheet = sheet()
        assertEquals("Type 4 characters", confirmSheet.need<TextView>(R.id.swSheetTitle).text)
        confirmSheet.need<EditText>(R.id.vaultRecoveryInput)
            .setText(VaultCrypto.normalizeRecoveryCode(code).take(4))
        primary(confirmSheet).performClick()
        idle()

        assertTrue(store.isUnlocked())
        assertEquals(
            "0 files protected",
            activity.need<TextView>(R.id.vaultStatsTitle).text.toString()
        )
        assertTrue(activity.need<View>(R.id.vaultBanner).isVisible)
        assertTrue("the setup sheet is gone", setupSheet.isShowing.not())
    }

    @Test
    fun `wrong passcode shows an error and the right one unlocks`() {
        store.setup("246810")
        val activity = launch()

        activity.need<View>(R.id.vaultLock).performClick()
        idle()
        val unlockSheet = sheet()
        assertEquals("Unlock Vault", unlockSheet.need<TextView>(R.id.swSheetTitle).text)

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("000000")
        primary(unlockSheet).performClick()
        idle()
        val error = unlockSheet.need<TextView>(R.id.vaultUnlockError)
        assertTrue(error.isVisible)
        assertEquals("Wrong passcode", error.text.toString())
        assertFalse(store.isUnlocked())

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        primary(unlockSheet).performClick()
        idle()
        assertTrue(store.isUnlocked())
        assertTrue(activity.need<View>(R.id.vaultBanner).isVisible)
    }

    @Test
    fun `the recovery code unlocks after a forgotten passcode`() {
        val setup = store.setup("246810")
        store.lock()
        val activity = launch()

        val unlockSheet = sheet()
        unlockSheet.need<View>(R.id.vaultUseRecovery).performClick()
        idle()
        val recoverySheet = sheet()
        assertEquals("Use recovery code", recoverySheet.need<TextView>(R.id.swSheetTitle).text)
        recoverySheet.need<EditText>(R.id.vaultRestoreCode).setText(setup.recoveryCode)
        primary(recoverySheet).performClick()
        idle()

        assertTrue(store.isUnlocked())
        assertEquals(
            "Unlocked with recovery code · relocks in 2 min",
            activity.need<TextView>(R.id.vaultBanner).text.toString()
        )
    }

    @Test
    fun `fingerprint unlock offers the prompt and opens the vault through the keystore key`() {
        store.setup("246810")
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        val challenges = ArrayList<VaultKeyChallenge>()
        VaultActivity.fingerprintStarter = { challenge ->
            challenges += challenge
            true // the prompt authorized the cipher
        }
        val activity = launch()

        val unlockSheet = sheet()
        val useFingerprint = unlockSheet.need<View>(R.id.swSheetSecondary)
        assertTrue(useFingerprint.isVisible)
        assertEquals("Use fingerprint", (useFingerprint as TextView).text.toString())
        useFingerprint.performClick()
        idle()

        assertEquals(1, challenges.size)
        assertNotNull("the CryptoObject cipher is offered to the prompt", challenges[0].cipher)
        assertTrue(store.isUnlocked())
        assertEquals(
            "Unlocked with fingerprint · relocks in 2 min",
            activity.need<TextView>(R.id.vaultBanner).text.toString()
        )
    }

    @Test
    fun `a failed fingerprint falls back to the passcode sheet and stays locked`() {
        store.setup("246810")
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        VaultActivity.fingerprintStarter = { false }
        val activity = launch()

        sheet().need<View>(R.id.swSheetSecondary).performClick()
        idle()

        assertFalse(store.isUnlocked())
        assertFalse(activity.need<View>(R.id.vaultBanner).isVisible)
        // The failure is explained, then the passcode sheet comes back: never a dead end.
        val error = sheet()
        assertEquals(
            "Fingerprint did not unlock the Vault. Use your passcode.",
            error.need<TextView>(R.id.swSheetMessage).text.toString()
        )
        primary(error).performClick()
        idle()
        assertEquals("Unlock Vault", sheet().need<TextView>(R.id.swSheetTitle).text)
    }

    @Test
    fun `no fingerprint option when biometrics are not enrolled`() {
        store.setup("246810")
        store.lock()
        for (status in listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        )) {
            VaultActivity.biometricStatusOverride = status
            launch()
            val unlockSheet = sheet()
            assertFalse(
                "status $status must not offer a fingerprint",
                unlockSheet.findViewById<View>(R.id.swSheetSecondary)?.isVisible == true
            )
            assertEquals("Unlock Vault", unlockSheet.need<TextView>(R.id.swSheetTitle).text)
            controller!!.close()
            controller = null
        }
    }

    @Test
    fun `an invalidated keystore key asks for the passcode and re-wraps afterwards`() {
        store.setup("246810")
        store.lock()
        keys.invalidate()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var started = false
        VaultActivity.fingerprintStarter = { started = true; false }
        val activity = launch()

        // No usable Keystore key -> the prompt is not even offered.
        assertFalse(activity.need<View>(R.id.vaultBanner).isVisible)
        val unlockSheet = sheet()
        assertTrue("the fingerprint option disappears", unlockSheet.findViewById<View>(R.id.swSheetSecondary)?.isVisible != true)

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        primary(unlockSheet).performClick()
        idle()
        assertTrue(store.isUnlocked())
        assertFalse("no prompt was started", started)
        assertTrue("the Keystore wrap was rebuilt", store.canUseBiometric())
    }

    @Test
    fun `add files encrypts a library document and shows it in the private list`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Aadhaar card")
        store.setup("246810")
        val activity = launch()

        activity.need<View>(R.id.vaultAddFiles).performClick()
        idle()
        val addSheet = sheet()
        assertEquals("Add to Vault", addSheet.need<TextView>(R.id.swSheetTitle).text)
        addSheet.need<View>(R.id.vaultAddRow).performClick()
        idle()

        val done = await {
            repo.getDocument(id) == null &&
                activity.need<android.view.ViewGroup>(R.id.vaultList).childCount == 1
        }
        assertTrue("the document was added to the Vault", done)
        val list = activity.need<android.view.ViewGroup>(R.id.vaultList)
        assertEquals("Aadhaar card", list.need<TextView>(R.id.vaultEntryName).text.toString())
        val stats = activity.need<TextView>(R.id.vaultStatsTitle).text.toString()
        assertTrue("files are counted: $stats", stats.endsWith("files protected") && !stats.startsWith("0 "))
    }

    @Test
    fun `backup asks for a swvault file with the system picker`() {
        store.setup("246810")
        val activity = launch()
        activity.need<View>(R.id.vaultBackupButton).performClick()
        idle()

        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the backup picker should start", started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertEquals("application/octet-stream", started.intent.type)
        assertTrue(
            started.intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty().endsWith(".swvault")
        )
    }

    @Test
    fun `the vault relocks itself after two minutes in the background`() {
        store.setup("246810")
        val activity = launch()
        assertTrue(activity.need<View>(R.id.vaultBanner).isVisible)

        controller!!.pause().stop()
        // The screen recorded "background" on stop; pretend it was more than the grace ago.
        VaultSession.onBackground(System.currentTimeMillis() - VaultSessionPolicy.GRACE_MILLIS - 1_000)
        controller!!.start().resume()
        idle()

        assertFalse("the key is gone", store.isUnlocked())
        assertFalse("no unlocked banner", activity.need<View>(R.id.vaultBanner).isVisible)
        assertEquals("Unlock Vault", sheet().need<TextView>(R.id.swSheetTitle).text)
    }

    @Test
    fun `the old four-digit PIN is asked once before the new Vault setup`() {
        Prefs.setVaultPin(app, "1234")
        val activity = launch()

        val legacySheet = sheet()
        assertEquals("Old PIN Vault", legacySheet.need<TextView>(R.id.swSheetTitle).text)
        legacySheet.need<EditText>(R.id.vaultLegacyPin).setText("1234")
        primary(legacySheet).performClick()
        idle()

        val setupSheet = sheet()
        assertEquals("Set up your Vault", setupSheet.need<TextView>(R.id.swSheetTitle).text)
        assertFalse(store.isSetUp())
        assertNotNull(activity.need<View>(R.id.vaultSetupButton))
    }
}
