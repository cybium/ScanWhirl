package com.scanwhirl.app
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * VAULT-F1 (Sol round 2, review F2): the "Save as file" picker for the one-time recovery code
 * writes a secret. A result that arrives after the Vault relocked (session expiry, "Lock now"),
 * or that belongs to a flow which was replaced or to another Vault (a restore changed the
 * identity), must be ignored: the code is not written, the picker's empty document is removed,
 * and no sheet is raised over a locked screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultPickerStaleResultTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null

    private val recoveryFile = "content://com.scanwhirl.app.test/recovery.txt"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        store = VaultStore(
            context = app,
            repo = ScanRepository(app),
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(),
            iterations = 2_000
        )
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride =
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
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
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            idle()
            if (condition()) return true
            if (System.nanoTime() >= deadline) {
                idle()
                return condition()
            }
            Thread.sleep(10)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> BottomSheetDialog.need(id: Int): T = findViewById<View>(id) as T

    /** Drives the real setup sheet until the one-time recovery code is on screen. */
    private fun reachRecoveryCode(): BottomSheetDialog {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        val setupSheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        setupSheet.need<EditText>(R.id.vaultPasscodeInput).setText("246810")
        setupSheet.need<EditText>(R.id.vaultPasscodeConfirm).setText("246810")
        setupSheet.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("setup finishes on a background thread", await { !setupSheet.isShowing })
        idle()
        val recovery = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertEquals(
            "Save this recovery code",
            recovery.need<TextView>(R.id.swSheetTitle).text.toString()
        )
        return recovery
    }

    /** Taps "Save as file" and returns the picker's request code with its intent. */
    private fun tapSave(recovery: BottomSheetDialog): Pair<Int, Intent> {
        recovery.need<TextView>(R.id.vaultRecoveryCopy).performClick()
        idle()
        val started = shadowOf(controller!!.get()).peekNextStartedActivityForResult()
        assertNotNull("the system picker starts", started)
        return started!!.requestCode to started.intent
    }

    private fun provider(): FixCopyTest.RecordingDocumentsProvider =
        Robolectric.setupContentProvider(
            FixCopyTest.RecordingDocumentsProvider::class.java,
            "com.scanwhirl.app.test"
        ) as FixCopyTest.RecordingDocumentsProvider

    private fun latestDialog(): android.app.Dialog? = ShadowDialog.getLatestDialog()

    @Test
    fun `a picker result after the Vault relocks writes no code and raises no sheet`() {
        val recovery = reachRecoveryCode()
        val request = tapSave(recovery)
        val activity = controller!!.get()

        // The session expires while the system picker is in front (VaultSession drops the key).
        VaultSession.close()
        assertTrue("the Vault is locked again", !store.isUnlocked())

        val documents = provider()
        val written = ByteArrayOutputStream()
        val uri = Uri.parse(recoveryFile)
        shadowOf(app.contentResolver).registerOutputStream(uri, written)
        shadowOf(activity).callOnActivityResult(request.first, Activity.RESULT_OK, Intent().setData(uri))

        val cleaned = await { documents.calls.any { it.first == "android:deleteDocument" } }
        assertTrue("the empty document the picker created is removed: ${documents.calls}", cleaned)
        assertEquals("no recovery code is written after a relock", 0, written.size())
        assertTrue(
            "no sheet is left on the locked screen: ${latestDialog()?.isShowing}",
            latestDialog()?.isShowing != true
        )
        assertTrue("the Vault stays locked", !store.isUnlocked())
    }

    @Test
    fun `a picker result for a replaced Vault writes no code`() {
        val recovery = reachRecoveryCode()
        val oldIdentity = store.vaultIdentity()
        val request = tapSave(recovery)
        val activity = controller!!.get()

        // The Vault is restored while the picker is open: another key, another recovery code.
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        store.setup("135790")
        assertTrue("the new setup is a different Vault", store.vaultIdentity() != oldIdentity)
        assertTrue("and it is open", store.isUnlocked())

        val documents = provider()
        val written = ByteArrayOutputStream()
        val uri = Uri.parse(recoveryFile)
        shadowOf(app.contentResolver).registerOutputStream(uri, written)
        shadowOf(activity).callOnActivityResult(request.first, Activity.RESULT_OK, Intent().setData(uri))

        assertTrue(
            "the stale document is removed: ${documents.calls}",
            await { documents.calls.any { it.first == "android:deleteDocument" } }
        )
        assertEquals(
            "a code that belongs to the Vault this phone no longer has is never written",
            0,
            written.size()
        )
        assertTrue(
            "nothing is left on screen for a Vault that no longer exists: ${latestDialog()?.isShowing}",
            latestDialog()?.isShowing != true
        )
    }
    @Test
    fun recoverySaveRequiredBeforeConfirmation() {
        val recovery = reachRecoveryCode()
        val primary = recovery.need<View>(R.id.swSheetPrimary)
        assertTrue("cannot confirm without saving", !primary.isEnabled)
        primary.performClick()
        assertTrue("defensive handler does not advance", recovery.isShowing)
        val request = tapSave(recovery)
        shadowOf(controller!!.get()).callOnActivityResult(request.first, Activity.RESULT_CANCELED, null)
        idle()
        assertTrue("cancel does not enable confirmation", !primary.isEnabled)
        saveRecoveryFileForTest(controller!!.get(), recovery)
        controller!!.recreate()
        idle()
        val restored = ShadowDialog.getLatestDialog() as BottomSheetDialog
        assertTrue("successful proof survives rotation", restored.need<View>(R.id.swSheetPrimary).isEnabled)
        restored.need<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertEquals("Type 4 characters", (ShadowDialog.getLatestDialog() as BottomSheetDialog).need<TextView>(R.id.swSheetTitle).text.toString())
        assertTrue("the existing confirmation step is still owed", store.isUnlocked())
    }

}
