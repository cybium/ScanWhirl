package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * F1-FIT (atlas 2026-11-20, screenshots on 7334c41d): the five viewer/dock
 * screens must stay readable at 100 %, 130 % and 200 % text on a 360 dp and a
 * 320 dp phone, in English **and** Hindi.
 *
 * Red evidence this class produced against the base (before the fix):
 *  - Page 024: dock labels broke inside the word — "Rotat / e", "Delet / e";
 *  - Reader 023: "Shar" was clipped and the pill ran off both screen edges;
 *    the page counter was a 40 %-black pill (grey-on-white, 1.9:1);
 *  - Saved 025: the CTA broke into "Save to / Downlo / ads";
 *  - Merge 017: "Mer / ge";
 *  - Review 028: the category rail cut the next chip to a sliver with no
 *    fading edge, and the fifth tool icon was clipped with no label.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ViewerDockFitTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultPagesFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private lateinit var previewFile: File
    private val problems = mutableListOf<String>()

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        defaultPagesFactory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages(3) }
        problems.clear()
        previewFile = File(app.cacheDir, "fit_preview.jpg").apply {
            if (!exists()) FileOutputStream(this).use { it.write(jpegBytes(240, 340)) }
        }
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h640dp-xhdpi")
        PdfViewerActivity.pagesFactory = defaultPagesFactory
    }

    // --- fixtures -----------------------------------------------------------

    private fun jpegBytes(w: Int = 240, h: Int = 340): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawColor(android.graphics.Color.RED)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://viewerfit/p_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun seedDoc(name: String, pages: Int = 2): String = kotlinx.coroutines.runBlocking {
        repo.saveScan((0 until pages).map { contentUri(jpegBytes()) }, null, name).id
    }

    private fun stagingDir(): File {
        val dir = File(app.cacheDir, "viewerfit_${System.nanoTime()}").apply { mkdirs() }
        File(dir, "page_001.jpg").writeBytes(jpegBytes())
        File(dir, "page_002.jpg").writeBytes(jpegBytes())
        return dir
    }

    // --- rig ----------------------------------------------------------------

    /** Every width × locale × font-scale the contract names: 360/320 dp, EN+HI, 100/130/200 %. */
    private fun eachConfig(block: (widthDp: Int, heightDp: Int, locale: String, scale: Float) -> Unit) {
        for (widthDp in intArrayOf(360, 320)) {
            for (locale in arrayOf("en-rUS", "hi")) {
                for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
                    RuntimeEnvironment.setQualifiers("$locale-w${widthDp}dp-h640dp-xhdpi")
                    RuntimeEnvironment.setFontScale(scale)
                    block(widthDp, 640, locale, scale)
                }
            }
        }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** A visible label that has text but was never laid out yet (async page load). */
    private fun hasUnlaidText(v: View): Boolean {
        if (v is android.widget.TextView && v.isShown) {
            val text = v.text?.toString().orEmpty()
            if (text.isNotEmpty() && v.width > 0 && v.layout == null) return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasUnlaidText(v.getChildAt(i))) return true
        }
        return false
    }

    /**
     * Lays the screen out at the configured size (synthetic 48 dp system bars,
     * the same insets a real device dispatches) and records every readability
     * problem found, naming the screen, width, locale and scale.
     */
    private fun checkScreen(
        activity: Activity,
        name: String,
        widthDp: Int,
        heightDp: Int,
        locale: String,
        scale: Float,
        barIds: List<Int>
    ) {
        settle()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: content
        val density = activity.resources.displayMetrics.density
        val w = Math.round(widthDp * density)
        val h = Math.round(heightDp * density)
        val bar = Math.round(48f * density)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        // Settle the main thread between passes: updateIndicator() and the page
        // loader post their text, and text set after the last measure would look
        // like "text but no Layout".
        repeat(2) {
            settle()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
        }
        var pass = 0
        while (pass < 4 && hasUnlaidText(root)) {
            settle()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
            pass++
        }
        settle()
        val bars = barIds.mapNotNull { id -> activity.findViewById<View>(id) }
        val label = "$name @ ${widthDp}dp $locale scale $scale"
        problems += TextFitRules.problems(root, label, bars)
    }

    private fun assertNoProblems() {
        if (problems.isEmpty()) return
        // The gate prints only the first line of a failure message, so the first
        // line names the dominant kinds (with counts); the full list follows.
        val kinds = problems
            .groupBy { p ->
                val body = p.substringAfter(": ", p)
                // Drop the "[View id=... \"text\"]" prefix so the kind is the reason.
                if (body.startsWith("[")) body.substringAfter("] ", body).take(60) else body.take(60)
            }
            .toList()
            .sortedByDescending { it.second.size }
        val head = kinds.take(4).joinToString(" || ") { "${it.second.size}x ${it.first}" }
        val message = buildString {
            appendLine("${problems.size} readability problem(s); kinds: $head")
            problems.forEach { appendLine(" - $it") }
        }
        assertTrue(message, problems.isEmpty())
    }

    // --- screens ------------------------------------------------------------

    @Test
    fun `page editor dock never breaks a label`() {
        val id = seedDoc("Fit pages")
        eachConfig { widthDp, heightDp, locale, scale ->
            ActivityScenario.launch<PageViewerActivity>(
                PageViewerActivity.intent(app, id, 0)
            ).use { scenario ->
                scenario.onActivity {
                    checkScreen(it, "PageEditor", widthDp, heightDp, locale, scale,
                        listOf(R.id.pageDock))
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `pdf reader dock never breaks or clips a label`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Fit reader")
        eachConfig { widthDp, heightDp, locale, scale ->
            ActivityScenario.launch<PdfViewerActivity>(
                PdfViewerActivity.intent(app, id, "Fit reader")
            ).use { scenario ->
                scenario.onActivity {
                    checkScreen(it, "Reader", widthDp, heightDp, locale, scale,
                        listOf(R.id.readerDockRoot))
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `export result actions and stats stay readable`() {
        eachConfig { widthDp, heightDp, locale, scale ->
            ActivityScenario.launch<ExportResultActivity>(
                ExportResultActivity.intent(
                    app,
                    outputPath = previewFile.path,
                    previewPath = previewFile.path,
                    mime = "image/jpeg",
                    displayName = "Fit export.jpg",
                    beforeBytes = 3L * 1024L * 1024L,
                    afterBytes = 420_000L,
                    width = 240,
                    height = 340,
                    title = "Compressed",
                    warning = null,
                    docId = null
                )
            ).use { scenario ->
                scenario.onActivity {
                    checkScreen(it, "Saved", widthDp, heightDp, locale, scale,
                        listOf(R.id.exportDock))
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `merge dock never breaks a label`() {
        eachConfig { widthDp, heightDp, locale, scale ->
            ActivityScenario.launch<MergePdfActivity>(
                MergePdfActivity.intent(app)
            ).use { scenario ->
                scenario.onActivity {
                    checkScreen(it, "Merge", widthDp, heightDp, locale, scale,
                        listOf(R.id.mergeDock))
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `scan review rail and tools stay readable`() {
        eachConfig { widthDp, heightDp, locale, scale ->
            val staging = stagingDir()
            ActivityScenario.launch<ScanReviewActivity>(
                ScanReviewActivity.intent(app, staging.path, null, "Fit review")
            ).use { scenario ->
                scenario.onActivity {
                    checkScreen(it, "Review", widthDp, heightDp, locale, scale,
                        listOf(R.id.saveDock))
                }
            }
        }
        assertNoProblems()
    }

    /** The dock reflows instead of squeezing: at 200 % a 320 dp phone needs two rows. */
    @Test
    fun `page editor dock reflows at 200 percent on a narrow phone`() {
        val id = seedDoc("Reflow pages")
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch<PageViewerActivity>(
            PageViewerActivity.intent(app, id, 0)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0) ?: content
                val density = activity.resources.displayMetrics.density
                val w = Math.round(320 * density)
                val h = Math.round(640 * density)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, w, h)
                val dock = activity.findViewById<ViewGroup>(R.id.pageDock)
                assertTrue(
                    "the dock must be a reflowing FlowRowLayout, was ${dock.javaClass.simpleName}",
                    dock is FlowRowLayout
                )
                assertTrue(
                    "at 200 % on 320 dp the four actions need more than one row, was " +
                        "${(dock as FlowRowLayout).rowCount}",
                    dock.rowCount >= 2
                )
            }
        }
    }
}
