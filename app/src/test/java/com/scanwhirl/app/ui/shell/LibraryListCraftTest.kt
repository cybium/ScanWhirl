package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * uihome: the Home library LIST itself.
 *
 * VISUAL_AUDIT_A X2: a document row must prove identity - a real first-page
 * thumbnail at >= 72dp (7% of the screen read as blank white), the name twice
 * at most, and a scrollbar + count so a 16-document library is navigable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryListCraftTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Robolectric: page thumbs decode on the loader's pool; let it land, then idle the post. */
    private fun settle() {
        repeat(4) {
            idle()
            Thread.sleep(120)
        }
        idle()
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                settle()
                block(activity)
            }
        }
    }

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        val scroll = host.getChildAt(0) as ScrollView
        return scroll.getChildAt(0) as LibraryScreen
    }

    private fun scrollOf(activity: ShellActivity): ScrollView {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        return host.getChildAt(0) as ScrollView
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    private fun dp(value: Float): Int = SwStyle.dp(app, value)

    // ---- 1. real paper thumbnails ----------------------------------------

    @Test
    fun `every row carries a real first-page thumbnail at least 64dp wide and 88dp tall`() {
        DocFixtures.saved(repo, app, pages = 2, name = "Studio lease")
        launch { activity ->
            val card = cards(activity).single()
            val frame = card.thumbView.parent as View

            assertTrue(
                "the paper thumbnail must be at least 64dp wide, was ${frame.layoutParams.width}px " +
                    "(64dp = ${dp(64f)}px)",
                frame.layoutParams.width >= dp(64f)
            )
            val ratio = frame.layoutParams.height.toFloat() / frame.layoutParams.width
            assertEquals("the tile keeps the page aspect (1:1.414)", 1.414f, ratio, 0.06f)

            val drawable = card.thumbView.drawable
            assertNotNull("a real page scan must show its decoded first page, not a blank tile", drawable)
            assertTrue(
                "the row thumbnail is the decoded page bitmap",
                drawable is android.graphics.drawable.BitmapDrawable
            )

            card.measure(
                View.MeasureSpec.makeMeasureSpec(dp(360f) - dp(36f), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            assertTrue("a row is at least 88dp tall", card.measuredHeight >= dp(88f))

            assertEquals("the name wraps at most twice", 2, card.nameView.maxLines)
            // T23 M1 (device sweep): the meta wraps to two lines so the date stays readable.
            assertTrue("the meta may wrap to two lines", card.metaView.maxLines >= 2)
        }
    }

    @Test
    fun `a two-line title keeps the row at or below 120dp`() {
        val id = DocFixtures.saved(
            repo, app, pages = 2,
            name = "Quarterly tax return and supporting receipts 2026"
        )
        launch { activity ->
            RuntimeEnvironment.setFontScale(1.0f)
            val card = cards(activity).single()
            val width = dp(360f) - dp(36f)
            card.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            card.layout(0, 0, width, card.measuredHeight)
            idle()
            assertTrue("the long title wraps to two lines", card.nameView.lineCount >= 2)
            assertTrue(
                "a two-line row is at most 120dp tall, was ${card.measuredHeight}px " +
                    "(120dp = ${dp(120f)}px)",
                card.measuredHeight <= dp(120f)
            )

            // The owner's real meta string must stay whole on one line at 100% font.
            val doc = requireNotNull(repo.getDocument(id))
            val probe = DocCard(activity).apply { bind(doc, "2 pages · 166 KB · Today", {}, {}) }
            probe.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            probe.layout(0, 0, width, probe.measuredHeight)
            idle()
            val layout = probe.metaView.layout
            assertNotNull("the meta is laid out", layout)
            assertEquals(
                "the meta keeps \"2 pages · 166 KB · Today\" on one un-cut line",
                0, layout!!.getEllipsisCount(0)
            )
        }
    }

    @Test
    fun `a long Hindi title at 1 point 3 font has no overlapping row text`() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            DocFixtures.saved(
                repo, app, pages = 2,
                name = "बहुत लंबा हिंदी दस्तावेज़ नाम परीक्षण संग्रह"
            )
            launch { activity ->
                val card = cards(activity).single()
                val width = dp(360f) - dp(36f)
                card.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                card.layout(0, 0, width, card.measuredHeight)
                idle()

                val texts = listOf(card.nameView, card.metaView, card.categoryView)
                    .filter { it.isShown && it.text.isNotBlank() }
                val boxes = texts.map { it to rectIn(card, it) }
                for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                    val (a, ra) = boxes[i]
                    val (b, rb) = boxes[j]
                    val ow = minOf(ra.right, rb.right) - maxOf(ra.left, rb.left)
                    val oh = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
                    assertTrue(
                        "row text at 1.3x overlaps: ${a.text} on ${b.text}",
                        ow <= 0 || oh <= 0
                    )
                }
                for ((view, rect) in boxes) {
                    assertTrue(
                        "\"${view.text}\" stays inside the card (right ${rect.right} > ${card.width})",
                        rect.right <= card.width && rect.bottom <= card.height
                    )
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    private fun rectIn(card: View, view: View): android.graphics.Rect {
        var left = 0
        var top = 0
        var current: View? = view
        while (current != null && current !== card) {
            left += current.left
            top += current.top
            current = current.parent as? View
        }
        return android.graphics.Rect(left, top, left + view.width, top + view.height)
    }

    @Test
    fun `a document without a page image shows its first letter and the page icon, never a blank tile`() {
        DocFixtures.locked(repo, app, password = "1234", pages = 2) // pages unreadable while locked
        launch { activity ->
            val card = cards(activity).single()
            val placeholder = card.thumbView.parent as ViewGroup
            val letter = placeholder.findViewById<TextView>(R.id.swDocThumbLetter)
            val icon = placeholder.findViewById<ImageView>(R.id.swDocThumbPlaceholder)
            assertNotNull("the placeholder letter exists", letter)
            assertTrue("the placeholder letter is shown", letter.isVisible)
            assertEquals("letter", "D", letter.text.toString())
            assertNotNull("the placeholder page icon exists", icon)
            assertTrue("the placeholder page icon is shown", icon.isVisible)
        }
    }

    @Test
    fun `the thumbnail reuses the page thumbnail cache`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Invoice")
        launch { activity ->
            val doc = requireNotNull(repo.getDocument(id))
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            val first = list.getChildAt(0) as DocCard
            settle()
            val firstBitmap = (first.thumbView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            assertNotNull("the first row decodes its page", firstBitmap)

            // A second row for the same page must reuse the cached bitmap, not decode again.
            val second = DocCard(activity).apply { bind(doc, "1 page", {}, {}) }
            settle()
            val cached = (second.thumbView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            assertTrue("the same page bitmap is served from the shared cache", firstBitmap === cached)
        }
    }

    // ---- 2. fast scroll + sticky count ------------------------------------

    @Test
    fun `sixteen documents get a draggable fast-scroll bar that scrolls the library`() {
        repeat(16) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Scan $i") }
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertNotNull("a 16-document library needs a visible fast-scroll bar", bar)
            assertTrue("the fast-scroll bar is shown", bar.isShown)
            assertTrue(
                "the bar is a wide-enough drag target",
                bar.layoutParams.width >= dp(12f)
            )
            assertTrue("the fast-scroll bar is enabled", bar.isEnabled)

            val barView = bar as LibraryFastScrollBar
            barView.scrollToFraction(0.5f)
            idle()
            assertTrue(
                "dragging the fast-scroll bar moves the library",
                scrollOf(activity).scrollY > 0
            )
        }
    }

    @Test
    fun `a short library has no fast-scroll bar`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Only one")
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertTrue("two documents do not need a fast-scroll bar", bar == null || !bar.isShown)
        }
    }

    @Test
    fun `nothing is drawn over the list content`() {
        repeat(16) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Scan $i") }
        launch { activity ->
            val count = activity.findViewById<TextView>(R.id.swDocCount)
            assertNotNull(count)
            assertEquals("the count stays in the list header", "16 documents", count.text.toString())
            assertTrue("the list header is shown", count.isShown)

            val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
            val scroll = host.getChildAt(0) as ScrollView
            for (i in 0 until host.childCount) {
                val child = host.getChildAt(i)
                if (child === scroll) continue
                val text = firstText(child)
                assertTrue(
                    "the scroll host paints \"${text?.text}\" over the list content",
                    text == null
                )
            }
        }
    }

    /**
     * LEAD DECISION (L1-04): OLD required the strip to be <=32dp so it could not cover rows.
     * NEW requires a >=48dp interactive strip, a <=8dp DRAWN thumb, and no overlap with any
     * card Share/More hit rect (the list reserves a 48dp end gutter for the strip).
     */
    @Test
    fun `the fast-scroll strip is 48dp non-overlapping with a 4dp drawn thumb`() {
        repeat(16) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Scan $i") }
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertNotNull(bar)
            assertTrue(bar.isShown)
            val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
            val chrome = host.findViewById<ViewGroup>(R.id.swLibraryChrome)
            assertNotNull("the pinned layer still exists for the scroll bar", chrome)
            val density = activity.resources.displayMetrics.density
            assertTrue(
                "the interactive strip is at least 48dp wide",
                bar!!.layoutParams.width >= Math.round(48 * density) - 1
            )
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/LibraryChrome.kt").readText()
            assertTrue("the visual thumb stays 4dp", src.contains("private val thumbWidth = 4f * density"))
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            assertTrue(
                "the list reserves a gutter so no card action is covered",
                list.paddingRight >= Math.round(48 * density) - 1
            )
            // No card Share/More hit rect may reach into the strip's horizontal band.
            val stripLeft = bar.x
            for (i in 0 until list.childCount) {
                val card = list.getChildAt(i)
                for (id in intArrayOf(R.id.swDocShare, R.id.swDocMore)) {
                    val action = card.findViewById<View>(id) ?: continue
                    val actionRight = action.x + action.width + (card.x) + (list.x)
                    assertTrue(
                        "card $i action $id must not overlap the fast-scroll strip",
                        actionRight <= stripLeft + 0.5f
                    )
                }
            }
        }
    }

    private fun firstText(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ---- 3. calm labels ---------------------------------------------------

    @Test
    fun `home labels are sentence case, never all caps`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Tax return")
        launch { activity ->
            val home = library(activity)
            val shouting = mutableListOf<String>()
            fun visit(view: View) {
                if (view is TextView && view.isShown) {
                    val text = view.text?.toString().orEmpty()
                    if (text.any { it.isLetter() } && text.length > 3 &&
                        text == text.uppercase(Locale.getDefault())
                    ) {
                        shouting.add(text)
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
            visit(home)
            assertTrue("ALL CAPS labels on Home: $shouting", shouting.isEmpty())
        }
    }

    @Test
    fun `the row names the folder in sentence case`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Bank letter")
        repo.setFolder(listOf(id), "Holidays")
        launch { activity ->
            val card = cards(activity).single()
            val folder = card.findViewById<TextView>(R.id.swDocFolder)
            assertNotNull(folder)
            assertTrue(folder.isVisible)
            assertEquals("Holidays", folder.text.toString())
        }
    }
}
