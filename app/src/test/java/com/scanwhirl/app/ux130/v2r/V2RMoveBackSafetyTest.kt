package com.scanwhirl.app.ux130.v2r
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.backup.BackupActivity
import com.scanwhirl.app.ui.vault.VaultActivity
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
import java.lang.reflect.Method

/**
 * V2R security rework: move-back must verify the published library document before it deletes the
 * Vault entry, and every failure must be reported exactly as it happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V2RMoveBackSafetyTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null
    private val name = "Fictional insurance"
    private val password = "correct horse battery"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        clean()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        store = VaultStore(app, ScanWhirlApp.from(app).repository, FakeVaultKeys(),
            VaultPasscodeGate(clock = { 0L }), 2_000)
        store.setup("246810")
        store.markConfirmationPassed()
        store.unlockWithPasscode("246810")
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride =
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        val doc = DocFixtures.saved(ScanWhirlApp.from(app).repository, app, pages = 2, name = name)
        store.addDocument(doc)
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failCreateForTest", null)
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failRemoveForTest", null)
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failVerifyForTest", false)
        BackupActivity.workGateForTest = null
        BackupActivity.vaultOverride = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        clean()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun clean() {
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, "documents").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        File(app.cacheDir, "fullbackup").deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun setStatic(className: String, fieldName: String, value: Any?) {
        val f = try {
            Class.forName(className).getField(fieldName)
        } catch (_: NoSuchFieldException) {
            throw AssertionError("the failure seam $fieldName is needed (RED on base)", NoSuchFieldException(fieldName))
        }
        f.set(null, value)
    }

    private fun findText(root: View?, text: String): View? {
        if (root == null) return null
        if (root is TextView && root.text?.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findText(root.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition never became true", condition())
    }

    private fun openChoices(): Dialog {
        idle()
        controller!!.get().findViewById<View>(R.id.vaultEntryMore)!!.performClick()
        idle()
        val actions = ShadowDialog.getShownDialogs().last { it.isShowing }
        findText(actions.findViewById(R.id.swSheetRoot), app.getString(R.string.vault_remove))!!.performClick()
        idle()
        return ShadowDialog.getShownDialogs().last { it.isShowing }
    }

    private fun moveBack() {
        val choices = openChoices()
        findText(choices.findViewById(R.id.swSheetRoot), app.getString(R.string.v2r_vault_move_back))!!
            .performClick()
        idle()
    }

    private fun receiptBody(): String {
        await {
            ShadowDialog.getShownDialogs().any { d ->
                d.isShowing && d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.v2r_vault_move_back)
            }
        }
        return ShadowDialog.getShownDialogs().last { it.isShowing }
            .findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
    }

    private fun launch() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
    }

    @Test(timeout = 30_000)
    fun `a failed create says the Vault copy is unchanged`() {
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failCreateForTest",
            IllegalStateException("fictional create failure"))
        launch()
        moveBack()
        assertTrue("honest failure: ${receiptBody()}", receiptBody().contains("unchanged"))
        assertTrue("Vault entry kept", store.listEntries().any { it.name == name })
        assertFalse("no half library doc", ScanWhirlApp.from(app).repository.listDocuments().any { it.name == name })
    }

    @Test(timeout = 30_000)
    fun `a verification failure deletes the created library copy and keeps the Vault`() {
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failVerifyForTest", true)
        launch()
        moveBack()
        val body = receiptBody()
        assertTrue("verification failure is named: $body", body.contains("verify"))
        assertTrue("Vault entry kept", store.listEntries().any { it.name == name })
        assertFalse("created copy removed", ScanWhirlApp.from(app).repository.listDocuments().any { it.name == name })
    }

    @Test(timeout = 30_000)
    fun `a failed Vault removal keeps both and never claims the Vault is unchanged`() {
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failRemoveForTest",
            IllegalStateException("fictional remove failure"))
        launch()
        moveBack()
        val body = receiptBody()
        assertTrue("remove failure named: $body", body.contains("could not be removed"))
        assertFalse("never claims the Vault is unchanged", body.contains("unchanged"))
        assertTrue("library copy kept", ScanWhirlApp.from(app).repository.listDocuments().any { it.name == name })
        assertTrue("Vault entry kept", store.listEntries().any { it.name == name })
    }

    @Test(timeout = 30_000)
    fun `out of memory is an honest failure with the Vault intact`() {
        setStatic("com.scanwhirl.app.ui.vault.VaultActivity", "failCreateForTest",
            OutOfMemoryError("fictional OOM"))
        launch()
        moveBack()
        assertTrue("OOM is reported: ${receiptBody()}", receiptBody().contains("unchanged"))
        assertTrue("Vault entry kept", store.listEntries().any { it.name == name })
    }

    @Test(timeout = 30_000)
    fun `cancelling the Vault unlock prompt leaves no false restore receipt`() {
        // Build a backup that carries this Vault's key while it is still unlocked, then lock it.
        val archive = File(app.cacheDir, "fullbackup/safety-source.swbackup")
        archive.parentFile?.mkdirs()
        FullBackup(app, store).write(archive, password, FullBackup.Wrap.PASSWORD)
        assertTrue("the fixture needs Vault unlock", FullBackup(app, store).header(archive).hasVaultKey)

        store.lock()
        BackupActivity.vaultOverride = store
        val activity = Robolectric.buildActivity(BackupActivity::class.java).setup().get()
        val method: Method = BackupActivity::class.java.getDeclaredMethod(
            "runRestore", File::class.java, String::class.java, Boolean::class.javaPrimitiveType
        ).also { it.isAccessible = true }
        method.invoke(activity, archive, password, true)
        idle()
        val prefs = app.getSharedPreferences("scanwhirl_prefs", 0)
        assertFalse(
            "a cancelled unlock prompt must not leave a fake interruption marker",
            prefs.contains("ux_v2r_interrupted_operation")
        )
    }

    @Test
    fun `move-back keeps store page order and cleans the decrypted folder`() {
        val source = File("src/main/java/com/scanwhirl/app/ui/vault/VaultActivity.kt").readText()
        val body = source.substringAfter("private fun moveBackToLibrary").substringBefore("// ------------------------------------------------------------------")
        assertFalse("no arbitrary page-name sorting", body.contains("sortedBy { it.name }"))
        assertTrue("pages are streamed through the save path", body.contains("saveScan("))
        assertTrue("the decrypted folder is deleted in finally", body.contains("opened?.dir?.deleteRecursively()"))
    }
}
