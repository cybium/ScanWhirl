package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lane RS (owner screenshot run5 B2): the tool result is dumb and bloated. These are the RED
 * tests for the lead's exact redesign. Every lifecycle case uses ActivityController + destroy in
 * finally + @Test(timeout=30_000) because ActivityScenario.close() hung CI.
 */
abstract class ResultRedesignRsBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected val repo get() = ScanRepository(app)
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun clearProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    protected fun compressIntent(): android.content.Intent {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Original")
        val out = DocFixtures.saved(repo, app, pages = 1, name = "Class 12 marksheet (2)")
        return ToolResultActivity.intent(
            app, "compress", docIds = listOf(out),
            beforeBytes = 1_600_000L, receiptSourceId = source,
            sourceKept = true, outputIdentity = null
        )
    }

    protected fun visibleTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString().trim()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    protected fun layoutScreen(activity: ToolResultActivity) {
        val decor = activity.window!!.decorView
        val w = app.resources.displayMetrics.widthPixels
        val h = app.resources.displayMetrics.heightPixels
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        idle()
    }

    @Test(timeout = 30_000)
    fun `top bar title is the tool name not a sentence`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val titles = visibleTexts(controller.get().window!!.decorView)
            assertFalse(
                "the old sentence title is gone: $titles",
                titles.any { it.contains("Larger than the original", ignoreCase = true) }
            )
            assertTrue(
                "the top bar names the tool: $titles",
                titles.contains(app.getString(R.string.tool_compress))
            )
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `no sentence is repeated on screen`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val texts = visibleTexts(controller.get().window!!.decorView)
            val duplicates = texts.groupingBy { it }.eachCount().filter { it.value > 1 }
            assertTrue("repeated sentences: $duplicates", duplicates.isEmpty())
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `at most three text lines sit above the visual`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val activity = controller.get()
            layoutScreen(activity)
            val view = activity.findViewById<View>(R.id.ccCompare)
            assertNotNull("the result has one visual", view)
            val visualTop = IntArray(2).also { view.getLocationOnScreen(it) }[1]
            var count = 0
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v.id == R.id.rsTitle || v.id == R.id.rsRemoveNote) return
                if (v is TextView && !v.text.isNullOrBlank()) {
                    val bottom = IntArray(2).also { v.getLocationOnScreen(it) }[1] + v.height
                    if (bottom <= visualTop) count++
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.findViewById<View>(android.R.id.content))
            assertTrue("above the visual there are at most three lines, got $count", count <= 3)
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `Open Share and More are on screen and do not overlap`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val activity = controller.get()
            layoutScreen(activity)
            val ids = listOf(R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultMore)
            val views = ids.map { activity.findViewById<View>(it) }
            views.forEach { assertNotNull("action exists", it) }
            val boxes = views.map { v ->
                val loc = intArrayOf(0, 0)
                v!!.getLocationOnScreen(loc)
                loc[0] to loc[1]
            }
            val h = app.resources.displayMetrics.heightPixels
            boxes.indices.forEach { i ->
                val (x, y) = boxes[i]
                assertTrue("action $i is on screen: $x,$y", x >= 0 && y >= 0 && y + views[i]!!.height <= h)
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val a = boxes[i]; val b = boxes[j]
                val overlapX = a.first < b.first + views[j]!!.width && b.first < a.first + views[i]!!.width
                val overlapY = a.second < b.second + views[j]!!.height && b.second < a.second + views[i]!!.height
                assertFalse("actions $i and $j overlap", overlapX && overlapY)
            }
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a larger output is discarded and the original is untouched`() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Keep me")
        val out = DocFixtures.saved(repo, app, pages = 1, name = "Grew copy")
        val sourcePdf = requireNotNull(repo.getDocument(source)).pdfFile.readBytes()
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(
                ToolResultActivity::class.java,
                ToolResultActivity.intent(
                    app, "compress", docIds = listOf(out), beforeBytes = 100L,
                    receiptSourceId = source, compressGrew = true
                )
            )
            controller.setup()
            idle()
            assertFalse(
                "no new library document from a copy that grew",
                repo.getDocument(out) != null
            )
            assertTrue("the original stays", repo.getDocument(source) != null)
            org.junit.Assert.assertArrayEquals(
                "the original bytes are unchanged", sourcePdf,
                requireNotNull(repo.getDocument(source)).pdfFile.readBytes()
            )
            val texts = visibleTexts(controller.get().window!!.decorView)
            assertTrue(
                "the already-compact hero is shown: $texts",
                texts.any {
                    it.contains("Already compact", ignoreCase = true) ||
                        it.contains("पहले ही छोटी")
                }
            )
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `rounded equal sizes state the real byte delta`() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Original")
        val out = DocFixtures.saved(repo, app, pages = 1, name = "Rounded")
        requireNotNull(repo.getDocument(out)).pdfFile.writeBytes(ByteArray(1_590_000))
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(
                ToolResultActivity::class.java,
                ToolResultActivity.intent(
                    app, "compress", docIds = listOf(out), beforeBytes = 1_600_000L,
                    receiptSourceId = source
                )
            )
            controller.setup()
            idle()
            val activity = controller.get()
            assertEquals(
                "the hero still shows the two rounded sizes",
                app.getString(R.string.rs_size_hero, "1.6 MB", "1.6 MB"),
                activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
            )
            val chip = activity.findViewById<TextView>(R.id.rsChip).text.toString()
            assertTrue("the chip tells the truth: $chip", chip.contains("10 KB"))
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `remove new copy has a remove icon and states the original is kept`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val activity = controller.get()
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            idle()
            val remove = activity.findViewById<TextView>(R.id.toolResultUndo)
            assertNotNull("remove control exists", remove)
            assertNotNull("a remove icon, not an undo icon", remove.compoundDrawablesRelative[0])
            val note = activity.findViewById<TextView>(R.id.rsRemoveNote).text.toString()
            assertTrue("the original is explicitly kept: $note",
                note.contains("original", ignoreCase = true) || note.contains("मूल"))
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a state only tool shows no page image`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(
                ToolResultActivity::class.java,
                ToolResultActivity.intent(
                    app, "protect", docIds = listOf(DocFixtures.saved(repo, app, pages = 1, name = "Safe")),
                    compareOutcome = "Protected; no preview"
                )
            )
            controller.setup()
            idle()
            val activity = controller.get()
            // Only the page/preview images count; the back arrow is an ImageButton, not a page.
            listOf(
                R.id.toolResultPreview, R.id.ccBeforeImage, R.id.ccAfterImage
            ).forEach { id ->
                val v = activity.findViewById<View>(id)
                assertTrue("no page image for a state-only tool: $id",
                    v == null || v.visibility == View.GONE ||
                        (v is ImageView && v.drawable == null))
            }
            assertEquals("no comparison surface at all", View.GONE,
                activity.findViewById<View>(R.id.ccCompare).visibility)
        } finally {
            controller?.destroy()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ResultRedesignRs360Test : ResultRedesignRsBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h800dp-xhdpi")
class ResultRedesignRs411Test : ResultRedesignRsBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class ResultRedesignRsHiTest : ResultRedesignRsBase() {

    @Test(timeout = 30_000)
    fun `the Hindi hero and receipt are localized`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, compressIntent())
            controller.setup()
            idle()
            val texts = visibleTexts(controller.get().window!!.decorView)
            assertTrue(
                "Hindi tool name in the top bar: $texts",
                texts.contains(app.getString(R.string.tool_compress))
            )
            assertTrue(
                "Hindi saved receipt: $texts",
                texts.any { it.contains("सहेजी गई") }
            )
        } finally {
            controller?.destroy()
        }
    }
}

/** A real page image for the zoom proof tests added with the implementation. */
internal object RsFixtures {
    fun jpeg(name: String, w: Int, h: Int, app: Application): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "rs_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }
}
