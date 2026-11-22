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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** V2R phone finding (a): removal must never be a one-tap permanent loss of the document. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V2RVaultMoveBackTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null
    private val name = "Fictional insurance"

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        clean()
        store = VaultStore(
            app, ScanWhirlApp.from(app).repository, FakeVaultKeys(),
            VaultPasscodeGate(clock = { 0L }), 2_000
        )
        store.setup("246810")
        store.markConfirmationPassed()
        store.unlockWithPasscode("246810")
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride =
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        val doc = DocFixtures.saved(ScanWhirlApp.from(app).repository, app, pages = 1, name = name)
        store.addDocument(doc)
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        clean()
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
    }

    private fun clean() {
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, "documents").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun sheetTitle(): String? =
        ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
            ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()

    private fun openChoices(): Dialog {
        idle()
        val entry = controller!!.get().findViewById<View>(R.id.vaultEntryMore)
        assertNotNull("the Vault entry is on screen", entry)
        entry!!.performClick()
        idle()
        val actions = ShadowDialog.getShownDialogs().last { it.isShowing }
        // The actions sheet content holds the "Remove from Vault" control; find it by its label.
        val removeRow = findText(actions.findViewById(android.R.id.content) ?: actions.window?.decorView,
            app.getString(R.string.vault_remove))
        assertNotNull("Remove from Vault is offered", removeRow)
        removeRow!!.performClick()
        idle()
        return ShadowDialog.getShownDialogs().last { it.isShowing }
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
        assertTrue("condition did not become true", condition())
    }

    @Test(timeout = 30_000)
    fun `safe move back is primary and permanent delete is a separate named confirm`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        val choices = openChoices()
        assertEquals(
            "Move back to Library",
            choices.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString()
        )
        val delete = choices.findViewById<View>(R.id.v2rVaultDeleteRow)
        assertNotNull("permanent delete is a separate row", delete)
        assertEquals("Delete permanently", (delete as TextView).text.toString())

        delete.performClick()
        idle()
        val confirm = ShadowDialog.getShownDialogs().last { it.isShowing }
        val confirmTitle = confirm.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty()
        val confirmBody = confirm.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
        assertTrue("the confirm names the document: $confirmTitle", confirmTitle.contains(name))
        assertTrue("the consequence names the document: $confirmBody", confirmBody.contains(name))
        assertEquals("Delete permanently", confirm.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString())
    }

    @Test(timeout = 30_000)
    fun `move back publishes a library document then removes the Vault entry`() {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        val choices = openChoices()
        findText(choices.findViewById(R.id.swSheetRoot), app.getString(R.string.v2r_vault_move_back))!!
            .performClick()
        idle()
        await {
            ShadowDialog.getShownDialogs().any { d ->
                d.isShowing && d.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.v2r_vault_move_back)
            }
        }
        val repo = ScanWhirlApp.from(app).repository
        val library = repo.listDocuments().firstOrNull { it.name == name }
        assertNotNull("the document is back in the Library", library)
        assertTrue("the Vault entry is gone", store.listEntries().none { it.name == name })
        val receipt = ShadowDialog.getShownDialogs().last { it.isShowing }
            .findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
        assertTrue("honest tier note: $receipt", receipt.contains("not carried over"))
    }

    @Test(timeout = 30_000)
    fun `Hindi removal offers the glossary-consistent safe action`() {
        RuntimeEnvironment.setQualifiers("hi-rIN-w320dp-h640dp-xhdpi")
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        val choices = openChoices()
        assertEquals(
            "कागज़ात में वापस रखें",
            choices.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString()
        )
        assertEquals(
            "हमेशा के लिए हटाएँ",
            (choices.findViewById<View>(R.id.v2rVaultDeleteRow) as TextView).text.toString()
        )
    }
}
