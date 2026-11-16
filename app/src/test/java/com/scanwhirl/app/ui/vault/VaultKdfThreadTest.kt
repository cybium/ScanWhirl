package com.scanwhirl.app.ui.vault

import android.app.Application
import android.os.Looper
import android.widget.EditText
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
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
import java.io.File

/**
 * C02: the Vault's 310 000-iteration PBKDF2 runs on a background thread from setup, unlock,
 * recovery unlock and change-passcode, with the button disabled while it works.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultKdfThreadTest {

    private lateinit var app: Application
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null
    private var onMain = 0
    private var offMain = 0

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        val repo = ScanRepository(app)
        store = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(clock = { 0L }),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        onMain = 0
        offMain = 0
        VaultCrypto.derivationProbe = {
            if (Looper.myLooper() == Looper.getMainLooper()) onMain++ else offMain++
        }
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultCrypto.derivationProbe = null
        VaultSession.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        repeat(400) {
            idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        idle()
        return condition()
    }

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

    private fun <T : android.view.View> android.app.Dialog.need(id: Int): T =
        requireNotNull(findViewById(id)) { "view $id is missing" }

    @Test
    fun `setup never derives the key on the main thread`() {
        launch()
        val setupSheet = sheet()
        setupSheet.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setupSheet.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")
        setupSheet.need<android.view.View>(R.id.swSheetPrimary).performClick()

        assertTrue("the Vault is set up", await { store.isSetUp() })
        assertEquals("no PBKDF2 on the main thread", 0, onMain)
        assertTrue(
            "the passcode and recovery wraps are both derived off main (was $offMain)",
            offMain >= 2
        )
    }

    @Test
    fun `unlock never derives the key on the main thread`() {
        store.setup("246810")
        store.lock()
        onMain = 0
        offMain = 0
        launch()
        val unlockSheet = sheet()
        unlockSheet.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        unlockSheet.need<android.view.View>(R.id.swSheetPrimary).performClick()

        assertTrue("the Vault unlocks", await { store.isUnlocked() })
        assertEquals("no PBKDF2 on the main thread", 0, onMain)
        assertTrue("the passcode key is derived off main", offMain >= 1)
    }
}
