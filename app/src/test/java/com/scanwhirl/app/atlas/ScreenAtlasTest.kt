package com.scanwhirl.app.atlas
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.MergePdfActivity
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.PageViewerActivity
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
import com.scanwhirl.app.ui.tools.PhotoCropFlowActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.ProGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * UI-ATLAS: a REPRODUCIBLE screen atlas generated from the real code.
 *
 * RUN ONLY ON DEMAND. The normal gate excludes this class through `testOptions` in
 * app/build.gradle.kts (a fail-closed gate treats a skipped test as a failure, so the class
 * is filtered out instead of skipped). Inside, [org.junit.Assume] still refuses to run when
 * `-Dscanwhirl.atlas=true` is missing, so a stray `--tests` run cannot start a long job.
 *
 * What it does, per screen state:
 *  * launches the fixture state (fresh library, three fictional documents, Free, English,
 *    w360dp-h800dp-xxhdpi) at font scale 1.0 and 2.0;
 *  * lays the decor view out at 1080x2400 and dispatches a 108 px status-bar / 72 px
 *    navigation-bar inset (the Robolectric window has none, and the Backup header bug of
 *    18 Nov was exactly a missing top inset - it must stay visible here);
 *  * draws the decor (or the open sheet over its dimmed host) to PNG;
 *  * records every visible clickable/focusable view (id, text, class, bounds, enabled);
 *  * writes a numbered red box overlay PNG;
 *  * presses each clickable button (destructive ones are only recorded), one level deep,
 *    and records what happened: started Activity + extras, a new sheet (which becomes its
 *    own screen entry, with one more level of buttons), toast, snackbar, a visible screen
 *    change, or "no visible effect" (a dead-button candidate);
 *  * writes app/build/atlas/atlas.json for tools/atlas/make_atlas.py.
 *
 * HONEST LIMITS (also written into atlas.json):
 *  * Robolectric is not a phone: system UI (ML Kit scanner, Android photo picker, SAF,
 *    share chooser) cannot be drawn; they are listed as "not renderable".
 *  * PdfRenderer does not run under Robolectric, so PdfViewer uses its existing test seam
 *    (`pagesFactory`) with fake page bitmaps: chrome and dock are real, page images are not.
 *  * The navigation pass runs at font scale 1.0 only (the 2.0 pass renders and lists
 *    buttons); a press re-launches the state, except when the previous press left the
 *    screen byte-for-byte unchanged, where the same instance is reused (same state).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class ScreenAtlasTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private companion object {
        const val W = 1080                 // 360 dp * 3 (xxhdpi)
        const val H = 2400                 // 800 dp * 3
        const val STATUS_BAR_PX = 108      // 36 dp status bar, dispatched (not a device inset)
        const val NAV_BAR_PX = 72          // 24 dp navigation bar
        const val NAV_BUDGET = 420         // total button presses in the navigation pass
        const val SUB_BUDGET = 320         // presses inside discovered sheets
        const val MAX_SUB_SCREENS = 60
        val DESTRUCTIVE = Regex(
            "\\b(delete|trash|purge|remove|discard|empty|reset)\\b",
            RegexOption.IGNORE_CASE
        )
        val SCALES = listOf(1.0f, 2.0f)
    }

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var outDir: File
    private lateinit var docA: String
    private lateinit var docB: String
    private lateinit var docC: String
    private lateinit var stageDir: File
    private lateinit var resultJpg: File
    private lateinit var resultZip: File
    private lateinit var cropPhoto: File

    private var defaultPagesFactory: ((android.os.ParcelFileDescriptor) -> PdfViewerActivity.PdfPages)? = null

    private val screens = ArrayList<ScreenEntry>()
    private val indexOf = LinkedHashMap<String, Int>()
    private val usedSubKeys = HashSet<String>()
    private var nextIndex = 1
    private var navBudget = NAV_BUDGET
    private var subBudget = SUB_BUDGET
    private var subScreenCount = 0
    private val notes = ArrayList<String>()
    private var navTruncated = false

    /**
     * A contiguous slice of `baseSpecs()` to render in this JVM, from `ATLAS_SLICE=from:to` (env, because
     * Gradle test tasks inherit the environment but not arbitrary -D properties without a build script
     * change). Empty = the whole atlas. The full 136-shot run needs ~3 GB RSS and the 3.5 GB WSL VM
     * (2 GB swap, Windows host at 7.4 GB) OOM-killed it twice at shot 78 and 93 on 2026-11-20; each slice
     * stays near 1 GB, and tools/atlas/merge_atlas.py stitches the per-slice JSON back into atlas.json.
     */
    private fun sliceRange(): IntRange {
        val raw = System.getenv("ATLAS_SLICE")?.trim().orEmpty()
        if (raw.isEmpty()) return 0 until Int.MAX_VALUE
        val parts = raw.split(":").mapNotNull { it.trim().toIntOrNull() }
        require(parts.size == 2 && parts[0] >= 0 && parts[1] > parts[0]) { "ATLAS_SLICE must be from:to, got '$raw'" }
        return parts[0] until parts[1]
    }

    private val slicing = System.getenv("ATLAS_SLICE")?.isNotBlank() == true

    // ---------------------------------------------------------------- fixture

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "UI-ATLAS runs only on demand: pass -Dscanwhirl.atlas=true",
            System.getProperty("scanwhirl.atlas") == "true"
        )
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        // Fixture: Free (not Pro), English (Robolectric's default locale), fresh library.
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // forced Free, even in a -PtestBuild run
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xxhdpi")

        docA = DocFixtures.saved(repo, app, pages = 2, name = "Physics notes")
        docB = DocFixtures.saved(repo, app, pages = 1, name = "Receipt August")
        docC = DocFixtures.saved(repo, app, pages = 1, name = "ID card copy")
        // Let the post-save indexer settle before writing OCR geometry into the same folder.
        idle()
        runCatching { Thread.sleep(500) }
        idle()
        // Windows (this atlas also runs from the Windows JDK) can refuse the atomic replace of
        // ocr_geo.txt while the save's indexer is still reading it; retry that one write.
        var ocrError: Throwable? = null
        var ocrDone = false
        repeat(5) { attempt ->
            if (!ocrDone) {
                try {
                    DocFixtures.withOcr(repo, docA)
                    ocrDone = true
                    ocrError = null
                } catch (t: Throwable) {
                    ocrError = t
                    runCatching { Thread.sleep(200L * (attempt + 1)) }
                }
            }
        }
        ocrError?.let { throw it }

        stageDir = File(app.cacheDir, "atlas_staging").apply { mkdirs() }
        (0 until 2).forEach { i ->
            File(stageDir, "p$i.jpg").writeBytes(
                ImportFixtures.image(width = 240 + 20 * i, height = 340)
            )
        }
        resultJpg = File(app.cacheDir, "atlas_result.jpg").apply {
            writeBytes(ImportFixtures.image(width = 240, height = 340))
        }
        resultZip = File(app.cacheDir, "atlas_result.zip").apply {
            writeBytes(ByteArray(2048) { (it % 251).toByte() })
        }
        cropPhoto = File(app.cacheDir, "atlas_crop.jpg").apply {
            writeBytes(ImportFixtures.image(width = 600, height = 400))
        }

        outDir = File(
            System.getProperty("scanwhirl.atlas.out")
                ?: File(System.getProperty("user.dir") ?: ".", "build/atlas").absolutePath
        ).apply { mkdirs() }

        defaultPagesFactory = PdfViewerActivity.pagesFactory
        // PdfRenderer is not available under Robolectric (existing F22 seam).
        PdfViewerActivity.pagesFactory = { FakePdfPages(3) }
    }

    @After
    fun tearDown() {
        runCatching { defaultPagesFactory?.let { PdfViewerActivity.pagesFactory = it } }
        runCatching { RuntimeEnvironment.setFontScale(1.0f) }
        runCatching { ProGate.setTestOverride(app, null) }
        runCatching { ProGate.setPro(app, false) }
    }

    private class FakePdfPages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int): Bitmap? = null
        override fun close() = Unit
    }

    // ---------------------------------------------------------------- test

    @Test
    fun `the screen atlas`() {
        notes += "Fixture invariants (Free, English, font scale) are restored before every launch and every press. " +
            "A press that persists something (language, sort, category) changes it for real; document content can drift " +
            "within the navigation pass, and the base screenshots are taken before that pass."
        val specs = baseSpecs()
        // Stable numbering: base screens first, in spec order; discovered sheets follow.
        specs.forEach { indexOf.getOrPut(it.key) { nextIndex++ } }
        // A slice renders only its own base specs (and the sheets they discover). Indices above were seeded
        // from the whole list, so base numbers never depend on the slice; sheet numbers get a per-slice
        // block (100, 1100, 2100 ...) so two slices can never hand out the same number.
        val mine = specs.filterIndexed { i, _ -> i in sliceRange() }
        if (slicing) nextIndex = 100 + sliceRange().first * 100

        // Phase A+B: render every base screen at both font scales.
        for (scale in SCALES) {
            RuntimeEnvironment.setFontScale(scale)
            for (spec in mine) renderBase(spec, scale)
        }

        // Phase C: press every visible button once (scale 1.0), discover sheets.
        RuntimeEnvironment.setFontScale(1.0f)
        for (spec in mine) {
            if (navBudget <= 0) { navTruncated = true; break }
            navigateBase(spec)
        }

        writeJson(specs)
    }

    // ---------------------------------------------------------------- screen model

    private inner class ScreenSpec(
        val key: String,
        val title: String,
        val path: String,
        val ready: (Activity) -> Boolean = { true },
        val open: () -> Session
    )

    private inner class Session(
        val scenario: ActivityScenario<Activity>?,
        val activity: Activity,
        val root: View,
        val dialog: Dialog?
    ) {
        var originX = 0
        var originY = 0
        fun close() {
            runCatching { if (dialog?.isShowing == true) dialog.dismiss() }
            runCatching { scenario?.close() }
            repeat(3) {
                runCatching {
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
                }
            }
        }
    }

    private class Btn(
        val n: Int,
        val idName: String,
        val label: String,
        val cls: String,
        val bounds: Rect,
        val enabled: Boolean,
        val clickable: Boolean,
        val focusable: Boolean,
        val destructive: Boolean,
        val matchKey: String,
        val nth: Int,
        val idNth: Int
    ) {
        fun json(): JSONObject = JSONObject().apply {
            put("n", n)
            put("id", idName)
            put("label", label)
            put("class", cls)
            put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            put("enabled", enabled)
            put("clickable", clickable)
            put("focusable", focusable)
            put("destructive", destructive)
            put("effect", JSONObject.NULL)
            put("dead", false)
        }
    }

    private class ScreenEntry(
        val index: Int,
        val key: String,
        val title: String,
        val path: String,
        val scale: String,
        val kind: String,
        val parent: String?,
        val png: String,
        val overlay: String,
        val topText: String,
        val contentTop: Int,
        val dialogTitle: String?,
        val raw: List<Btn>
    ) {
        val buttons: MutableList<JSONObject> = raw.map { it.json() }.toMutableList()
        val notes: MutableList<String> = mutableListOf()
    }

    // ---------------------------------------------------------------- base screen list

    private fun shell(tabNavId: Int? = null): Session {
        val s = launch(ShellActivity.intent(app))
        if (tabNavId != null) {
            s.activity.findViewById<View>(tabNavId)?.performClick()
            idle()
            waitUntil(3000) { tabPresent(s.activity, tabNavId) }
        }
        return s
    }

    private fun tabPresent(a: Activity, tabNavId: Int): Boolean = when (tabNavId) {
        R.id.swNavTools -> a.findViewById<View>(R.id.swToolsSearch) != null
        R.id.swNavVault -> a.findViewById<View>(R.id.swVaultNote) != null
        R.id.swNavMe -> a.findViewById<View>(R.id.swMeList) != null
        else -> a.findViewById<View>(R.id.swDocList) != null
    }

    private fun openViewer(): Session {
        val s = launch(DocumentViewerActivity.intent(app, docA))
        waitUntil(9000) {
            s.activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
        }
        return s
    }

    private fun openSheet(host: Session, open: (Activity) -> Unit, ready: (Dialog) -> Boolean = { true }): Session {
        open(host.activity)
        idle()
        waitUntil(6000) { latestDialog()?.let(ready) == true }
        val d = latestDialog() ?: error("no sheet opened")
        return sheetSession(host, d)
    }

    private fun sheetSession(host: Session, dialog: Dialog): Session {
        val decor = dialog.window?.decorView
        val sw = dialog.findViewById<View>(R.id.swSheetRoot)
        val bottom = decor?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        val content = when {
            sw != null -> sw
            bottom is ViewGroup && bottom.childCount > 0 -> bottom.getChildAt(0)
            else -> decor ?: error("sheet without a window")
        }
        return Session(host.scenario, host.activity, content, dialog)
    }

    private fun baseSpecs(): List<ScreenSpec> {
        return listOf(
            ScreenSpec("library", "Library", "launch app > Library tab") { shell() },
            ScreenSpec("tools", "Tools", "launch app > Tools tab") { shell(R.id.swNavTools) },
            ScreenSpec("vault_tab", "Vault", "launch app > Vault tab (not set up)") { shell(R.id.swNavVault) },
            ScreenSpec("me", "Me", "launch app > Me tab") { shell(R.id.swNavMe) },
            ScreenSpec(
                "create_sheet", "Create", "Library > centre Scan",
                ready = { latestDialog() != null }
            ) {
                val s = shell()
                s.activity.findViewById<View>(R.id.swNavScan)?.performClick()
                idle()
                waitUntil(4000) { latestDialog() != null }
                val d = latestDialog() ?: error("the Create sheet did not open")
                sheetSession(s, d)
            },
            ScreenSpec(
                "viewer", "Document viewer", "Library > a document",
                ready = { it.findViewById<View>(R.id.pageCount) != null },
                open = { openViewer() }
            ),
            ScreenSpec(
                "actions_sheet", "Actions", "Document viewer > Actions",
                ready = { latestDialog() != null }
            ) {
                val s = openViewer()
                openSheet(s, { a -> a.findViewById<View>(R.id.btnMore)?.performClick() })
            },
            ScreenSpec(
                "compress_sheet", "Compress", "Document viewer > Actions > Compress",
                ready = { latestDialog()?.findViewById<View>(R.id.editCompressTarget) != null }
            ) {
                val s = openViewer()
                val actions = openSheet(s, { a -> a.findViewById<View>(R.id.btnMore)?.performClick() })
                openSheet(actions, { _ ->
                    actions.root.findViewById<View>(R.id.rowMoreCompress)?.performClick()
                })
            },
            ScreenSpec(
                "compress_result", "Compressed", "Actions > Compress > Continue",
                open = {
                    launch(
                        ToolResultActivity.intent(
                            app, "compress", docIds = listOf(docA),
                            files = listOf(resultJpg), mime = "image/jpeg",
                            targetBytes = 500L * 1024L, beforeBytes = 3L * 1024L * 1024L,
                            sourceKept = true
                        )
                    )
                }
            ),
            ScreenSpec("toolsetup_zip", "ZIP export", "Tools > ZIP export") {
                launch(ToolSetupActivity.intent(app, "zip"))
            },
            ScreenSpec("tool_result", "Done", "Tools > ZIP export > Continue") {
                launch(
                    ToolResultActivity.intent(
                        app, "zip", files = listOf(resultZip), mime = "application/zip",
                        note = "2 files"
                    )
                )
            },
            ScreenSpec(
                "ocr_editor", "Text", "Document viewer > Read text",
                open = {
                    launch(
                        Intent(app, OcrEditorActivity::class.java)
                            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, docA)
                            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Physics notes")
                    )
                }
            ),
            ScreenSpec("trash", "Trash", "Me > Trash") { launch(TrashActivity.intent(app)) },
            ScreenSpec("settings", "Settings", "Me > Settings") { launch(SettingsActivity.intent(app)) },
            ScreenSpec("backup", "Backup & restore", "Me > Backup") { launch(BackupActivity.intent(app)) },
            ScreenSpec("lock_tools", "Protect PDF", "Tools > Protect PDF") {
                launch(LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT))
            },
            ScreenSpec("merge", "Merge", "Tools > Merge") { launch(MergePdfActivity.intent(app)) },
            ScreenSpec("batch", "Batch tools", "Tools > Batch tools") {
                launch(BatchActivity.intent(app, listOf(docA, docB)))
            },
            ScreenSpec("id_card", "ID card", "Create > ID card") { launch(IdCardActivity.intent(app)) },
            ScreenSpec("passport", "Passport photo", "Create > Passport") { launch(PassportActivity.intent(app)) },
            ScreenSpec("qr", "QR code", "Tools > QR code") { launch(QrActivity.intent(app)) },
            ScreenSpec(
                "stamp_editor", "Edit document", "Document viewer > Sign / Edit",
                open = { launch(StampEditorActivity.intent(app, docA)) }
            ),
            ScreenSpec("pdf_viewer", "Reader", "Library > a document > Pages") {
                launch(PdfViewerActivity.intent(app, docA, "Physics notes"))
            },
            ScreenSpec("page_viewer", "Page", "Document viewer > a page") {
                launch(PageViewerActivity.intent(app, docA, 0))
            },
            ScreenSpec("export_result", "Saved", "Any export > result") {
                launch(
                    ExportResultActivity.intent(
                        app,
                        outputPath = resultJpg.path,
                        previewPath = resultJpg.path,
                        mime = "image/jpeg",
                        displayName = "Physics notes (compressed).jpg",
                        beforeBytes = 3L * 1024L * 1024L,
                        afterBytes = 420_000L,
                        width = 240,
                        height = 340,
                        title = "Compressed",
                        warning = null,
                        docId = docA
                    )
                )
            },
            ScreenSpec("pro_screen", "ScanWhirl Pro", "Me > ScanWhirl Pro") {
                val s = shell(R.id.swNavMe)
                s.activity.findViewById<View>(R.id.swMePro)?.performClick()
                idle()
                waitUntil(3000) { s.activity.findViewById<View>(R.id.swProScreen) != null }
                s
            },
            ScreenSpec("vault", "Vault (not set up)", "Me > Vault") { launch(VaultActivity.intent(app)) },
            ScreenSpec("scan_review", "Review", "Scan > photos > Review") {
                launch(ScanReviewActivity.intent(app, stageDir.path, null, "Fiction notes"))
            },
            ScreenSpec("business_card", "Business card", "Create > Business card") {
                launch(BusinessCardActivity.intent(app))
            },
            ScreenSpec("photo_crop", "Crop photo", "Insert pages > photos") {
                launch(PhotoCropFlowActivity.intent(app, listOf(cropPhoto)))
            }
            // W8: legacy Home and Book pages slices removed with their unreachable screens.
        )
    }

    // ---------------------------------------------------------------- launch / idle

    private fun launch(intent: Intent): Session {
        val scenario = ActivityScenario.launch<Activity>(intent)
        var activity: Activity? = null
        scenario.onActivity { a ->
            activity = a
            idle()
        }
        val a = activity ?: error("activity did not start")
        drainStarted(a)
        return Session(scenario, a, a.window.decorView, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * The fixture invariant: Free and English. The navigation pass presses real buttons, and
     * some of them persist a preference (language, sort, category), so every press starts from
     * the promised state again. Document categories are content, not an invariant; they can
     * drift within the pass (reported as a press effect).
     */
    private fun resetInvariants(scale: Float) {
        runCatching { ProGate.setPro(app, false) }
        runCatching { ProGate.setTestOverride(app, false) }
        runCatching {
            // Only pull the app back when a press switched it to Hindi. Do NOT force an explicit
            // "English" choice: that would turn the App language sheet's English row into a no-op.
            if (AppLanguage.currentTag(app) == AppLanguage.HINDI || AppLanguage.showingHindi(app)) {
                AppLanguage.apply(app, AppLanguage.ENGLISH)
            }
        }
        runCatching { RuntimeEnvironment.setFontScale(scale) }
    }

    private fun waitUntil(timeoutMs: Long = 6000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return true
            runCatching { Thread.sleep(20) }
        }
        idle()
        return cond()
    }

    private fun latestDialog(): Dialog? =
        runCatching { ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } }.getOrNull()

    private fun drainStarted(a: Activity) {
        repeat(5) {
            val next = runCatching { shadowOf(a).nextStartedActivity }.getOrNull() ?: return
            if (next.component == null && next.action == null) return
        }
    }

    // ---------------------------------------------------------------- render

    private fun renderBase(spec: ScreenSpec, scale: Float) {
        try {
            resetInvariants(scale)
            val s = spec.open()
            try {
                waitUntil(4000) { spec.ready(s.activity) }
                screens += renderAndCollect(spec.key, spec.title, spec.path, scale, null, s)
            } finally {
                s.close()
            }
        } catch (t: Throwable) {
            notRenderable(spec.key, spec.title, spec.path, scale, t)
        }
    }

    private fun renderAndCollect(
        key: String, title: String, path: String, scale: Float, parent: String?, s: Session
    ): ScreenEntry {
        place(s)
        val idx = indexOf.getOrPut(key) { nextIndex++ }
        val base = String.format(Locale.ROOT, "%03d_%s_%.1f", idx, key, scale)
        val png = File(outDir, "$base.png")
        val overlayPng = File(outDir, "${base}_boxes.png")
        val bmp = drawSession(s)
        val buttons = enumerate(s)
        try {
            save(bmp, png)
            val ov = bmp.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(ov)
            if (ov !== bmp) c.drawBitmap(bmp, 0f, 0f, null)
            drawOverlay(c, buttons)
            save(ov, overlayPng)
            ov.recycle()
        } finally {
            bmp.recycle()
        }
        val entry = ScreenEntry(
            idx, key, title, path, String.format(Locale.ROOT, "%.1f", scale),
            if (s.dialog != null) "sheet" else "activity", parent,
            png.name, overlayPng.name,
            topText(s),
            contentTop(s),
            s.dialog?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString(),
            buttons
        )
        // 180 screens x (decor + overlay) bitmaps: keep the peak flat over a long run.
        runCatching { System.gc() }
        return entry
    }

    private fun place(s: Session) {
        val host = s.activity.window.decorView
        dispatchInsets(host)
        if (s.dialog == null) {
            repeat(3) {
                host.measure(exactly(W), exactly(H))
                host.layout(0, 0, W, H)
            }
            s.originX = 0
            s.originY = 0
        } else {
            repeat(2) {
                host.measure(exactly(W), exactly(H))
                host.layout(0, 0, W, H)
            }
            val sheet = s.root
            val margin = 24
            sheet.measure(exactly(W - margin * 2), atMost(H))
            val sh = sheet.measuredHeight.coerceIn(1, H - 24)
            sheet.layout(0, 0, W - margin * 2, sh)
            s.originX = margin
            s.originY = H - sh
        }
        idle()
    }

    private fun dispatchInsets(root: View) {
        runCatching {
            ViewCompat.dispatchApplyWindowInsets(
                root,
                WindowInsetsCompat.Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.systemBars(),
                        Insets.of(0, STATUS_BAR_PX, 0, NAV_BAR_PX)
                    )
                    .build()
            )
        }
    }

    private fun drawSession(s: Session): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF0A1016.toInt())
        val host = s.activity.window.decorView
        host.draw(canvas)
        if (s.dialog != null) {
            canvas.drawColor(0x8F000000.toInt())
            canvas.save()
            canvas.translate(s.originX.toFloat(), s.originY.toFloat())
            val clip = canvas.save()
            canvas.clipRect(0, 0, s.root.width, s.root.height)
            s.root.draw(canvas)
            canvas.restoreToCount(clip)
            canvas.restore()
        }
        return bmp
    }

    private fun save(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun drawOverlay(canvas: Canvas, buttons: List<Btn>) {
        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.RED
        }
        val fill = Paint().apply { style = Paint.Style.FILL; color = Color.RED }
        val text = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            isAntiAlias = true
            isFakeBoldText = true
        }
        for (b in buttons) {
            if (b.bounds.width() <= 0 || b.bounds.height() <= 0) continue
            canvas.drawRect(b.bounds, stroke)
            val label = b.n.toString()
            val w = text.measureText(label) + 12f
            canvas.drawRect(
                b.bounds.left.toFloat(), b.bounds.top.toFloat(),
                b.bounds.left + w, b.bounds.top + 30f, fill
            )
            canvas.drawText(label, b.bounds.left + 6f, b.bounds.top + 23f, text)
        }
        // The dispatched status-bar boundary: anything drawn above this line sits under the bar.
        val line = Paint().apply {
            color = 0xFF00E5FF.toInt()
            strokeWidth = 2f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        canvas.drawLine(0f, STATUS_BAR_PX.toFloat(), W.toFloat(), STATUS_BAR_PX.toFloat(), line)
    }

    // ---------------------------------------------------------------- view collection

    private fun enumerate(s: Session): List<Btn> {
        val out = ArrayList<Btn>()
        eachClickable(s) { v, idName, label, cls, b, nth, idNth ->
            val key = "$idName|$label|$cls"
            val destructive = DESTRUCTIVE.containsMatchIn(idName) ||
                DESTRUCTIVE.containsMatchIn(label)
            out += Btn(
                out.size + 1, idName, label, cls, Rect(b), v.isEnabled,
                v.isClickable, v.isFocusable, destructive, key, nth, idNth
            )
        }
        return out
    }

    /** Walks the visible clickable/focusable views with their bitmap bounds and duplicate index. */
    private fun eachClickable(
        s: Session,
        onEach: (View, String, String, String, Rect, Int, Int) -> Unit
    ) {
        val seen = HashMap<String, Int>()
        val seenIds = HashMap<String, Int>()
        fun walk(v: View) {
            if (v.isShown && (v.isClickable || v.isFocusable)) {
                val visible = Rect()
                if (v.getGlobalVisibleRect(visible) && !visible.isEmpty) {
                    val b = boundsIn(s.root, v)
                    b.offset(s.originX, s.originY)
                    if (b.right > 0 && b.bottom > 0 && b.left < W && b.top < H) {
                        b.left = b.left.coerceIn(0, W)
                        b.right = b.right.coerceIn(0, W)
                        b.top = b.top.coerceIn(0, H)
                        b.bottom = b.bottom.coerceIn(0, H)
                        val idName = idName(v)
                        val label = label(v)
                        val cls = v.javaClass.simpleName
                        val key = "$idName|$label|$cls"
                        val nth = seen.getOrDefault(key, 0)
                        seen[key] = nth + 1
                        val idNth = if (idName.isEmpty()) 0 else seenIds.getOrDefault(idName, 0)
                        if (idName.isNotEmpty()) seenIds[idName] = idNth + 1
                        onEach(v, idName, label, cls, b, nth, idNth)
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(s.root)
    }

    private fun findView(s: Session, matchKey: String, nth: Int, idName: String, idNth: Int): View? {
        var found: View? = null
        var byId: View? = null
        eachClickable(s) { v, id, label, cls, _, n, iN ->
            if (found == null && n == nth && "$id|$label|$cls" == matchKey) found = v
            if (byId == null && idName.isNotEmpty() && id == idName && iN == idNth) byId = v
        }
        return found ?: byId
    }

    private fun boundsIn(root: View, v: View): Rect {
        var x = 0
        var y = 0
        var c: View? = v
        var reached = false
        while (c != null) {
            x += c.left
            y += c.top
            if (c === root) { reached = true; break }
            c = c.parent as? View
        }
        return if (reached) Rect(x, y, x + v.width, y + v.height)
        else Rect(0, 0, v.width, v.height)
    }

    private fun idName(v: View): String =
        if (v.id == View.NO_ID) "" else runCatching {
            v.resources.getResourceEntryName(v.id)
        }.getOrDefault("#${v.id}")

    private fun label(v: View): String {
        val own = when (v) {
            is TextView -> v.text?.toString()?.trim().orEmpty()
            else -> v.contentDescription?.toString()?.trim().orEmpty()
        }
        if (own.isNotEmpty()) return own.take(80)
        val d = v.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotEmpty()) return d.take(80)
        // A row container carries its label in a child (sometimes a child that the dialog has
        // not marked shown yet while the sheet is still settling).
        fun firstText(x: View): String? {
            if (x !== v) {
                if (x is TextView && x.text?.toString()?.isNotBlank() == true) return x.text.toString().trim()
                if (x is ViewGroup) {
                    for (i in 0 until x.childCount) firstText(x.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        val child = firstText(v)
        if (!child.isNullOrBlank()) return child.take(80)
        val tag = v.tag?.toString().orEmpty()
        if (tag.isNotBlank() && tag.length < 40 && !tag.contains(":")) return tag
        return ""
    }

    private fun topText(s: Session): String {
        var best: String = ""
        var bestTop = Int.MAX_VALUE
        fun walk(v: View) {
            if (v.isShown && v is TextView) {
                val t = v.text?.toString()?.trim().orEmpty()
                if (t.length > 1) {
                    val y = boundsIn(s.root, v).top + s.originY
                    if (y < 420 && y < bestTop && t.length < 60) {
                        best = t
                        bestTop = y
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(s.root)
        return best
    }

    private fun contentTop(s: Session): Int {
        var min = Int.MAX_VALUE
        fun walk(v: View) {
            if (v.isShown && v is TextView) {
                val t = v.text?.toString()?.trim().orEmpty()
                if (t.length > 1) {
                    val y = boundsIn(s.root, v).top + s.originY
                    if (y in 0 until min && v.height > 0) min = y
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(s.root)
        return if (min == Int.MAX_VALUE) -1 else min
    }

    private fun notRenderable(key: String, title: String, path: String, scale: Float, t: Throwable) {
        val raw = "${t.javaClass.simpleName}: ${t.message ?: "no message"}".take(300)
        val reason = if (raw.contains("destroyed already")) {
            "The screen closes itself at start in the Free fixture (Pro-only screen: it opens the Pro offer). See pro_screen for where the tap lands."
        } else raw
        val idx = indexOf.getOrPut(key) { nextIndex++ }
        screens += ScreenEntry(
            idx, key, title, path, String.format(Locale.ROOT, "%.1f", scale),
            "not-renderable", null, "", "", "", -1, null, emptyList()
        ).also { it.notes += reason }
        notes += "$key not rendered at $scale: $reason"
    }

    // ---------------------------------------------------------------- navigation

    private inner class SheetPlan(
        val parent: ScreenSpec,
        val opener: Btn?,
        val dialog: Dialog?,
        val effect: String,
        val auto: Boolean = false
    )

    private fun navigateBase(spec: ScreenSpec) {
        if (navBudget <= 0) return
        resetInvariants(1.0f)
        var s: Session = try {
            val fresh = spec.open()
            waitUntil(4000) { spec.ready(fresh.activity) }
            place(fresh)
            fresh
        } catch (_: Throwable) {
            return
        }
        var sig = signature(s.activity)
        var clean = true
        val discovered = ArrayList<SheetPlan>()
        try {
            val buttons = enumerate(s)
            val autoSheet = latestDialog()
            if (s.dialog == null && autoSheet != null) {
                // The screen starts with a sheet (ID card capture, QR fallback, Vault setup,
                // LockTools, business card). Its own buttons are covered; the sheet is pressed.
                buttons.forEach {
                    setEffect(
                        spec.key, it.n,
                        "covered by the sheet this screen opens with (not pressed)", dead = false
                    )
                }
                discovered += SheetPlan(spec, null, autoSheet, "opens with this sheet", auto = true)
                notes += "${spec.key}: buttons behind the sheet it opens with were not pressed"
            } else {
                for (btn in buttons) {
                    if (!btn.clickable) continue
                    if (navBudget <= 0) { navTruncated = true; break }
                    if (btn.destructive) {
                        setEffect(spec.key, btn.n, "destructive, not pressed", dead = false)
                        continue
                    }
                    if (!btn.enabled) {
                        setEffect(spec.key, btn.n, "disabled, not pressed", dead = false)
                        continue
                    }
                    if (!(clean && !s.activity.isFinishing &&
                            latestDialog() == null && signature(s.activity) == sig)
                    ) {
                        s.close()
                        s = try {
                            resetInvariants(1.0f)
                            val fresh = spec.open()
                            waitUntil(4000) { spec.ready(fresh.activity) }
                            place(fresh)
                            fresh
                        } catch (t: Throwable) {
                            setEffect(spec.key, btn.n, "could not restore the screen: ${t.message}", dead = false)
                            clean = false
                            continue
                        }
                        sig = signature(s.activity)
                        clean = true
                    }
                    navBudget--
                    val target = findButton(s, btn)
                    if (target == null) {
                        setEffect(spec.key, btn.n, "button not found after restore", dead = false)
                        clean = false
                        continue
                    }
                    val res = press(s, target)
                    setEffect(spec.key, btn.n, res.text, res.dead)
                    if (res.dialog != null) {
                        if (subScreenCount < MAX_SUB_SCREENS) {
                            discovered += SheetPlan(spec, btn, res.dialog, res.text)
                        }
                        runCatching { res.dialog.dismiss() }
                        clean = false
                    } else {
                        // A sheet can close itself on a row press; its Session root is then dead,
                        // so only an activity screen may be reused without a relaunch.
                        clean = s.dialog == null && !res.changed && !s.activity.isFinishing
                        sig = signature(s.activity)
                    }
                }
            }
        } finally {
            runCatching { s.close() }
        }

        // The sheets opened from this screen: their own entry plus one level of buttons.
        for (plan in discovered) {
            if (subScreenCount >= MAX_SUB_SCREENS || navBudget <= 0) break
            runCatching { registerAndPress(plan) }
        }
    }

    private fun registerAndPress(plan: SheetPlan) {
        subScreenCount++
        val baseLabel = plan.opener?.label?.ifBlank { plan.opener.idName }
            ?: plan.dialog?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            ?: "sheet"
        var key = "${plan.parent.key}__${slug(baseLabel)}"
        while (!usedSubKeys.add(key)) key += "_2"
        val title = "Sheet: $baseLabel"
        val path = "${plan.parent.path} > $baseLabel"
        val opener = {
            resetInvariants(1.0f)
            val host = plan.parent.open()
            if (plan.opener == null) {
                idle()
                waitUntil(4000) { latestDialog() != null }
                val d = latestDialog() ?: run { host.close(); error("the sheet did not reopen") }
                sheetSession(host, d)
            } else {
                val v = findButton(host, plan.opener)
                if (v == null) {
                    host.close()
                    error("the sheet's opener vanished")
                }
                v.performClick()
                idle()
                waitUntil(4000) { latestDialog() != null }
                val d = latestDialog() ?: run { host.close(); error("the sheet did not reopen") }
                sheetSession(host, d)
            }
        }
        val entry = try {
            val sheet = opener()
            try {
                val e = renderAndCollect(key, title, path, 1.0f, plan.parent.key, sheet)
                e.notes += if (plan.auto) {
                    "opened automatically when ${plan.parent.key} starts"
                } else {
                    "opened by '${plan.opener?.label?.ifBlank { plan.opener.idName }}' on ${plan.parent.key}: ${plan.effect}"
                }
                e
            } finally {
                sheet.close()
            }
        } catch (t: Throwable) {
            notes += "sheet $key not rendered: ${t.message}"
            return
        }
        screens += entry

        // One more level: press the sheet's own buttons on a freshly reopened sheet.
        for (btn in entry.raw) {
            val info = entry.buttons.getOrNull(btn.n - 1) ?: continue
            if (subBudget <= 0) { navTruncated = true; break }
            if (btn.destructive) {
                info.put("effect", "destructive, not pressed")
                continue
            }
            if (!btn.enabled) {
                info.put("effect", "disabled, not pressed")
                continue
            }
            if (!btn.clickable) continue
            val s2 = try {
                opener()
            } catch (t: Throwable) {
                info.put("effect", "could not reopen the sheet: ${t.message}")
                continue
            }
            try {
                place(s2)
                val target = findView(s2, btn.matchKey, btn.nth, btn.idName, btn.idNth)
                if (target == null) {
                    info.put("effect", "button not found after reopen")
                    continue
                }
                subBudget--
                val res = press(s2, target)
                info.put("effect", res.text)
                info.put("dead", res.dead)
                if (res.dialog != null) {
                    runCatching { res.dialog.dismiss() }
                    info.put("effect", res.text + " (a sheet; not expanded further)")
                }
            } finally {
                s2.close()
            }
        }
    }

    private fun findButton(s: Session, btn: Btn): View? =
        findView(s, btn.matchKey, btn.nth, btn.idName, btn.idNth)

    private class PressResult(
        val text: String,
        val dialog: Dialog?,
        val dead: Boolean,
        val changed: Boolean
    )

    private fun press(s: Session, target: View): PressResult {
        val a = s.activity
        val beforeDialog = latestDialog()
        val beforeFocus = a.currentFocus
        val beforeText = signature(a)
        val beforeSnack = snackbar(a)
        drainStarted(a)
        runCatching { ShadowToast.reset() }
        runCatching { target.performClick() }
        idle()
        waitUntil(1200) {
            a.isFinishing || latestDialog() !== beforeDialog ||
                (snackbar(a) != null && snackbar(a) != beforeSnack) ||
                ShadowToast.getTextOfLatestToast() != null ||
                a.currentFocus !== beforeFocus
        }
        val started = runCatching { shadowOf(a).nextStartedActivity }.getOrNull()
        val afterDialog = latestDialog()
        val toast = runCatching { ShadowToast.getTextOfLatestToast() }.getOrNull()
        val snack = snackbar(a)?.takeIf { it != beforeSnack }
        val changed = signature(a) != beforeText

        val parts = ArrayList<String>()
        var stateNoop = false
        if (started != null) parts += describe(started)
        if (afterDialog != null && afterDialog !== beforeDialog) {
            val t = afterDialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            parts += if (!t.isNullOrBlank()) "sheet '$t'" else "sheet opens"
        }
        if (toast != null) parts += "toast '$toast'"
        if (snack != null) parts += "snackbar '$snack'"
        if (beforeDialog != null && afterDialog == null) parts += "closes the sheet"
        if (started == null && afterDialog === beforeDialog && toast == null && snack == null) {
            if (a.isFinishing) parts += "closes this screen"
            else if (isInput(target)) {
                parts += "focuses a text field (keyboard not rendered)"
                stateNoop = true
            } else if (target.isSelected) {
                parts += "already selected (no change)"
                stateNoop = true
            } else if (labelOf(target).contains("More options", ignoreCase = true)) {
                parts += "opens an overflow menu (popup not rendered by Robolectric)"
                stateNoop = true
            } else if (isCurrentTab(a, idName(target))) {
                parts += "already on this tab (no change)"
                stateNoop = true
            } else if (a.currentFocus !== beforeFocus || a.currentFocus is EditText) {
                parts += "focuses an input (keyboard not rendered)"
            }
        }
        if (!stateNoop && changed && a.isFinishing.not() && parts.none { it == "visible screen change" }) {
            parts += "visible screen change"
        }
        if (parts.isEmpty()) {
            parts += if (target.isFocusable && !target.isClickable) {
                "no visible effect (focusable only)"
            } else {
                "no visible effect (dead-button candidate)"
            }
        }
        val dead = parts.all { it.startsWith("no visible effect") }
        return PressResult(parts.joinToString("; "), afterDialog?.takeIf { it !== beforeDialog }, dead, changed)
    }

    private fun isInput(v: View): Boolean {
        if (v is EditText) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (isInput(v.getChildAt(i))) return true
        }
        return false
    }

    private fun labelOf(v: View): String = label(v)

    private fun isCurrentTab(a: Activity, id: String): Boolean = when (id) {
        "swNavLibrary" -> a.findViewById<View>(R.id.swDocList)?.isShown == true
        "swNavTools" -> a.findViewById<View>(R.id.swToolsSearch)?.isShown == true
        "swNavVault" -> a.findViewById<View>(R.id.swVaultNote)?.isShown == true
        "swNavMe" -> a.findViewById<View>(R.id.swMeList)?.isShown == true
        else -> false
    }

    private fun describe(intent: Intent): String {
        val target = intent.component?.className?.substringAfterLast('.')
            ?: intent.action
            ?: "intent"
        val extras = ArrayList<String>()
        runCatching {
            val b = intent.extras ?: return@runCatching
            b.keySet().sorted().forEach { k ->
                val v = b.get(k)
                if (v is String || v is Boolean || v is Int || v is Long || v is java.util.ArrayList<*>) {
                    extras += "$k=${v.toString().take(40)}"
                }
            }
        }
        return if (extras.isEmpty()) "opens $target"
        else "opens $target (${extras.take(4).joinToString(", ")})"
    }

    private fun signature(activity: Activity): String = signature(activity.window.decorView)

    private fun signature(root: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            if (v.isShown) {
                sb.append(v.javaClass.simpleName).append('#').append(v.id).append('|')
                if (v is TextView) sb.append(v.text).append('|')
                if (v is CompoundButton) sb.append(v.isChecked).append('|')
                if (v is ImageView) sb.append(v.drawable?.javaClass?.simpleName).append('|')
                sb.append(if (v.isEnabled) 'e' else 'd')
                sb.append('\n')
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun snackbar(a: Activity): String? =
        runCatching {
            (a.window.decorView.findViewById<View>(
                com.google.android.material.R.id.snackbar_text
            ) as? TextView)?.text?.toString()
        }.getOrNull()

    private fun setEffect(key: String, n: Int, effect: String, dead: Boolean) {
        screens.filter { it.key == key && it.scale == "1.0" }.forEach { e ->
            e.buttons.getOrNull(n - 1)?.let { b ->
                b.put("effect", effect)
                b.put("dead", dead)
            }
        }
    }

    // ---------------------------------------------------------------- output

    private fun writeJson(specs: List<ScreenSpec>) {
        val root = JSONObject()
        root.put("generated", java.time.Instant.now().toString())
        root.put("qualifiers", "w360dp-h800dp-xxhdpi")
        root.put("language", "en")
        root.put("pro", false)
        root.put("device", JSONObject().apply {
            put("widthPx", W); put("heightPx", H); put("density", 3)
            put("statusBarPx", STATUS_BAR_PX); put("navBarPx", NAV_BAR_PX)
        })
        root.put("scales", JSONArray(SCALES.map { String.format(Locale.ROOT, "%.1f", it) }))
        root.put("navTruncated", navTruncated)
        root.put("navBudgetLeft", navBudget)
        root.put("subBudgetLeft", subBudget)
        root.put("notes", JSONArray(notes))
        root.put("screens", JSONArray(screens.sortedBy { it.index }.map { e ->
            JSONObject().apply {
                put("index", e.index)
                put("key", e.key)
                put("title", e.title)
                put("path", e.path)
                put("scale", e.scale)
                put("kind", e.kind)
                put("parent", e.parent ?: JSONObject.NULL)
                put("png", e.png)
                put("overlay", e.overlay)
                put("topText", e.topText)
                put("contentTopPx", e.contentTop)
                put("underStatusBar", e.kind == "activity" && e.contentTop in 0 until STATUS_BAR_PX)
                put("dialogTitle", e.dialogTitle ?: JSONObject.NULL)
                put("notes", JSONArray(e.notes))
                put("buttons", JSONArray(e.buttons))
            }
        }))

        val museum = listOf(
            Triple(
                "ML Kit document scanner", "camera/Play-services UI",
                "Cannot be drawn by Robolectric: Google's scanner surface needs Play services and a camera. Reached from Create > Scan, ID card, Passport, Book pages and Review (add pages)."
            ),
            Triple(
                "Android photo picker", "system UI",
                "The photo picker is a system process (PickVisualMedia/GetMultipleContents). Passport and several tools open it immediately; the local screen behind it is the one rendered here."
            ),
            Triple(
                "Google code scanner UI", "camera/Play-services UI",
                "The QR code tile opens Play services' code scanner before QrActivity. QrActivity's fallback sheet is rendered instead."
            ),
            Triple(
                "SAF save/open picker", "system UI",
                "CreateDocument/OpenDocument chooser (Save to Documents, Backup file, restore, recovery-code file)."
            ),
            Triple(
                "Share chooser (ACTION_SEND)", "system UI",
                "System chooser; the atlas records the chooser intent instead of a picture."
            ),
            Triple(
                "Camera capture inside Review", "camera UI",
                "ML Kit scanner; the review screen itself is rendered from a staging folder of fictional pages."
            )
        )
        root.put("notRenderable", JSONArray(museum.map { (name, kind, why) ->
            JSONObject().apply { put("screen", name); put("kind", kind); put("reason", why) }
        }))
        root.put("startedFrom", JSONArray(specs.map { it.key }))
        // A sliced run writes its own file; merge_atlas.py joins them into atlas.json.
        val name = if (slicing) "atlas_slice_${sliceRange().first}.json" else "atlas.json"
        File(outDir, name).writeText(root.toString(2))
    }

    private fun slug(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "sheet" }

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
    private fun atMost(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.AT_MOST)
}
