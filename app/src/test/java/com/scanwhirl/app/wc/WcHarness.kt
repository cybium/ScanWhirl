package com.scanwhirl.app.wc

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.backup.BackupActivity
import com.scanwhirl.app.ui.protect.LockToolsActivity
import com.scanwhirl.app.ui.qr.QrActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.ui.tools.BatchActivity
import com.scanwhirl.app.ui.tools.BusinessCardActivity
import com.scanwhirl.app.ui.tools.IdCardActivity
import com.scanwhirl.app.ui.tools.PassportActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.ui.tools.linkindex.LinkIndexActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.FormUnit
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SwActivitySession
import com.scanwhirl.app.util.UserContent
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Lane WC: one place that knows how to reach every main ScanWhirl screen/state
 * under Robolectric, lay it out at 360x800dp / 100% font / English, and count
 * its visible app-authored words. Shared by [WordBudgetReportTest] (report
 * mode, never fails on count) and [WordBudgetGateTest] (ratchet, fails on bloat).
 *
 * Every activity is owned by an [SwActivitySession] and destroyed in a finally
 * (the lead's binding lifecycle rule). Fixtures are fictional; no real media,
 * no secrets, no adb/browser, no network.
 */
class WcHarness {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(app)
    private lateinit var docA: String
    private lateinit var resultJpg: File
    private lateinit var resultZip: File
    private lateinit var stageDir: File
    private var defaultPagesFactory = PdfViewerActivity.pagesFactory

    private class FakePdfPages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int): Bitmap? = null
        override fun close() = Unit
    }

    fun setUp() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xxhdpi")
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        docA = DocFixtures.saved(repo, app, pages = 2, name = "Physics notes")
        DocFixtures.saved(repo, app, pages = 1, name = "Receipt August")
        resultJpg = File(app.cacheDir, "wc_result.jpg").apply {
            writeBytes(ImportFixtures.image(width = 240, height = 340))
        }
        resultZip = File(app.cacheDir, "wc_result.zip").apply {
            writeBytes(ByteArray(2048) { (it % 251).toByte() })
        }
        stageDir = File(app.cacheDir, "wc_staging").apply { mkdirs() }
        repeat(2) { i ->
            File(stageDir, "p$i.jpg").writeBytes(ImportFixtures.image(width = 240 + 20 * i, height = 340))
        }
        PdfViewerActivity.pagesFactory = { FakePdfPages(3) }
    }

    fun tearDown() {
        runCatching { PdfViewerActivity.pagesFactory = defaultPagesFactory }
        runCatching { VaultActivity.storeOverride = null }
        runCatching { VaultActivity.biometricStatusOverride = null }
        runCatching { VaultActivity.fingerprintStarter = null }
        runCatching { VaultSession.close() }
        runCatching { ProGate.setTestOverride(app, null) }
        runCatching { ProGate.setPro(app, false) }
        runCatching { RuntimeEnvironment.setFontScale(1.0f) }
    }

    // ---------------------------------------------------------------- model

    data class Row(
        val screen: String,
        val state: String,
        val words: Int,
        val top: List<String>,
        val error: String? = null
    )

    data class Spec(
        val screen: String,
        val state: String,
        val pro: Boolean? = null,
        val open: (WcHarness) -> Session
    )

    /** A laid-out root plus an owner that destroys the activity/dialog in finally. */
    class Session(val root: View, val activity: Activity?, private val release: () -> Unit) {
        fun close() = release()
    }

    // ---------------------------------------------------------------- screens

    /** The screens the owner asked for, derived from ToolRegistry for every tool setup. */
    fun specs(): List<Spec> {
        val base = listOf(
            Spec("Shell Library", "Free · EN · 100% · 3 fictional docs") { h ->
                h.shell(null)
            },
            Spec("Shell Tools", "Free · EN · 100%") { h ->
                h.shell(R.id.swNavTools)
            },
            Spec("Shell Me", "Free · EN · 100%") { h ->
                h.shell(R.id.swNavMe)
            },
            Spec("Settings", "Free · EN · 100%") { h ->
                h.activity(SettingsActivity.intent(h.app))
            },
            Spec("Pro", "Free · EN · 100% · purchase state", pro = false) { h ->
                h.proScreen()
            },
            Spec("Trash", "Free · EN · 100%") { h ->
                h.activity(TrashActivity.intent(h.app))
            },
            Spec("Vault (not set up)", "Free · EN · 100%") { h ->
                h.activity(VaultActivity.intent(h.app))
            },
            Spec("Vault (unlocked)", "Free · EN · 100% · test passcode") { h ->
                h.unlockedVault()
            },
            Spec("Backup", "Free · EN · 100%") { h ->
                h.activity(BackupActivity.intent(h.app))
            },
            Spec("Document viewer", "2 pages · EN · 100%") { h ->
                h.documentViewer()
            },
            Spec("Document actions sheet", "2 pages · EN · 100%") { h ->
                val host = h.documentViewer()
                h.sheet(host, { host.activity?.findViewById<View>(R.id.btnMore)?.performClick() })
            },
            Spec("PDF reader", "3 pages · EN · 100%") { h ->
                h.activity(PdfViewerActivity.intent(h.app, h.docA, "Physics notes"))
            },
            Spec("OCR editor", "2 pages · EN · 100%") { h ->
                h.activity(
                    Intent(h.app, OcrEditorActivity::class.java)
                        .putExtra(OcrEditorActivity.EXTRA_DOC_ID, h.docA)
                        .putExtra(OcrEditorActivity.EXTRA_TITLE, "Physics notes")
                )
            },
            Spec("Stamp editor", "2 pages · EN · 100%") { h ->
                h.activity(StampEditorActivity.intent(h.app, h.docA))
            },
            Spec("Scan review", "2 photos · EN · 100%") { h ->
                h.activity(ScanReviewActivity.intent(h.app, h.stageDir.path, null, "Fiction notes"))
            },
            // Explicit extra state the owner named: Form photo with a loaded image.
            Spec("Form photo (loaded)", "preset + photo · EN · 100%") { h ->
                h.loadedForm()
            },
            // Result screens (both producers, compress + form results).
            Spec("Result - tool compress", "compress · under target · EN · 100%") { h ->
                h.activity(
                    ToolResultActivity.intent(
                        h.app, "compress", docIds = listOf(h.docA), files = listOf(h.resultJpg),
                        mime = "image/jpeg", targetBytes = 500L * 1024L,
                        beforeBytes = 3L * 1024L * 1024L, sourceKept = true
                    )
                )
            },
            Spec("Result - tool form", "form photo · EN · 100%") { h ->
                h.activity(
                    ToolResultActivity.intent(
                        h.app, "form_ready", files = listOf(h.resultJpg),
                        mime = "image/jpeg", note = "200 x 230 px · 32 KB"
                    )
                )
            },
            Spec("Result - export compress", "compress · EN · 100%") { h ->
                h.activity(h.exportResult("Compressed", "Physics notes (compressed).jpg", "image/jpeg"))
            },
            Spec("Result - export form", "form photo · EN · 100%") { h ->
                h.activity(h.exportResult("Form photo", "Ravi form photo.jpg", "image/jpeg"))
            }
        )

        // Every READY tool gets its setup screen/sheet, enumerated from the real
        // registry (so a newly added tool is covered automatically). Dedicated
        // tools open their own screen; the rest use the shared ToolSetupActivity.
        val tools = com.scanwhirl.app.ui.kit.ToolRegistry.all
            .filter { it.status == com.scanwhirl.app.ui.kit.ToolStatus.READY }
            .map { tool ->
                Spec("Tool setup - ${tool.id}", "EN · 100% · ${tool.id}") { h ->
                    h.activity(h.toolIntent(tool.id))
                }
            }
        return base + tools
    }

    // ---------------------------------------------------------------- helpers

    private val userContentIds = setOf(
        R.id.swDocName, R.id.docPickerName, R.id.protectDocName, R.id.vaultEntryName,
        R.id.toolSetupDocName, R.id.toolResultFileName, R.id.toolResultName, R.id.bcName
    )

    private fun markUserContent(root: View) {
        fun walk(v: View) {
            if (v.id in userContentIds) UserContent.mark(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    private fun layout(root: View, w: Int, h: Int) {
        repeat(3) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
        }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun latestDialog(): Dialog? =
        runCatching { ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } }.getOrNull()

    private fun waitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return true
            runCatching { Thread.sleep(20) }
        }
        idle()
        return cond()
    }

    private fun sheetRoot(dialog: Dialog): View =
        dialog.findViewById<View>(R.id.swSheetRoot)
            ?: dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: dialog.window?.decorView
            ?: error("sheet has no root")

    private fun activity(intent: Intent): Session {
        applyPro()
        val session = SwActivitySession.launch<Activity>(intent)
        val a = session.activity
        idle()
        val root = a.window.decorView
        layout(root, W, H)
        markUserContent(root)
        return Session(root, a) { session.close() }
    }

    private fun shell(tabNavId: Int?): Session {
        val s = activity(ShellActivity.intent(app))
        if (tabNavId != null) {
            s.activity?.findViewById<View>(tabNavId)?.performClick()
            idle()
            waitUntil { tabPresent(s.activity!!, tabNavId) }
            s.activity?.window?.decorView?.let { layout(it, W, H); markUserContent(it) }
        }
        return s
    }

    private fun tabPresent(a: Activity, id: Int): Boolean = when (id) {
        R.id.swNavTools -> a.findViewById<View>(R.id.swToolsSearch) != null
        R.id.swNavVault -> a.findViewById<View>(R.id.swVaultNote) != null
        R.id.swNavMe -> a.findViewById<View>(R.id.swMeList) != null
        else -> a.findViewById<View>(R.id.swDocList) != null
    }

    private fun proScreen(): Session {
        val s = shell(R.id.swNavMe)
        s.activity?.findViewById<View>(R.id.swMePro)?.performClick()
        idle()
        waitUntil { s.activity?.findViewById<View>(R.id.swProScreen) != null }
        s.activity?.window?.decorView?.let { layout(it, W, H); markUserContent(it) }
        return s
    }

    private fun documentViewer(): Session {
        val s = activity(DocumentViewerActivity.intent(app, docA))
        waitUntil(4_000) { s.activity?.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
        return s
    }

    private fun sheet(host: Session, open: () -> Unit): Session {
        open()
        idle()
        waitUntil { latestDialog() != null }
        val dialog = latestDialog() ?: error("no sheet opened")
        val root = sheetRoot(dialog)
        layout(root, W - 48, H)
        markUserContent(root)
        return Session(root, host.activity) {
            runCatching { dialog.dismiss() }
            host.close()
        }
    }

    private fun unlockedVault(): Session {
        VaultSession.close()
        val keys = FakeVaultKeys()
        val store = VaultStore(
            context = app, repo = repo, keys = keys,
            gate = VaultPasscodeGate(clock = { 0L }), iterations = 2_000
        )
        store.setup("246810")
        store.markConfirmationPassed()
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        return activity(VaultActivity.intent(app))
    }

    private fun loadedForm(): Session {
        val s = activity(FormReadyActivity.intent(app))
        val a = s.activity as? FormReadyActivity ?: return s
        a.setCustomValuesForTest(3.5f, 4.5f, FormUnit.CM, 300, 20, 50)
        a.findViewById<View>(R.id.formReadyPresetPhoto)?.performClick()
        idle()
        a.acceptPickedBytes(ImportFixtures.image(width = 600, height = 500))
        waitUntil(4_000) { a.hasImageForTest() }
        a.window.decorView.let { layout(it, W, H); markUserContent(it) }
        return s
    }

    private fun exportResult(title: String, displayName: String, mime: String): Intent =
        ExportResultActivity.intent(
            app,
            outputPath = resultJpg.path,
            previewPath = resultJpg.path,
            mime = mime,
            displayName = displayName,
            beforeBytes = 3L * 1024L * 1024L,
            afterBytes = 420_000L,
            width = 240,
            height = 340,
            title = title,
            warning = null,
            docId = docA
        )

    /** The registry-owned tool screens this lane can launch directly. */
    private fun toolIntent(id: String): Intent = when (id) {
        "passport" -> PassportActivity.intent(app)
        "id_card" -> IdCardActivity.intent(app)
        "business_card" -> BusinessCardActivity.intent(app)
        "batch_tools" -> BatchActivity.intent(app, emptyList())
        "form_ready" -> FormReadyActivity.intent(app)
        "link_index" -> LinkIndexActivity.intent(app)
        "protect" -> LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT)
        "unlock" -> LockToolsActivity.intent(app, LockToolsActivity.MODE_UNLOCK)
        "vault" -> VaultActivity.intent(app)
        "qr_code" -> QrActivity.intent(app)
        else -> ToolSetupActivity.intent(app, id)
    }

    private fun applyPro() {
        val pro = pendingPro
        if (pro != null) ProGate.setTestOverride(app, pro)
        pendingPro = null
    }

    private var pendingPro: Boolean? = null

    /** Measure one spec; never throws (a broken screen becomes an error row). */
    fun measure(spec: Spec): Row {
        pendingPro = spec.pro
        var session: Session? = null
        return try {
            session = spec.open(this)
            // A screen that opens a sheet on entry is measured from the sheet (the
            // topmost thing a person sees), exactly like the Document actions spec.
            val dialog = latestDialog()
            val root = dialog?.let { sheetRoot(it) } ?: session!!.root
            layout(root, if (dialog == null) W else W - 48, H)
            markUserContent(root)
            val result = VisibleWords.count(root)
            Row(spec.screen, spec.state, result.words, result.topStrings())
        } catch (t: Throwable) {
            val msg = (t.message ?: t.javaClass.simpleName).take(160).replace('|', '/').replace('\n', ' ')
            Row(spec.screen, spec.state, 0, emptyList(), error = msg)
        } finally {
            runCatching { session?.close() }
            pendingPro = null
        }
    }

    private companion object {
        const val W = 1080
        const val H = 2400
    }
}
