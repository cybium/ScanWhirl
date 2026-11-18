package com.scanwhirl.app.ui

import android.app.Application
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.pagegrid.PageGridView
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.StatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 07_reader_dock — the saved-document screen, owner revision 2026-11-18:
 * the dock reads Edit · Text · More · Share (the document is worked on first, sent away last),
 * every item is equal width, >= 56 dp tall, 24 dp icon + one 12 sp label that never wraps or
 * clips in EN or HI; the folder/category pill is sentence case metadata; the title wraps to
 * two lines; the meta line reads "3 pages · 544 KB"; a long document's grid keeps a visible
 * vertical scrollbar and legible page badges.
 *
 * Red first: written against the pre-revision screen (Share first, weight 2/1/1/1, caps pill,
 * one-line title, "N pages" only, no scrollbar).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class DocumentViewerReaderUiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    private fun dp(value: Float): Int = Math.round(value * app.resources.displayMetrics.density)

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /**
     * Seeds a document, opens the screen, waits for the async first load and lays the screen
     * out at the 360 dp phone width the qualifier promises (Robolectric does not lay out the
     * decor itself, so widths/heights would otherwise stay 0).
     */
    private fun open(
        name: String = "Physics notes - Chapter 4",
        pages: Int = 3,
        category: String? = null,
        block: (DocumentViewerActivity) -> Unit
    ) {
        val id = DocFixtures.saved(repo, app, pages = pages, name = name)
        category?.let { repo.setCategory(id, it) }
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id))
            .use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "the first load must fill the meta line",
                        pollUntil {
                            activity.findViewById<TextView>(R.id.pageCount)
                                ?.text?.isNotEmpty() == true
                        }
                    )
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    val root = content.getChildAt(0) ?: content
                    val w = dp(360f)
                    val h = dp(640f)
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, w, h)
                    idle()
                    block(activity)
                }
            }
    }

    private fun dock(activity: DocumentViewerActivity): ViewGroup =
        activity.findViewById(R.id.bottomDock)

    private fun dockLabels(activity: DocumentViewerActivity): List<TextView> {
        val dock = dock(activity)
        return (0 until dock.childCount).map { dock.getChildAt(it) as TextView }
    }

    // --- 1. dock order and shape -------------------------------------------

    @Test
    fun `dock reads Edit, Text, More then Share last`() {
        open { activity ->
            val dock = dock(activity)
            assertEquals("the dock keeps its four actions", 4, dock.childCount)
            val ids = (0 until dock.childCount).map { dock.getChildAt(it).id }
            assertEquals(
                "Edit · Text · More · Share: sharing the document is the last action",
                listOf(R.id.btnEdit, R.id.btnText, R.id.btnMore, R.id.btnShare),
                ids
            )
            val labels = listOf(
                R.string.edit, R.string.btn_text, R.string.more, R.string.share_pdf
            )
            dockLabels(activity).forEachIndexed { index, label ->
                assertEquals(
                    "dock item $index keeps its label",
                    activity.getString(labels[index]),
                    label.text.toString()
                )
            }
        }
    }

    @Test
    fun `dock items are equal width and at least 56 dp tall`() {
        open { activity ->
            val dock = dock(activity)
            val widths = (0 until dock.childCount).map { dock.getChildAt(it).width }
            assertTrue("the dock is laid out", widths.all { it > 0 })
            assertTrue(
                "every dock item has the same width (even spacing), was $widths",
                widths.max() - widths.min() <= dp(1f)
            )
            (0 until dock.childCount).forEach { index ->
                val item = dock.getChildAt(index)
                assertEquals("item $index is weighted equally", 1f, weight(item), 0f)
                assertEquals(
                    "item $index keeps the DockItem 56 dp minimum",
                    dp(56f),
                    (item as TextView).minimumHeight
                )
                assertTrue(
                    "item $index is at least 56 dp tall, was ${item.height}px",
                    item.height >= dp(56f)
                )
            }
        }
    }

    private fun weight(view: View): Float {
        val lp = view.layoutParams
        return if (lp is android.widget.LinearLayout.LayoutParams) lp.weight else -1f
    }

    // --- 2. labels never wrap or clip, EN and HI ---------------------------

    @Test
    fun `no dock label clips at 360 dp in English`() {
        open { activity -> assertDockLabelsFit(activity) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `no dock label clips at 360 dp in Hindi`() {
        open { activity ->
            assertEquals(
                "the Hindi dock label is the existing values-hi string",
                "शेयर करें",
                activity.getString(R.string.share_pdf)
            )
            assertDockLabelsFit(activity)
        }
    }

    private fun assertDockLabelsFit(activity: DocumentViewerActivity) {
        dockLabels(activity).forEachIndexed { index, label ->
            assertEquals(
                "dock label $index is one line (maxLines)",
                1,
                label.maxLines
            )
            assertEquals(
                "dock label $index never takes a second line",
                1,
                label.lineCount
            )
            val layout = label.layout
            assertNotNull("dock label $index is laid out", layout)
            assertEquals(
                "dock label '${label.text}' is not truncated with an ellipsis",
                0,
                layout!!.getEllipsisCount(0)
            )
            assertEquals(
                "dock label $index is 12 sp",
                12f,
                label.textSize / activity.resources.displayMetrics.scaledDensity,
                0.5f
            )
        }
    }

    // --- 3. the category pill is calm metadata -----------------------------

    @Test
    fun `category chip is a compact pill inside a 48 dp touch target`() {
        open(category = CategoryRules.STUDY) { activity ->
            val chip = activity.findViewById<View>(R.id.docCategoryChip)
            val touch = activity.findViewById<View>(R.id.docCategoryTouch)
            val label = activity.findViewById<TextView>(R.id.docCategoryLabel)
            val dot = activity.findViewById<View>(R.id.docCategoryDot)
            val meta = activity.findViewById<TextView>(R.id.pageCount)

            assertEquals(View.VISIBLE, chip.visibility)
            assertTrue(
                "the visible chip is compact (<= 36 dp tall), was ${chip.height}px",
                chip.height <= dp(36f)
            )
            assertTrue(
                "the visible chip is still a pill (>= 30 dp tall), was ${chip.height}px",
                chip.height >= dp(30f)
            )
            assertTrue(
                "the tap area is 48 dp tall, was ${touch.height}px",
                touch.height >= dp(48f)
            )
            assertNotNull(
                "taps outside the compact pill still reach it (TouchDelegate)",
                touch.touchDelegate
            )
            // Same row as the meta line: the two centres line up.
            val chipY = IntArray(2).also { chip.getLocationOnScreen(it) }[1] + chip.height / 2
            val metaY = IntArray(2).also { meta.getLocationOnScreen(it) }[1] + meta.height / 2
            assertTrue(
                "the chip sits on the meta line (chip $chipY, meta $metaY)",
                kotlin.math.abs(chipY - metaY) <= dp(4f)
            )

            assertEquals(
                "the label is sentence case, not ALL CAPS",
                "Study",
                label.text.toString()
            )
            assertFalse("the label must not be all-caps", label.isAllCaps)
            assertEquals(
                "the label is 13 sp",
                13f * activity.resources.displayMetrics.scaledDensity,
                label.textSize,
                0.5f
            )
            assertEquals(
                "the label is metadata: neutral ink",
                activity.getColor(R.color.text_hologram_mid),
                label.currentTextColor
            )
            assertEquals("the dot is 8 dp wide", dp(8f), dot.width)
            assertEquals("the dot is 8 dp tall", dp(8f), dot.height)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `Hindi category pill is sentence case too`() {
        open(category = CategoryRules.UNSORTED) { activity ->
            val label = activity.findViewById<TextView>(R.id.docCategoryLabel)
            assertEquals(View.VISIBLE, label.visibility)
            assertEquals(
                "the Hindi pill is the values-hi label, not uppercase",
                com.scanwhirl.app.util.CategoryLabels.display(
                    activity, CategoryRules.UNSORTED
                ),
                label.text.toString()
            )
            assertFalse("the pill must not be all-caps", label.isAllCaps)
        }
    }

    // --- 4. title wraps twice, meta line carries pages and size ------------

    @Test
    fun `title wraps to two lines then ellipsis`() {
        open { activity ->
            val title = titleView(activity, "Physics notes - Chapter 4")
            assertEquals("the title may use two lines", 2, title.maxLines)
            assertEquals(
                "the title ellipsises instead of running away",
                TextUtils.TruncateAt.END,
                title.ellipsize
            )
        }
    }

    @Test
    fun `a long title uses both lines and stops there`() {
        val long = "Physics notes - Chapter 4 - thermodynamics, entropy and the second law, " +
            "lecture notes from the autumn term"
        open(name = long) { activity ->
            val title = titleView(activity, long)
            assertEquals("a long name takes the second line", 2, title.lineCount)
            assertEquals(2, title.maxLines)
        }
    }

    private fun titleView(activity: DocumentViewerActivity, name: String): TextView {
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        val title = (0 until toolbar.childCount)
            .map { toolbar.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == name }
        assertNotNull("the header shows the document name", title)
        return title!!
    }

    @Test
    fun `meta line reads pages, size`() {
        open { activity ->
            val meta = activity.findViewById<TextView>(R.id.pageCount).text.toString()
            val doc = requireNotNull(repo.listDocuments().firstOrNull()) { "seeded document" }
            val size = StatusLine.formatBytes(
                if (doc.pdfFile.isFile && doc.pdfFile.length() > 0L) doc.pdfFile.length()
                else doc.pageFiles.sumOf { it.length() }
            )
            assertEquals("3 pages · $size", meta)
            assertTrue("the meta line keeps the middle dot", meta.contains(" · "))
        }
    }

    // --- 5. long document: visible scrollbar, legible badges ---------------

    @Test
    fun `a long document grid keeps a visible vertical scrollbar`() {
        open(pages = 12) { activity ->
            val grid = activity.findViewById<PageGridView>(R.id.pageList)
            assertTrue("the page grid scrolls vertically", grid.isVerticalScrollBarEnabled)
            assertFalse("the grid has no horizontal bar", grid.isHorizontalScrollBarEnabled)
            assertFalse(
                "the bar must not fade away (fadeScrollbars=false)",
                grid.isScrollbarFadingEnabled
            )
            assertEquals(12, grid.adapter?.itemCount ?: 0)
        }
    }

    @Test
    fun `page numbers sit below the paper, never on it`() {
        open(pages = 4) { activity ->
            val grid = activity.findViewById<PageGridView>(R.id.pageList)
            var checked = 0
            for (i in 0 until grid.childCount) {
                val cell = grid.getChildAt(i) as? ViewGroup ?: continue
                val number = cell.findViewById<TextView>(R.id.pageNum)
                val paper = cell.findViewById<View>(R.id.pageThumb)
                if (number == null || paper == null) continue

                val paperRect = android.graphics.Rect(0, 0, paper.width, paper.height)
                cell.offsetDescendantRectToMyCoords(paper, paperRect)
                val numberRect = android.graphics.Rect(0, 0, number.width, number.height)
                cell.offsetDescendantRectToMyCoords(number, numberRect)

                assertFalse(
                    "page number '${number.text}' is drawn over the page image " +
                        "(number $numberRect, paper $paperRect)",
                    android.graphics.Rect.intersects(paperRect, numberRect)
                )
                assertTrue(
                    "page number '${number.text}' sits below the paper",
                    numberRect.top >= paperRect.bottom
                )
                assertTrue(
                    "page number '${number.text}' is centred under its page",
                    kotlin.math.abs(numberRect.centerX() - paperRect.centerX()) <= dp(2f)
                )
                assertEquals("the number is the page position", "${i + 1}", number.text.toString())
                assertTrue(
                    "the number is 12-13 sp",
                    number.textSize >= 12f * activity.resources.displayMetrics.scaledDensity - 0.5f &&
                        number.textSize <= 13f * activity.resources.displayMetrics.scaledDensity + 0.5f
                )
                assertEquals(
                    "the number is mid-grey, not a floating badge",
                    activity.getColor(R.color.text_hologram_mid),
                    number.currentTextColor
                )
                assertTrue(
                    "the number has no chip background over the paper",
                    number.background == null
                )
                checked++
            }
            assertTrue("the grid laid out at least one page cell", checked >= 1)
        }
    }
}
