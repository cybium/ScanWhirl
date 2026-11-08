package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.OcrEditorActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * test26 T1.2 — the readability guard (anti-clipping rule R10).
 *
 * test25 shipped 209 green tests *and* clipped button labels, a truncated
 * search hint and a cut-off Share button: tests asserted that views *exist*,
 * not that their text is *readable*. This suite walks every visible TextView
 * on a screen and reports anything a person could not read:
 *
 *  1. the view has a laid-out [TextView.getLayout];
 *  2. no line is wider than the space between the view's total paddings;
 *  3. layout + totalPaddingTop/Bottom fit inside the view's height;
 *  4. no line is ellipsized (text silently cut with "…");
 *  5. an EditText's hint fits between its paddings;
 *  6. the view's right/bottom edge stays inside its parent and the screen
 *     root — unless a scrolling ancestor intentionally crops it.
 *
 * Every problem on every screen at both font scales is collected into one
 * failure message, so one run lists every offender.
 *
 * Rig: `w320dp-h640dp-xhdpi` (the narrowest realistic phone) × font scale
 * 1.0 and 1.3 (rule R3), with synthetic 48dp status/navigation insets
 * dispatched on the screen root — the same insets a real device sends after
 * T1.1's edge-to-edge wiring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class TextFitTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private val problems = mutableListOf<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        problems.clear()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        // Never leak a 1.3 font scale into other test classes.
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // --- fixtures (local copies of the Phase5ScreensTest patterns) ----------

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://textfit/smoke_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(310, 444, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 1): String = runBlocking {
        val uris = (0 until pages).map { contentUri(jpegBytes()) }
        repo.saveScan(uris, null, name).id
    }

    private fun pollUntil(timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    // --- the rig ------------------------------------------------------------

    /** Runs [block] at font scale 1.0 and 1.3 (rule R3). */
    private fun atBothFontScales(block: () -> Unit) {
        for (scale in floatArrayOf(1.0f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            block()
        }
    }

    /**
     * Layouts the launched screen root exactly as a 320dp×640dp device would:
     * synthetic system-bar insets first (the screen pads itself in response —
     * T1.1), then two measure/layout passes so padding-driven remeasures settle.
     */
    private fun checkScreen(activity: Activity, name: String, afterLayout: (View) -> Unit = {}) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: content
        checkView(activity, root, name, afterLayout)
    }

    /**
     * The measure/walk half of [checkScreen], reusable for views that live in
     * another window (T3.6: the Tools sheet's dialog decor).
     */
    private fun checkView(activity: Activity, root: View, name: String, afterLayout: (View) -> Unit = {}) {
        val scale = RuntimeEnvironment.getFontScale()
        val label = "$name @ scale $scale"
        val config = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val w = Math.round(config.screenWidthDp * density)
        val h = Math.round(config.screenHeightDp * density)
        val bar = Math.round(48f * density) // synthetic status/nav bars for the rig

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bar))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)

        val pass = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY) to
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(pass.first, pass.second)
            root.layout(0, 0, w, h)
        }
        afterLayout(root)
        collectProblems(root, label)
        // Keep the root around for as long as the scenario is open only.
    }

    private fun collectProblems(root: View, label: String) {
        // Views under a scrolling ancestor are cropped on purpose — skipping
        // them keeps below-the-fold list rows from looking like defects.
        fun inScrollChain(v: View): Boolean {
            var p = v.parent as? View
            while (p != null && p != root) {
                val n = p.javaClass.simpleName
                if (n.contains("Scroll") || n.contains("ViewPager")) return true
                p = p.parent as? View
            }
            return false
        }

        /** Bottom/right edge of [v] relative to the screen root. */
        fun absBottom(v: View): Int {
            var b = v.bottom
            var p = v.parent as? View
            while (p != null && p != root) {
                b += p.top
                p = p.parent as? View
            }
            return b
        }

        fun absRight(v: View): Int {
            var r = v.right
            var p = v.parent as? View
            while (p != null && p != root) {
                r += p.left
                p = p.parent as? View
            }
            return r
        }

        fun walk(v: View) {
            if (v.isShown && v.width > 0 && v.height > 0) {
                if (v is TextView) collectTextView(v, label) { msg -> problems += msg }
                val scroll = inScrollChain(v)
                if (!scroll) {
                    val parent = v.parent as? View
                    if (parent != null && parent !== root) {
                        if (v.right > parent.width) {
                            problems +=
                                "$label: ${describe(v)} right edge ${v.right}px exceeds parent " +
                                "width ${parent.width}px (${parent.javaClass.simpleName})"
                        }
                        if (v.bottom > parent.height) {
                            problems +=
                                "$label: ${describe(v)} bottom edge ${v.bottom}px exceeds parent " +
                                "height ${parent.height}px (${parent.javaClass.simpleName})"
                        }
                    }
                    if (absRight(v) > root.width) {
                        problems +=
                            "$label: ${describe(v)} right edge ${absRight(v)}px exceeds screen " +
                            "width ${root.width}px"
                    }
                    if (absBottom(v) > root.height) {
                        problems +=
                            "$label: ${describe(v)} bottom edge ${absBottom(v)}px exceeds screen " +
                            "height ${root.height}px"
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }

        walk(root)
    }

    private fun describe(v: View): String {
        // Any view may land here when it overflows its parent (not just
        // TextViews) — a blind cast masked real problems with a crash (T3.11).
        val tv = v as? TextView
        val text = tv?.text?.toString().orEmpty().ifEmpty { tv?.hint?.toString().orEmpty() }
        val shown = if (text.length > 40) text.take(40) + "…" else text
        return "[${v.javaClass.simpleName} id=${idName(v)} \"$shown\"]"
    }

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "no-id" else v.resources.getResourceEntryName(v.id)
    } catch (_: Exception) {
        "0x${Integer.toHexString(v.id)}"
    }

    private fun collectTextView(v: TextView, label: String, report: (String) -> Unit) {
        val shown = v.text?.toString().orEmpty().ifEmpty { v.hint?.toString().orEmpty() }
        if (shown.isEmpty()) return

        // 5. an EditText's hint must fit (the test25 "Search name or scanned
        //    tex" complaint) — check even when text is present and empty.
        if (v is EditText && !v.hint.isNullOrEmpty()) {
            val need = v.paint.measureText(v.hint.toString())
            val avail = v.width - v.totalPaddingLeft - v.totalPaddingRight
            if (need > avail + 0.5f) {
                report(
                    "$label: ${describe(v)} hint \"${v.hint}\" needs ${need.toInt()}px but only " +
                    "${avail}px are available (view ${v.width}px wide)"
                )
            }
        }

        val layout = v.layout ?: run {
            report("$label: ${describe(v)} has text but no Layout (never measured)")
            return
        }
        val availW = v.width - v.totalPaddingLeft - v.totalPaddingRight

        // 2. no line wider than the space between the paddings.
        // 4. no ellipsized (silently truncated) line.
        for (line in 0 until layout.lineCount) {
            val lineWidth = layout.getLineWidth(line)
            if (lineWidth > availW + 0.5f) {
                report(
                    "$label: ${describe(v)} line $line is ${lineWidth.toInt()}px wide but only " +
                    "${availW}px fit between paddings"
                )
            }
            val ellipsized = layout.getEllipsisCount(line)
            if (ellipsized > 0) {
                report(
                    "$label: ${describe(v)} line $line is ellipsized ($ellipsized chars cut)"
                )
            }
        }

        // 3. layout height plus total paddings must fit the view. A MaterialButton
        //    with iconGravity=top puts its icon inside compound padding, so take
        //    the larger of total/compound padding to count it.
        val top = maxOf(v.totalPaddingTop, v.compoundPaddingTop)
        val bottom = maxOf(v.totalPaddingBottom, v.compoundPaddingBottom)
        val needed = layout.height + top + bottom
        if (needed > v.height + 0.5f) {
            report(
                "$label: ${describe(v)} needs ${needed}px (layout ${layout.height}px + pads " +
                "$top/$bottom) but the view is ${v.height}px tall"
            )
        }
    }

    private fun assertNoProblems() {
        val message = buildString {
            appendLine("${problems.size} text-fit problem(s) at w320dp font scale 1.0 and 1.3:")
            problems.forEach { appendLine(" - $it") }
        }
        assertTrue(message, problems.isEmpty())
    }

    /**
     * T3.11 (complaint #16): a tile label may wrap onto a second line but
     * must never break **inside a word** (`Compres` / `s` happened on device
     * while every check above stayed green — ellipsis/height checks cannot
     * see a mid-word line break). Every `tile_*` label's longest word must
     * fit between the view's paddings, measured with the label's own TextPaint
     * at the current font scale. Zero width is reported, never passed.
     */
    private fun collectTileWordProblems(root: View, name: String) {
        val scale = RuntimeEnvironment.getFontScale()
        val label = "$name words @ scale $scale"
        fun walk(v: View) {
            if (v is TextView && v.id != View.NO_ID) {
                val entry = try {
                    v.resources.getResourceEntryName(v.id)
                } catch (_: Exception) {
                    ""
                }
                // All tile labels share the id `tileLabel` (the container ids
                // are tile_edit, tile_ocr … — matching those on a TextView
                // never fires; the forced-narrow red-proof caught exactly that).
                if (entry == "tileLabel" && v.isShown) {
                    if (v.width == 0) {
                        problems += "$label: ${describe(v)} has zero width — longest-word check would be vacuous"
                    } else {
                        val avail = v.width - v.totalPaddingLeft - v.totalPaddingRight
                        val longest = v.text.toString()
                            .split(Regex("\\s+")).filter { it.isNotEmpty() }
                            .maxOfOrNull { v.paint.measureText(it) } ?: 0f
                        if (longest > avail + 0.5f) {
                            problems +=
                                "$label: ${describe(v)} longest word needs ${longest.toInt()}px but only " +
                                "${avail}px fit between paddings (word would break across lines)"
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    // --- screens ------------------------------------------------------------

    @Test
    fun `home screen text fits`() {
        atBothFontScales {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { checkScreen(it, "Home") }
            }
        }
        assertNoProblems()
    }

    /**
     * T3.6: the Tools sheet lives in its own dialog window, so it gets its own
     * decor walk. Owner-approved to join the known-red list if it fails for a
     * genuine fit reason (never `@Ignore`).
     */
    @Test
    fun `tools sheet text fits`() {
        atBothFontScales {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.btnTools).performClick()
                    val sheet = ShadowDialog.getLatestDialog()
                    assertNotNull("tools sheet should open", sheet)
                    val decor = sheet!!.window!!.decorView
                    checkView(activity, decor, "ToolsSheet")
                    // T3.11: no tile label may break a word across lines.
                    collectTileWordProblems(decor, "ToolsSheet")
                    sheet.dismiss()
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `document screen text fits`() {
        val id = seedDoc("Text fit document")
        atBothFontScales {
            ActivityScenario.launch<DocumentViewerActivity>(
                DocumentViewerActivity.intent(app, id)
            ).use { scenario ->
                scenario.onActivity { activity ->
                    // reloadDocument() fills title/page count off the main thread.
                    pollUntil {
                        activity.findViewById<TextView>(R.id.pageCount)
                            ?.text?.isNotEmpty() == true
                    }
                    checkScreen(activity, "Document")
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `page editor text fits`() {
        val id = seedDoc("Text fit pages", pages = 2)
        atBothFontScales {
            ActivityScenario.launch<PageViewerActivity>(
                PageViewerActivity.intent(app, id, 0)
            ).use { scenario ->
                scenario.onActivity { checkScreen(it, "PageEditor") }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `scan review text fits`() {
        val staging = File(app.cacheDir, "textfit_${System.nanoTime()}").apply { mkdirs() }
        File(staging, "page_001.jpg").writeBytes(jpegBytes())
        atBothFontScales {
            ActivityScenario.launch<ScanReviewActivity>(
                ScanReviewActivity.intent(app, staging.path, null, "Text fit scan")
            ).use { scenario ->
                scenario.onActivity { checkScreen(it, "ScanReview") }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `settings screen text fits`() {
        atBothFontScales {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { checkScreen(it, "Settings") }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `ocr editor text fits`() {
        val docId = runBlocking {
            repo.saveScan(listOf(contentUri(jpegBytes())), null, "OCR fit doc").id
        }
        runBlocking {
            repo.buildOcr(docId) { _ ->
                "This is some OCR text that needs editing before copying or saving.\nIt has multiple lines and paragraphs.\n\nA second paragraph here."
            }
        }
        atBothFontScales {
            ActivityScenario.launch<OcrEditorActivity>(
                android.content.Intent(app, OcrEditorActivity::class.java).apply {
                    putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
                }
            ).use { scenario ->
                scenario.onActivity { checkScreen(it, "OcrEditor") }
            }
        }
        assertNoProblems()
    }
}
