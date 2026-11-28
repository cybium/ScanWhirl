package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * Live More-sheet entry points, in forced-Free mode.
 *
 * The first WP-22 pass freed the inner methods (`VaultFlow.openVaultWithPin`,
 * `toggleVaultDoc`, `unlockCurrentPdf`) but left the OUTER click guards (the
 * Tools `tile_vault` Pro guard and the More-sheet `locked()` wrapper) in
 * place, so Free users never reached them. These tests click the actual
 * tiles / rows / buttons so that can't regress silently.
 *
 * Synthetic fixtures only — never real user documents.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
/**
 * W8 port: the five live DocumentViewer "More" sheet entry-point assertions from
 * FreeLegacyVaultEntryPointTest. These already drove a live screen; only the dead
 * Home/Tools-tile cases were retired with MainActivity.
 */
class LiveMoreSheetFreeTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // forced Free
        ShadowToast.reset()
    }

    // ---- fixtures / helpers ------------------------------------------

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String): String = runBlocking {
        val uri = Uri.parse("content://test/vault_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, jpegBytes().inputStream())
        repo.saveScan(listOf(uri), null, name).id
    }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun latestToast(): String? = ShadowToast.getTextOfLatestToast()?.toString()

    /** True when the latest dialog is the Pro-suite dossier (the Pro panel). */
    private fun proPanelShown(): Boolean =
        ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.proDossierStatus) != null
    // ---- DocumentViewer More sheet → rowMoreVault / rowMoreLock -------

    private fun withMoreSheet(id: String, block: (DocumentViewerActivity, android.app.Dialog) -> Unit) {
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue("document loads", pollUntil {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<android.widget.TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                ready
            })
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnMore).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("More sheet opens", sheet)
                block(activity, sheet!!)
            }
        }
    }
    @Test(timeout = 60_000)
    fun `free user can remove an existing vaulted doc and sees no Pro badge`() {
        val id = seedDoc("Vaulted doc")
        repo.setVaulted(id, true)
        withMoreSheet(id) { activity, sheet ->
            assertFalse("really Free", ProGate.isPro(activity))
            assertFalse("no misleading Pro badge on vault row",
                sheet.findViewById<View>(R.id.pillVault).isVisible)
            assertEquals(activity.getString(R.string.btn_vault_unhide),
                sheet.findViewById<android.widget.TextView>(R.id.labelVault).text.toString())
            sheet.findViewById<View>(R.id.rowMoreVault).performClick()
            assertFalse("no Pro-panel redirect on remove", proPanelShown())
            assertEquals(activity.getString(R.string.vault_moved_out), latestToast())
        }
        val after = repo.getDocument(id)
        assertNotNull("document and its files intact", after)
        assertFalse("doc restored out of the vault", after!!.isVaulted)
        assertTrue("PDF intact", after.pdfFile.length() > 0)
    }

    @Test(timeout = 60_000)
    fun `free user cannot add a doc to the vault and is not sent to the Pro panel`() {
        val id = seedDoc("Plain doc")
        withMoreSheet(id) { _, sheet ->
            assertFalse(sheet.findViewById<View>(R.id.pillVault).isVisible)
            sheet.findViewById<View>(R.id.rowMoreVault).performClick()
            assertFalse("no Pro-panel redirect", proPanelShown())
            assertEquals(app.getString(R.string.vault_new_unavailable), latestToast())
        }
        assertFalse("doc was not vaulted", repo.getDocument(id)!!.isVaulted)
    }

    @Test(timeout = 60_000)
    fun `pro user cannot add a doc to the vault either`() {
        ProGate.setTestOverride(app, true)
        val id = seedDoc("Plain doc")
        withMoreSheet(id) { _, sheet ->
            sheet.findViewById<View>(R.id.rowMoreVault).performClick()
            assertEquals(app.getString(R.string.vault_new_unavailable), latestToast())
            assertFalse(proPanelShown())
        }
        assertFalse(repo.getDocument(id)!!.isVaulted)
    }

    @Test(timeout = 60_000)
    fun `free user can unlock an existing locked PDF from More and sees no Pro badge`() {
        val id = seedDoc("Locked doc")
        runBlocking { repo.protectPdf(id, "1234") }
        assertTrue(repo.getDocument(id)!!.isLocked)
        withMoreSheet(id) { activity, sheet ->
            assertFalse("really Free", ProGate.isPro(activity))
            assertEquals(activity.getString(R.string.btn_unlock_pdf),
                sheet.findViewById<android.widget.TextView>(R.id.labelLock).text.toString())
            assertFalse("Unlock is Free — no Pro badge",
                sheet.findViewById<View>(R.id.pillLock).isVisible)
            sheet.findViewById<View>(R.id.rowMoreLock).performClick()
            assertFalse("no Pro-panel redirect for Unlock", proPanelShown())
            // PM-3.1b: DocumentViewerActivity.unlockCurrentPdf asks for the current password
            // (LockFlow.promptCurrentPassword) before repo.unlockPdf(id, pw). It used to unlock at once (F9, F21).
            // v2 UI: 09_unlock_sheet — the current password is asked in the kit sheet, not an AlertDialog.
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val passwordSheet = ShadowDialog.getLatestDialog()
            val passwordField = passwordSheet?.findViewById<EditText>(R.id.unlockPassword)
            assertNotNull("Unlock asks for the current password", passwordField)
            passwordField!!.setText("1234")
            passwordSheet.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("unlock completes", pollUntil { !repo.getDocument(id)!!.isLocked })
        }
        assertTrue("PDF still present after unlock", repo.getDocument(id)!!.pdfFile.length() > 0)
    }

    @Test(timeout = 60_000)
    fun `free user can protect an unlocked doc from More and sees no Pro badge`() {
        val id = seedDoc("Open doc")
        withMoreSheet(id) { activity, sheet ->
            assertFalse(
                "Protect PDF is a free tool — no Pro badge",
                sheet.findViewById<View>(R.id.pillLock).isVisible
            )
            sheet.findViewById<View>(R.id.rowMoreLock).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse("no Pro-panel redirect for a free Protect", proPanelShown())
            val protectSheet = ShadowDialog.getLatestDialog()
            assertNotNull("Protect opens the shared Protect sheet", protectSheet)
            assertNotNull(
                "the shared Protect flow asks for a password",
                protectSheet!!.findViewById<EditText>(R.id.protectPassword)
            )
            protectSheet.dismiss()
        }
    }
}
