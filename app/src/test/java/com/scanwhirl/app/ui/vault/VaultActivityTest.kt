package com.scanwhirl.app.ui.vault

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
            "0 documents protected",
            activity.need<TextView>(R.id.vaultStatsTitle).text.toString()
        )
        assertTrue(activity.need<View>(R.id.vaultBanner).isVisible)
        assertTrue("the setup sheet is gone", setupSheet.isShowing.not())
    }

    @Test
    fun `wrong passcode shows the tries left and keeps the sheet open`() {
        store.setup("246810")
        val activity = launch()

        activity.need<View>(R.id.vaultLock).performClick()
        idle()
        val unlockSheet = sheet()
        assertEquals("Unlock Vault", unlockSheet.need<TextView>(R.id.swSheetTitle).text)
        assertEquals("Enter your passcode.", unlockSheet.need<TextView>(R.id.swSheetMessage).text.toString())

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("000000")
        primary(unlockSheet).performClick()
        idle()
        val error = unlockSheet.need<TextView>(R.id.vaultUnlockError)
        assertTrue(error.isVisible)
        assertEquals("Wrong passcode. 4 tries left.", error.text.toString())
        assertTrue("the sheet stays open", unlockSheet.isShowing)
        assertFalse(store.isUnlocked())
        assertTrue("the screen also says it is still locked", activity.need<View>(R.id.vaultStatus).isVisible)

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        primary(unlockSheet).performClick()
        idle()
        assertTrue(store.isUnlocked())
        assertTrue(activity.need<View>(R.id.vaultBanner).isVisible)
        assertFalse(activity.need<View>(R.id.vaultStatus).isVisible)
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
    fun `fingerprint unlock starts the prompt once and opens the vault`() {
        store.setup("246810")
        store.completeKeystoreWrap(store.beginKeystoreWrap())
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        val challenges = ArrayList<VaultKeyChallenge>()
        VaultActivity.fingerprintStarter = { challenge ->
            challenges += challenge
            true // the prompt authorized the cipher
        }
        val activity = launch()

        assertEquals("the prompt starts once when the sheet opens", 1, challenges.size)
        assertNotNull("the CryptoObject cipher is offered to the prompt", challenges[0].cipher)
        assertTrue(store.isUnlocked())
        assertEquals(
            "Unlocked with fingerprint · relocks in 2 min",
            activity.need<TextView>(R.id.vaultBanner).text.toString()
        )
    }

    @Test
    fun `the fingerprint button is offered with its icon and retries after a cancel`() {
        store.setup("246810")
        store.completeKeystoreWrap(store.beginKeystoreWrap())
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var answered = false
        VaultActivity.fingerprintStarter = { responded ->
            if (!answered) {
                answered = true
                false // the automatic prompt was cancelled: no error sheet, just the passcode
            } else {
                true
            }
        }
        val activity = launch()

        val unlockSheet = sheet()
        assertEquals(
            "Use your fingerprint, or enter your passcode.",
            unlockSheet.need<TextView>(R.id.swSheetMessage).text.toString()
        )
        val useFingerprint = unlockSheet.need<View>(R.id.swSheetSecondary)
        assertTrue("the fingerprint control is there", useFingerprint.isVisible)
        assertEquals("Use fingerprint", (useFingerprint as TextView).text.toString())
        assertFalse("a cancelled automatic prompt saves nothing", store.isUnlocked())

        useFingerprint.performClick()
        idle()
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `a failed manual fingerprint falls back to the passcode sheet and stays locked`() {
        store.setup("246810")
        store.completeKeystoreWrap(store.beginKeystoreWrap())
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var attempts = 0
        VaultActivity.fingerprintStarter = {
            attempts++
            false
        }
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
        store.completeKeystoreWrap(store.beginKeystoreWrap())
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
            assertEquals(
                "the copy must not promise a fingerprint",
                "Enter your passcode.",
                unlockSheet.need<TextView>(R.id.swSheetMessage).text.toString()
            )
            controller!!.close()
            controller = null
        }
    }

    @Test
    fun `an invalidated keystore key asks for the passcode then offers a new fingerprint`() {
        store.setup("246810")
        store.completeKeystoreWrap(store.beginKeystoreWrap())
        store.lock()
        keys.invalidate()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var started = false
        VaultActivity.fingerprintStarter = { started = true; true }
        val activity = launch()

        // No usable Keystore key -> the prompt is not offered, only the passcode.
        assertFalse(activity.need<View>(R.id.vaultBanner).isVisible)
        val unlockSheet = sheet()
        assertTrue(
            "the fingerprint option disappears",
            unlockSheet.findViewById<View>(R.id.swSheetSecondary)?.isVisible != true
        )
        assertFalse("no prompt was started before the passcode", started)

        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        primary(unlockSheet).performClick()
        idle()
        assertTrue(store.isUnlocked())
        assertTrue("the passcode unlock offered a new fingerprint wrap", started)
        assertTrue("the Keystore wrap was rebuilt through the prompt", store.canUseBiometric())
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
        assertEquals("documents, not files, are counted", "1 document protected", stats)
        val meta = list.need<TextView>(R.id.vaultEntryMeta).text.toString()
        assertTrue("pages are pluralised: $meta", meta.startsWith("2 pages"))
    }

    @Test
    fun `one page reads as one page, not one pages`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Receipt")
        store.setup("246810")
        val activity = launch()

        activity.need<View>(R.id.vaultAddFiles).performClick()
        idle()
        sheet().need<View>(R.id.vaultAddRow).performClick()
        idle()
        await { repo.getDocument(id) == null && activity.need<ViewGroup>(R.id.vaultList).childCount == 1 }

        val meta = activity.need<android.view.ViewGroup>(R.id.vaultList)
            .need<TextView>(R.id.vaultEntryMeta).text.toString()
        assertTrue("one page, not one pages: $meta", meta.startsWith("1 page "))
    }

    @Test
    fun `the locked screen says what is behind Private files`() {
        store.setup("246810")
        store.lock()
        val activity = launch()
        assertTrue(
            "a locked Vault shows a placeholder, not empty space",
            activity.need<View>(R.id.vaultLockedPlaceholder).isVisible
        )

        val unlockSheet = sheet()
        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        primary(unlockSheet).performClick()
        idle()
        assertTrue(store.isUnlocked())
        assertFalse(activity.need<View>(R.id.vaultLockedPlaceholder).isVisible)
    }

    @Test
    fun `another screen can say why it sent the user to unlock`() {
        store.setup("246810")
        store.lock()
        controller = Robolectric.buildActivity(
            VaultActivity::class.java,
            VaultActivity.intent(app, "Unlock the Vault to save this password.")
        ).setup()
        idle()

        assertEquals(
            "Unlock the Vault to save this password.",
            sheet().need<TextView>(R.id.swSheetMessage).text.toString()
        )
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
    fun `the vault window blocks screenshots and blanks the recents card`() {
        store.setup("246810")
        val activity = launch()
        assertTrue(
            "FLAG_SECURE must be set on the Vault window",
            com.scanwhirl.app.util.SecureView.isSecure(activity.window)
        )
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
