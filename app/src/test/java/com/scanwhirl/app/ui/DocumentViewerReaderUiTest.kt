package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 07_reader_dock → UI-B6 (GPT judgement 2026-11-19b, item 1): the saved-document
 * screen is the workspace. Seven NAMED actions — Edit / crop (the only filled cyan button),
 * Read text, Share, Compress, Sign (+ the gold word Pro while Free), Save PDF and More — read
 * 4 + 3 at 360 dp, and from 160 % type the panel moves into the scroll flow above the pages as
 * two growing columns. The folder/category pill is sentence-case metadata, the name wraps and
 * is never ellipsized, the meta line reads "3 pages · 544 KB", and a long document's grid
 * keeps a visible vertical scrollbar and legible page badges.
 *
 * Red first: originally written against the pre-B2 screen (Edit · Text · More · Share, four
 * across at any font scale); updated for B6 where the four-item dock became this panel.
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

    private fun panel(activity: DocumentViewerActivity): ViewGroup =
        activity.findViewById(R.id.bottomDock)

    /** The seven action buttons in reading order, flattened out of their rows. */
    private fun actionButtons(activity: DocumentViewerActivity): List<TextView> {
        val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
        return (0 until grid.childCount).flatMap { index ->
            val row = grid.getChildAt(index) as ViewGroup
            (0 until row.childCount).map { row.getChildAt(it) as TextView }
        }
    }

    private val actionIds = listOf(
        R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnCompress,
        R.id.btnSign, R.id.btnSavePdf, R.id.btnMore
    )

    // --- 1. the panel's order and shape -------------------------------------

    @Test
    fun `the workspace reads seven named actions in one order`() {
        open { activity ->
            val buttons = actionButtons(activity)
            assertEquals("the panel keeps its seven actions", 7, buttons.size)
            assertEquals(
                "Edit / crop · Read text · Share · Compress · Sign · Save PDF · More",
                actionIds,
                buttons.map { it.id }
            )
            val labels = listOf(
                R.string.doc_action_edit_crop,
                R.string.dock_read_text,
                R.string.share_pdf,
                R.string.tool_compress,
                R.string.tool_sign,
                R.string.doc_action_save_pdf,
                R.string.more
            )
            buttons.forEachIndexed { index, label ->
                assertTrue(
                    "action $index keeps its label",
                    label.text.toString() == activity.getString(labels[index]) ||
                        label.text.toString().startsWith(activity.getString(labels[index]))
                )
            }
            assertEquals(
                "the panel names itself",
                activity.getString(R.string.doc_actions_header),
                activity.findViewById<TextView>(R.id.actionPanelTitle).text.toString()
            )
            assertEquals(
                "the panel says where the copy goes",
                activity.getString(R.string.doc_action_save_hint),
                activity.findViewById<TextView>(R.id.actionPanelHint).text.toString()
            )
        }
    }

    @Test
    fun `at 100 percent the actions read four then three`() {
        open { activity ->
            val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
            val rows = (0 until grid.childCount).map { grid.getChildAt(it) as ViewGroup }
            assertEquals("four actions in the first row, three in the second",
                listOf(4, 3), rows.map { it.childCount })
        }
    }

    @Test
    fun `at 200 percent the panel is two growing columns above the pages`() {
        RuntimeEnvironment.setFontScale(2.0f)
        try {
            open { activity ->
                val scroll = activity.findViewById<View>(R.id.actionFlowScroll)
                assertTrue("the content scrolls at 200 %", scroll.isShown)
                assertNotNull(
                    "the panel moved into the scroll flow",
                    scroll.findViewById<View>(R.id.bottomDock)
                )
                val column = activity.findViewById<ViewGroup>(R.id.actionFlowColumn)
                assertEquals(R.id.bottomDock, column.getChildAt(0).id)
                assertEquals(R.id.pageList, column.getChildAt(1).id)

                val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
                val rows = (0 until grid.childCount).map { grid.getChildAt(it) as ViewGroup }
                assertEquals("two columns at 200 %", listOf(2, 2, 2, 1), rows.map { it.childCount })
                for (row in rows) {
                    for (i in 0 until row.childCount) {
                        val item = row.getChildAt(i) as TextView
                        assertNotNull("a 200 % cell is laid out", item.layout)
                        assertEquals(
                            "grid label '${item.text}' is not truncated",
                            0,
                            item.layout!!.getEllipsisCount(0)
                        )
                        assertTrue(
                            "a 200 % cell stays a 48 dp target, was ${item.height}px",
                            item.height >= dp(48f)
                        )
                    }
                }
                assertEquals(
                    "the last action uses the full width rather than one column",
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rows.last().getChildAt(0).layoutParams.width
                )

                // The sheet is the same named destination from the grid too.
                activity.findViewById<View>(R.id.btnMore).performClick()
                idle()
                assertNotNull("Actions opens the document sheet", ShadowDialog.getLatestDialog())
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    @Test
    fun `each row's actions are equal width and at least 56 dp tall`() {
        open { activity ->
            val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
            val rows = (0 until grid.childCount).map { grid.getChildAt(it) as ViewGroup }
            for ((rowIndex, row) in rows.withIndex()) {
                val widths = (0 until row.childCount).map { row.getChildAt(it).width }
                assertTrue("row $rowIndex is laid out", widths.all { it > 0 })
                assertTrue(
                    "every action in row $rowIndex has the same width, was $widths",
                    widths.max() - widths.min() <= dp(1f)
                )
                for (index in 0 until row.childCount) {
                    val item = row.getChildAt(index)
                    assertEquals("item $rowIndex/$index is weighted equally", 1f, weight(item), 0f)
                    assertEquals(
                        "item $rowIndex/$index keeps the 56 dp DockItem minimum",
                        dp(56f),
                        (item as TextView).minimumHeight
                    )
                    assertTrue(
                        "item $rowIndex/$index is at least 56 dp tall, was ${item.height}px",
                        item.height >= dp(56f)
                    )
                }
            }
        }
    }

    private fun weight(view: View): Float {
        val lp = view.layoutParams
        return if (lp is android.widget.LinearLayout.LayoutParams) lp.weight else -1f
    }

    // --- 2. labels never clip, EN and HI -----------------------------------

    @Test
    fun `no action label clips at 360 dp in English`() {
        open { activity -> assertActionLabelsFit(activity) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `no action label clips at 360 dp in Hindi`() {
        open { activity ->
            assertEquals(
                // hindi lane: the judged glossary replaced "शेयर करें" with "भेजें".
                "the Hindi Share label is the judged glossary word",
                "भेजें",
                activity.getString(R.string.share_pdf)
            )
            assertEquals(
                "Read text is everyday Hindi, not a loanword",
                "पढ़ें",
                activity.getString(R.string.dock_read_text)
            )
            assertEquals(
                "More is everyday Hindi, not a loanword",
                "और",
                activity.getString(R.string.more)
            )
            assertActionLabelsFit(activity)
        }
    }

    private fun assertActionLabelsFit(activity: DocumentViewerActivity) {
        actionButtons(activity).forEachIndexed { index, label ->
            assertEquals(
                "action label $index may wrap to three lines at most",
                3,
                label.maxLines
            )
            val layout = label.layout
            assertNotNull("action label $index is laid out", layout)
            for (line in 0 until layout!!.lineCount) {
                assertEquals(
                    "action label '${label.text}' is not truncated with an ellipsis",
                    0,
                    layout.getEllipsisCount(line)
                )
                assertTrue(
                    "action label '${label.text}' line $line fits between its paddings",
                    layout.getLineWidth(line) <=
                        (label.width - label.totalPaddingLeft - label.totalPaddingRight) + 0.5f
                )
            }
            assertEquals(
                "action label $index is 12 sp (never shrunk)",
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
            assertTrue("the 48 dp tap area is the clickable view", touch.isClickable)
            assertNotNull(
                "the tap area carries the spoken label",
                touch.contentDescription
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

    // --- 4. the name wraps; the meta line carries pages and size -----------

    @Test
    fun `the document name is the header H1 and wraps without an ellipsis`() {
        val long = "Physics notes - Chapter 4 - thermodynamics, entropy and the second law"
        open(name = long) { activity ->
            val name = activity.findViewById<TextView>(R.id.docName)
            assertEquals("the header shows the document name", long, name.text.toString())
            assertTrue("a long name takes more than one line", name.lineCount >= 2)
            val layout = requireNotNull(name.layout) { "the name is laid out" }
            for (line in 0 until layout.lineCount) {
                assertEquals(
                    "the name is never ellipsized (line $line)",
                    0,
                    layout.getEllipsisCount(line)
                )
            }
            // The toolbar keeps back + the brand mark; the name is not a toolbar title.
            val toolbar =
                activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            val toolbarTexts = (0 until toolbar.childCount)
                .map { toolbar.getChildAt(it) }
                .filterIsInstance<TextView>()
            assertTrue(
                "the document name is not painted into the toolbar",
                toolbarTexts.none { it.text?.toString() == long }
            )
            assertNotNull(
                "the brand mark keeps its leading place",
                activity.findViewById<View>(R.id.docBrandMark)
            )
        }
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
