package com.scanwhirl.app.w5e

import android.app.Application
import android.app.Dialog
import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.ui.ImportRasterNotice
import com.scanwhirl.app.ui.ImportSheet
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.qr.QrActivity
import com.scanwhirl.app.ui.protect.LockToolsActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.shell.VaultScreen
import com.scanwhirl.app.ui.tools.PdfImportSheets
import com.scanwhirl.app.ui.tools.ToolFirstScreenCopy
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.PdfImportBudget
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * W5e: harm-named guards for the VQA Vault, Import and named-tool first-screen rows.
 *
 * These assert what a person sees, not that a method was called. No crypto, setup-gate or
 * recovery behaviour is changed or weakened here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class W5eHonestyTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        ProGate.setTestOverride(app, true)
    }

    @After
    fun tearDown() {
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        ProGate.setTestOverride(app, null)
    }

    private fun screenText(view: View): String {
        val out = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) out.append(v.text).append('\n')
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out.toString()
    }

    // ------------------------------------------------------------------ Vault

    @Test
    fun `vaultNeverClaimsEncryptionBeforeSetup`() {
        val screen = VaultScreen(app)
        screen.dispatchWindowVisibilityChanged(View.VISIBLE)
        val before = screenText(screen)
        for (falseClaim in listOf("Encrypted on this phone", "AES-256", "Open Vault")) {
            assertFalse("before setup the tab must not say '$falseClaim'", before.contains(falseClaim))
        }
        assertTrue(before.contains(app.getString(R.string.vault_tab_not_setup)))
        assertEquals(
            "before setup the only action is setup",
            app.getString(R.string.vault_tab_setup),
            screen.findViewById<TextView>(R.id.swVaultAction).text.toString()
        )

        // A created but not unlocked Vault is honestly "locked", with an unlock action.
        val dir = File(app.filesDir, "vault2").apply { mkdirs() }
        File(dir, "recovery.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        screen.dispatchWindowVisibilityChanged(View.VISIBLE)
        val locked = screenText(screen)
        assertFalse("locked is not an encryption claim", locked.contains("AES-256"))
        assertTrue(locked.contains(app.getString(R.string.vault_tab_locked)))
        assertEquals(
            app.getString(R.string.vault_tab_unlock),
            screen.findViewById<TextView>(R.id.swVaultAction).text.toString()
        )

        // Unlocked state: the count and the "Open" action come back together.
        VaultSession.open(ByteArray(32) { 7 })
        screen.dispatchWindowVisibilityChanged(View.VISIBLE)
        val unlocked = screenText(screen)
        assertTrue(unlocked.contains(app.getString(R.string.vault_tab_note_unlocked)))
        assertTrue(unlocked.contains("unlocked"))
        assertEquals(
            app.getString(R.string.vault_tab_open),
            screen.findViewById<TextView>(R.id.swVaultAction).text.toString()
        )
    }

    @Test
    fun `vaultTabShowsNoAlgorithmNameInAnyState`() {
        for (res in listOf(
            R.string.vault_tab_encrypted, R.string.vault_tab_aes, R.string.vault_stats_body
        )) {
            val text = app.getString(res)
            assertFalse("$text", text.contains("AES", ignoreCase = true))
        }
    }

    // ----------------------------------------------------------------- Import

    @Test
    fun `everyImportDoorShowsTheRasteriseNotice`() {
        val notice = app.getString(R.string.import_raster_notice)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Door 1: Library Import sheet, PDF row visible before any picker.
                val importSheet: Dialog = ImportSheet.show(activity, {}, {})
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNotNull("the Library import chooser opens", importSheet)
                val rowText = screenText(importSheet.window!!.decorView)
                assertTrue("the PDF row carries the D1 notice", rowText.contains(notice))
                importSheet.dismiss()

                // Door 2: Scan FAB -> Import PDF shows the same notice before the picker.
                activity.findViewById<View>(R.id.swNavScan).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val create = ShadowDialog.getLatestDialog()!!
                create.findViewById<View>(R.id.swCreateImport).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val prePicker = ShadowDialog.getLatestDialog()!!
                assertTrue(prePicker.isShowing)
                assertEquals(
                    "the Scan FAB route uses the same helper text",
                    notice,
                    prePicker.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                assertNullPicker(activity)
                prePicker.findViewById<View>(R.id.swSheetPrimary).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNotNull("Continue opens the PDF picker", shadowOf(activity).nextStartedActivityForResult)

                // Door 3: the plan sheet after a picked PDF (PdfImportSheets).
                val plan = PdfImportBudget.plan(pageCount = 3, freeBytes = 2L * 1024 * 1024 * 1024)
                val planSheet = PdfImportSheets.ask(activity, plan) {}
                val planText = screenText(planSheet.view)
                assertTrue("the plan sheet carries the D1 notice", planText.contains(notice))
                planSheet.dismiss()
            }
        }

        // Door 4: Tools hub / Tools search import_pdf (ShellActivity.open) — one helper call.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.open(ToolRegistry.byId("import_pdf")!!)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val prePicker = ShadowDialog.getLatestDialog()!!
                assertTrue("the Tools import door opens the notice", prePicker.isShowing)
                assertEquals(
                    "all four doors use the same helper text",
                    notice,
                    prePicker.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                assertNullPicker(activity)
                prePicker.findViewById<View>(R.id.swSheetPrimary).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNotNull("Continue opens the PDF picker", shadowOf(activity).nextStartedActivityForResult)
            }
        }
    }

    private fun assertNullPicker(activity: ShellActivity) {
        org.junit.Assert.assertNull(
            "no picker opens before the notice is accepted",
            shadowOf(activity).nextStartedActivityForResult
        )
    }

    @Test
    fun `noMisleadingEditablePageCopy`() {
        for (file in listOf(File("src/main/res/values/strings.xml"), File("src/main/res/values-hi/strings.xml"))) {
            val text = file.readText(Charsets.UTF_8)
            assertFalse("${file.name} must not promise editable imported pages",
                text.contains("editable page", ignoreCase = true))
        }
        assertTrue(
            "the helper states the real effect",
            ImportRasterNotice.text(app).contains("images")
        )
    }

    @Test
    fun `readTextExplainsSearchablePdfWithoutAddingVisibleWords`() {
        // V107 recommendation: the distinction is behind overflow, never on the 62-word screen.
        val paragraphs = listOf(
            R.string.w5e_ocr_about_p1, R.string.w5e_ocr_about_p2, R.string.w5e_ocr_about_p3
        ).map { app.getString(it) }
        assertTrue("info sheet is under the 40-word limit", paragraphs.sumOf { it.split(Regex("\\s+")).size } <= 40)
        assertTrue("every paragraph is under 25 words", paragraphs.all { it.split(Regex("\\s+")).size < 25 })
        assertFalse("the OCR screen still has no extra visible purpose line",
            File("src/main/res/layout/activity_ocr_editor.xml").readText(Charsets.UTF_8)
                .contains("toolFirstPurpose"))

        val repo = ScanRepository(app)
        val docId = DocFixtures.saved(repo, app, pages = 2, name = "Reading notes")
        repo.updateOcrPage(docId, "page_000.jpg", "first")
        repo.updateOcrPage(docId, "page_001.jpg", "second")
        Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            android.content.Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
        ).setup().use { controller ->
            val activity = controller.get()
            val menu: Menu = activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
            if (menu.findItem(R.id.ocrAboutReading) == null) activity.onCreateOptionsMenu(menu)
            val item = menu.findItem(R.id.ocrAboutReading)
            assertNotNull("the OCR overflow has About reading text", item)
            item.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val sheet = ShadowDialog.getLatestDialog()!!
            val text = screenText(sheet.window!!.decorView)
            assertTrue("the sheet explains reading", text.contains(paragraphs[0]))
            assertTrue("the sheet distinguishes Searchable PDF", text.contains(paragraphs[1]))
            assertTrue("the sheet explains scanned images", text.contains(paragraphs[2]))
        }
    }

    // ------------------------------------------------ Named tools' first screens

    @Test
    fun `namedToolFirstScreensSayWhatTheyDo`() {
        val ids = listOf(
            "split", "to_word", "to_excel", "to_powerpoint", "long_image", "zip",
            "searchable_pdf", "batch_tools", "link_index", "remove_metadata",
            "qr_code", "protect", "unlock", "read_text", "pdfa", "form_ready"
        )
        for (id in ids) {
            val purpose = ToolFirstScreenCopy.text(app, id)
            assertNotNull("$id has no plain-language purpose", purpose)
            assertTrue("$id purpose is too short: $purpose", purpose!!.length > 18)
            assertTrue("$id purpose is too long for font 2.0 at 320 dp: $purpose", purpose.length <= 40)
            assertFalse("$id must not be a bare format noun", purpose.equals("ZIP", ignoreCase = true))
        }

        // The shared setup screen renders that same line for its own named tools.
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "zip")).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val view = activity.findViewById<TextView>(R.id.toolSetupPurpose)
                assertNotNull("ZIP's first screen has a purpose line", view)
                assertEquals(ToolFirstScreenCopy.text(app, "zip"), view.text.toString())
            }
        }
        ActivityScenario.launch<QrActivity>(QrActivity.intent(app)).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<TextView>(R.id.toolFirstPurpose)
                assertNotNull("QR's first screen says it reads a code", view)
                assertEquals(ToolFirstScreenCopy.text(app, "qr_code"), view.text.toString())
            }
        }
        ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val view = activity.findViewById<TextView>(R.id.toolFirstPurpose)
                assertNotNull("Protect's first screen says what it does", view)
                assertEquals(ToolFirstScreenCopy.text(app, "protect"), view.text.toString())
            }
        }
    }
}
