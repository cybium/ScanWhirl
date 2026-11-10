package com.scanwhirl.app.ui

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
 * WP-22 regression repair — the REAL entry points, in forced-Free mode.
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
class FreeLegacyVaultEntryPointTest {

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

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun latestToast(): String? = ShadowToast.getTextOfLatestToast()?.toString()

    /** True when the latest dialog is the Pro-suite dossier (the Pro panel). */
    private fun proPanelShown(): Boolean =
        ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.proDossierStatus) != null

    private fun openToolsAndTap(activity: MainActivity, tileId: Int) {
        activity.findViewById<View>(R.id.btnTools).performClick()
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("Tools sheet opens", sheet)
        val tile = sheet!!.findViewById<View>(tileId)
        assertNotNull("tile present", tile)
        tile.performClick()
    }

    private fun pinDialog(): AlertDialog? {
        val d = ShadowDialog.getLatestDialog() as? AlertDialog ?: return null
        return d.takeIf { dlg ->
            allViews(dlg.window!!.decorView).any { it is EditText }
        }
    }

    private fun submitPin(dialog: AlertDialog, pin: String) {
        val input = allViews(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        input.setText(pin)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AlertDialog dispatches button clicks through a Handler message.
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun waitForHome(scenario: ActivityScenario<MainActivity>, docs: Int) {
        assertTrue("home list loads", pollUntil {
            var ok = false
            scenario.onActivity { a ->
                ok = (a.findViewById<RecyclerView>(R.id.scanList).adapter?.itemCount ?: 0) >= docs
            }
            ok
        })
    }

    private fun vaultBannerShown(activity: MainActivity) =
        activity.findViewById<View>(R.id.vaultBanner).isVisible

    // ---- Tools sheet → tile_vault ------------------------------------

    @Test
    fun `free tools vault tile with existing pin shows PIN dialog not the Pro panel`() {
        seedDoc("Public doc")
        Prefs.setVaultPin(app, "2580")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { activity ->
                assertFalse("really Free", ProGate.isPro(activity))
                openToolsAndTap(activity, R.id.tile_vault)
                assertFalse("no Pro-panel redirect for an existing vault", proPanelShown())
                assertNotNull("PIN prompt shown to Free user", pinDialog())
                assertFalse("vault stays locked until PIN verified", vaultBannerShown(activity))
            }
        }
    }

    @Test
    fun `free wrong PIN keeps the vault closed and correct PIN opens it`() {
        seedDoc("Public doc")
        val secretId = seedDoc("Secret doc")
        repo.setVaulted(secretId, true)
        Prefs.setVaultPin(app, "2580")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { activity ->
                openToolsAndTap(activity, R.id.tile_vault)
                val wrong = pinDialog()
                assertNotNull(wrong)
                submitPin(wrong!!, "1111")
                assertEquals(app.getString(R.string.vault_pin_wrong), latestToast())
                assertFalse("wrong PIN must not open the vault", vaultBannerShown(activity))
                assertFalse(proPanelShown())
            }
            scenario.onActivity { activity ->
                openToolsAndTap(activity, R.id.tile_vault)
                val right = pinDialog()
                assertNotNull(right)
                submitPin(right!!, "2580")
                assertTrue("correct PIN opens the existing vault in Free", vaultBannerShown(activity))
            }
            assertTrue("vault lists the hidden doc", pollUntil {
                var ok = false
                scenario.onActivity { a ->
                    val list = a.findViewById<RecyclerView>(R.id.scanList)
                    ok = list.adapter?.itemCount == 1
                }
                ok
            })
        }
        assertTrue("PIN data untouched", Prefs.hasVaultPin(app))
        assertTrue("vaulted doc untouched", repo.getDocument(secretId)!!.isVaulted)
    }

    @Test
    fun `free vault tile without a PIN is blocked with truthful copy and creates nothing`() {
        seedDoc("Public doc")
        assertFalse(Prefs.hasVaultPin(app))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { activity ->
                openToolsAndTap(activity, R.id.tile_vault)
                assertEquals(app.getString(R.string.vault_new_unavailable), latestToast())
                assertFalse("no Pro-panel redirect", proPanelShown())
                assertNull("no PIN-setup / entry dialog", pinDialog())
                assertFalse(vaultBannerShown(activity))
            }
        }
        assertFalse("no PIN was created", Prefs.hasVaultPin(app))
    }

    @Test
    fun `pro vault tile without a PIN is also blocked - setup disabled for everyone`() {
        ProGate.setTestOverride(app, true)
        seedDoc("Public doc")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { activity ->
                assertTrue("really Pro", ProGate.isPro(activity))
                openToolsAndTap(activity, R.id.tile_vault)
                assertEquals(app.getString(R.string.vault_new_unavailable), latestToast())
                assertNull(pinDialog())
                assertFalse(vaultBannerShown(activity))
            }
        }
        assertFalse(Prefs.hasVaultPin(app))
    }

    @Test
    fun `adding from inside an open vault is blocked in Free and Pro`() {
        for (pro in listOf(false, true)) {
            ProGate.setTestOverride(app, pro)
            ShadowToast.reset()
            seedDoc("Doc for $pro")
            Prefs.setVaultPin(app, "2580")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForHome(scenario, 1)
                scenario.onActivity { activity ->
                    openToolsAndTap(activity, R.id.tile_vault)
                    submitPin(pinDialog()!!, "2580")
                    assertTrue(vaultBannerShown(activity))
                    ShadowToast.reset()
                    activity.findViewById<View>(R.id.btnScan).performClick()
                    assertEquals("pro=$pro", app.getString(R.string.vault_new_unavailable), latestToast())
                    assertFalse("pro=$pro: no Pro panel", proPanelShown())
                    assertNull("pro=$pro: no camera/picker launched",
                        shadowOf(activity).nextStartedActivity)
                }
            }
        }
    }

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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
            assertTrue("unlock completes", pollUntil { !repo.getDocument(id)!!.isLocked })
        }
        assertTrue("PDF still present after unlock", repo.getDocument(id)!!.pdfFile.length() > 0)
    }

    @Test
    fun `free user still sees the Pro badge and panel for Protect on an unlocked doc`() {
        val id = seedDoc("Open doc")
        withMoreSheet(id) { _, sheet ->
            assertTrue(sheet.findViewById<View>(R.id.pillLock).isVisible)
            sheet.findViewById<View>(R.id.rowMoreLock).performClick()
            assertTrue("Protect stays Pro until G5", proPanelShown())
        }
    }

    // ---- Home selection bar → Free ZIP export -------------------------

    @Test
    fun `free selection export builds a ZIP and opens the export result without the Pro panel`() {
        seedDoc("Export me")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { activity ->
                assertFalse("really Free", ProGate.isPro(activity))
                val list = activity.findViewById<RecyclerView>(R.id.scanList)
                if (list.childCount == 0) {
                    list.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    list.layout(0, 0, 1080, 1920)
                }
                activity.findViewById<View>(R.id.btnSelect).performClick()
                assertTrue("row 0 is laid out", pollUntil {
                    list.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    list.layout(0, 0, 1080, 1920)
                    list.findViewHolderForAdapterPosition(0) != null
                })
                list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                val export = activity.findViewById<View>(R.id.btnSelExport)
                assertTrue("Export visible in Free", export.isVisible)
                assertTrue("Export enabled with a selection", export.isEnabled)
                export.performClick()
            }
            var started: android.content.Intent? = null
            assertTrue("ZIP export launches the result screen", pollUntil {
                scenario.onActivity { a -> started = shadowOf(a).nextStartedActivity }
                started != null
            })
            assertEquals(ExportResultActivity::class.java.name, started!!.component?.className)
            assertFalse("no Pro panel on Free export", proPanelShown())
        }
    }
}
