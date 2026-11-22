package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Activity
import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.Locale

/**
 * The owner's device screenshot of the shared tool setup page showed plain name cards with no
 * page image, an ALL-CAPS "SOURCE" label, a clipped "Mediu / m" chip and Light/Medium/Strong
 * wording that disagreed with the document compress sheet. This suite holds the picker to the
 * document's own look: a real first-page thumbnail per source row, sentence-case headings,
 * chips that never clip at 360 dp in English and Hindi, and the compress sheet's four levels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ToolSetupPickerUiTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
        DocFixtures.saved(repo, app, pages = 1, name = "Offer letter")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Lays the whole screen out at the 360 dp rig width, as the device would. */
    private fun layoutScreen(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: content
        val config = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val w = Math.round(config.screenWidthDp * density)
        val h = Math.round(config.screenHeightDp * density)
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
        }
    }

    private fun sourceRows(activity: Activity): List<LinearLayout> {
        val host = activity.findViewById<LinearLayout>(R.id.toolSetupSource) ?: return emptyList()
        return (0 until host.childCount)
            .map { host.getChildAt(it) }
            .filterIsInstance<LinearLayout>()
            .filter { it.id == R.id.toolSetupDocRow }
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }

    /** Every option chip label on the screen (the source rows are not chips). */
    private fun chipLabels(activity: Activity): List<TextView> {
        val out = mutableListOf<TextView>()
        walk(activity.findViewById(R.id.toolSetupOptions)) { view ->
            if (view is TextView && view.tag == ToolSetupActivity.CHIP_TAG) out += view
        }
        return out
    }

    private fun levelSizes(activity: Activity): List<TextView> {
        val out = mutableListOf<TextView>()
        walk(activity.findViewById(R.id.toolSetupLevels)) { view ->
            if (view is TextView && view.id == R.id.toolSetupLevelSize) out += view
        }
        return out
    }

    // ---- 1. source rows ----------------------------------------------------

    @Test(timeout = 60_000)
    fun `every source row shows the real first page as a thumbnail`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { sourceRows(activity).size == 2 })
                layoutScreen(activity)
                assertTrue(
                    "every row has decoded its own first page",
                    waitUntil {
                        sourceRows(activity).all { row ->
                            val thumb = row.findViewById<ImageView>(R.id.toolSetupDocThumb)
                            thumb?.drawable is BitmapDrawable
                        }
                    }
                )
                for (row in sourceRows(activity)) {
                    val thumb = requireNotNull(row.findViewById<ImageView>(R.id.toolSetupDocThumb))
                    assertTrue(
                        "a page thumb is at least 56 dp wide: ${thumb.width}px",
                        thumb.width >= dp(activity, 56f)
                    )
                    assertTrue(
                        "a page thumb keeps the page aspect: ${thumb.width}x${thumb.height}",
                        thumb.height > thumb.width
                    )
                    assertTrue(
                        "a source row is at least 72 dp tall: ${row.height}px",
                        row.height >= dp(activity, 72f)
                    )
                    val name = requireNotNull(row.findViewById<TextView>(R.id.toolSetupDocName))
                    assertEquals(2, name.maxLines)
                    assertTrue("a row names its document", name.text.isNotEmpty())
                    val meta = requireNotNull(row.findViewById<TextView>(R.id.toolSetupDocMeta))
                    assertTrue(
                        "the row carries pages and size: ${meta.text}",
                        Regex("""\d+ pages? · .*B""").containsMatchIn(meta.text.toString())
                    )
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun `the selected row shows a check as well as its tint`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "merge")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("two documents", waitUntil { sourceRows(activity).size == 2 })
                idle()
                val rows = sourceRows(activity)
                val checked = rows.map { row ->
                    row.findViewById<View>(R.id.toolSetupDocCheck).visibility == View.VISIBLE
                }
                assertEquals("exactly one row is selected at first", 1, checked.count { it })
                assertTrue("the first row is the one selected", checked.first())
            }
        }
    }

    // ---- 2. section labels -------------------------------------------------

    @Test(timeout = 60_000)
    fun `section labels are sentence case not all caps`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                val labels = mutableListOf<TextView>()
                walk(activity.findViewById(android.R.id.content)) { view ->
                    if (view is TextView && view.id == R.id.toolSetupSection) labels += view
                }
                assertEquals("two section labels", 2, labels.size)
                val texts = labels.map { it.text.toString() }
                assertEquals(
                    listOf(
                        app.getString(R.string.tools_pick_choose_doc),
                        app.getString(R.string.tools_setup_options)
                    ),
                    texts
                )
                for (text in texts) {
                    assertFalse(
                        "\"$text\" is still shouted in capitals",
                        text == text.uppercase(Locale.ROOT)
                    )
                }
                for (label in labels) {
                    assertEquals(
                        "\"${label.text}\" still has Latin small-caps tracking",
                        0f, label.letterSpacing, 0.001f
                    )
                }
            }
        }
    }

    // ---- 3. compress options match the document sheet ----------------------

    @Test(timeout = 60_000)
    fun `compress offers the document sheet's four levels with their sizes`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                val expected = listOf(
                    R.string.compress_level_low,
                    R.string.compress_level_medium,
                    R.string.compress_level_high,
                    R.string.compress_level_ultra
                ).map { app.getString(it) }
                val shown = chipLabels(activity).map { it.text.toString() }
                assertEquals("the compress chips are the sheet's four levels", expected, shown)

                for (id in listOf(
                    R.id.toolSetupLevelBest, R.id.toolSetupLevelBalanced,
                    R.id.toolSetupLevelSmaller, R.id.toolSetupLevelSmallest
                )) {
                    assertNotNull("missing level row $id", activity.findViewById<View>(id))
                }
                assertTrue(
                    "Balanced is the default",
                    activity.findViewById<View>(R.id.toolSetupLevelBalanced).isSelected
                )
                assertTrue(
                    "the selected level shows a check, not only colour",
                    activity.findViewById<View>(R.id.toolSetupLevelBalanced)
                        .findViewById<View>(R.id.toolSetupLevelCheck).visibility == View.VISIBLE
                )

                activity.findViewById<View>(R.id.toolSetupLevelSmallest).performClick()
                idle()
                assertTrue(activity.findViewById<View>(R.id.toolSetupLevelSmallest).isSelected)
                assertFalse(activity.findViewById<View>(R.id.toolSetupLevelBalanced).isSelected)

                assertTrue("every level carries its estimated size", waitUntil {
                    val sizes = levelSizes(activity).map { it.text.toString() }
                    sizes.size == 4 && sizes.all { it.contains("B") }
                })
                assertEquals(
                    "the custom size field kept its wording",
                    app.getString(R.string.tools_pick_custom_size),
                    activity.findViewById<EditText>(R.id.toolSetupTarget).hint.toString()
                )
            }
        }
    }

    // ---- 4. no clipped chip ------------------------------------------------

    private fun assertNoChipIsClipped(activity: Activity, toolId: String) {
        layoutScreen(activity)
        val chips = chipLabels(activity)
        assertTrue("$toolId has chips to check", chips.isNotEmpty())
        for (chip in chips) {
            val layout = requireNotNull(chip.layout) { "${chip.text} was never measured" }
            assertEquals(
                "$toolId chip \"${chip.text}\" wraps onto ${layout.lineCount} lines",
                1, layout.lineCount
            )
            val available = chip.width - chip.totalPaddingLeft - chip.totalPaddingRight
            for (line in 0 until layout.lineCount) {
                assertEquals(
                    "$toolId chip \"${chip.text}\" is cut with an ellipsis",
                    0, layout.getEllipsisCount(line)
                )
                assertTrue(
                    "$toolId chip \"${chip.text}\" line $line is ${layout.getLineWidth(line).toInt()}px " +
                        "but only ${available}px fit",
                    layout.getLineWidth(line) <= available + 0.5f
                )
            }
            // The tappable surface (a chip itself, or the row around a level label) is 48 dp.
            var target: View = chip
            while (!target.isClickable && target.parent is View) target = target.parent as View
            assertTrue(
                "$toolId chip \"${chip.text}\" has a ${target.height}px tap target, under 48 dp",
                target.height >= dp(activity, 48f)
            )
        }
    }

    @Test(timeout = 60_000)
    fun `no option chip clips at 360 dp in English`() {
        for (tool in listOf("compress", "pdf_to_images", "to_excel")) {
            ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, tool)).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue("$tool listed its options", waitUntil { chipLabels(activity).isNotEmpty() })
                    assertNoChipIsClipped(activity, tool)
                }
            }
        }
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `no option chip clips at 360 dp in Hindi`() {
        for (tool in listOf("compress", "pdf_to_images", "to_excel")) {
            ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, tool)).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue("$tool listed its options", waitUntil { chipLabels(activity).isNotEmpty() })
                    assertNoChipIsClipped(activity, tool)
                }
            }
        }
    }

    /** The narrowest phone still supported: the same chips must fit there too. */
    @Test(timeout = 60_000)
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `no option chip clips at 320 dp in English and Hindi`() {
        for (qualifiers in listOf("w320dp-h640dp-xhdpi", "hi-w320dp-h640dp-xhdpi")) {
            org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers)
            for (tool in listOf("compress", "pdf_to_images", "to_excel")) {
                ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, tool)).use { scenario ->
                    scenario.onActivity { activity ->
                        assertTrue("$tool listed its options", waitUntil { chipLabels(activity).isNotEmpty() })
                        assertNoChipIsClipped(activity, tool)
                    }
                }
            }
        }
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
    }

    // ---- 5. the long list shows a scrollbar --------------------------------

    @Test(timeout = 60_000)
    fun `the long source list keeps a visible scrollbar`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                val scroll = requireNotNull(activity.findViewById<ScrollView>(R.id.toolSetupScroll))
                assertTrue("the page scrolls vertically", scroll.isVerticalScrollBarEnabled)
                assertFalse("the scrollbar must not fade away", scroll.isScrollbarFadingEnabled)
            }
        }
    }

    // ---- 6. the action is pinned, not at the end of the list ---------------

    private fun ancestors(view: View): List<View> {
        val out = mutableListOf<View>()
        var parent = view.parent as? View
        while (parent != null) {
            out += parent
            parent = parent.parent as? View
        }
        return out
    }

    /** Distance of [view]'s bottom from the bottom of the screen root. */
    private fun bottomInRoot(activity: Activity, view: View): Int {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: content
        var bottom = view.bottom
        var parent = view.parent as? View
        while (parent != null && parent !== root) {
            bottom += parent.top
            parent = parent.parent as? View
        }
        return bottom
    }

    @Test(timeout = 60_000)
    fun `the action button is pinned below the list and visible without scrolling`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { sourceRows(activity).size == 2 })
                layoutScreen(activity)
                val run = requireNotNull(activity.findViewById<View>(R.id.toolSetupRun))
                assertTrue(
                    "Continue must not live inside the scrolling list",
                    ancestors(run).none { it.id == R.id.toolSetupScroll }
                )
                assertTrue("Continue is on screen", run.isShown && run.height > 0)
                assertTrue("Continue is 56 dp tall: ${run.height}px", run.height >= dp(activity, 56f))

                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0) ?: content
                assertTrue(
                    "Continue must be reachable without scrolling: bottom ${bottomInRoot(activity, run)}px " +
                        "of ${root.height}px",
                    bottomInRoot(activity, run) <= root.height
                )
                assertTrue(
                    "Continue sits in the bottom half, not at the end of the list",
                    bottomInRoot(activity, run) > root.height * 2 / 3
                )

                val bar = requireNotNull(activity.findViewById<View>(R.id.toolSetupBar))
                assertTrue(
                    "the bar itself must not scroll",
                    ancestors(bar).none { it.id == R.id.toolSetupScroll }
                )
                val scroll = requireNotNull(activity.findViewById<ScrollView>(R.id.toolSetupScroll))
                val column = scroll.getChildAt(0) as ViewGroup
                // The clearance is a scrollable spacer, not bottom padding: padding was not
                // counted in the fillViewport scroll range, so the range field hid behind Continue.
                val spacer = column.getChildAt(column.childCount - 1)
                assertEquals(
                    "the last child is the scrollable clearance",
                    ToolSetupActivity.BOTTOM_SPACER_TAG,
                    spacer.tag
                )
                assertTrue(
                    "the list must keep room for the bar: spacer ${spacer.height}px, " +
                        "bar ${bar.height}px",
                    spacer.height >= bar.height
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun `the longest list ends above the pinned bar and the last row stays reachable`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { sourceRows(activity).size == 2 })
                layoutScreen(activity)
                val bar = requireNotNull(activity.findViewById<View>(R.id.toolSetupBar))
                val scroll = requireNotNull(activity.findViewById<ScrollView>(R.id.toolSetupScroll))
                // Scroll to the end: the last row's bottom must clear the bar.
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                idle()
                layoutScreen(activity)
                val last = sourceRows(activity).last()
                assertTrue(
                    "the last source row (${bottomInRoot(activity, last)}px) is hidden behind the bar " +
                        "(top ${bottomInRoot(activity, bar) - bar.height}px)",
                    bottomInRoot(activity, last) <= bottomInRoot(activity, bar) - bar.height
                )
            }
        }
    }

    // ---- 7. the compress help line and the glossary ------------------------

    @Test(timeout = 60_000)
    fun `compress explains the level-or-size choice in plain words`() {
        assertEquals(
            "Choose a level, or type the size you need.",
            app.getString(R.string.tools_setup_compress_note)
        )
        assertFalse(
            "the old help line is gone",
            app.getString(R.string.tools_setup_compress_note).contains("A target size fits every page")
        )
        assertEquals(
            "Custom size (optional), e.g. 500 KB",
            app.getString(R.string.tools_pick_custom_size)
        )
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `Hindi labels follow the judged glossary`() {
        // JUDGE_HINDI: "Custom size | अपनी ज़रूरत का आकार (चाहें तो)"; no वैकल्पिक.
        assertEquals(
            "अपनी ज़रूरत का आकार (चाहें तो), जैसे 500 KB",
            app.getString(R.string.tools_pick_custom_size)
        )
        assertEquals("दस्तावेज़ चुनें", app.getString(R.string.tools_pick_choose_doc))
        assertEquals(
            "स्तर चुनें, या जितना आकार चाहिए वह लिखें।",
            app.getString(R.string.tools_setup_compress_note)
        )
        // JUDGE_HINDI: "Continue | जारी रखें", distinct from आगे.
        assertEquals("जारी रखें", app.getString(R.string.tools_setup_run))
    }

    @Test(timeout = 60_000)
    fun `the custom size hint fits its field at 360 dp in English`() {
        assertHintFits(R.string.tools_pick_custom_size)
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `the custom size hint fits its field at 360 dp in Hindi`() {
        assertHintFits(R.string.tools_pick_custom_size)
    }

    private fun assertHintFits(hintRes: Int) {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                layoutScreen(activity)
                val field = requireNotNull(activity.findViewById<EditText>(R.id.toolSetupTarget))
                val hint = requireNotNull(field.hint).toString()
                val need = field.paint.measureText(hint)
                val available = field.width - field.totalPaddingLeft - field.totalPaddingRight
                assertTrue(
                    "the hint \"$hint\" needs ${need.toInt()}px but only ${available}px fit",
                    need <= available + 0.5f
                )
            }
        }
    }

    // ---- 8. Batch compress says the same four levels -----------------------

    @Test(timeout = 60_000)
    fun `batch compress offers the document sheet's four levels too`() {
        val ids = repo.listDocuments().map { it.id }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue("the action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionCompress) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionCompress).performClick()
                idle()
                val sheet = requireNotNull(ShadowDialog.getLatestDialog())
                val expected = listOf(
                    R.string.compress_level_low,
                    R.string.compress_level_medium,
                    R.string.compress_level_high,
                    R.string.compress_level_ultra
                ).map { app.getString(it) }
                val shown = mutableListOf<String>()
                walk(sheet.window!!.decorView) { view ->
                    if (view is TextView && view.tag == ToolSetupActivity.CHIP_TAG) {
                        shown += view.text.toString()
                    }
                }
                assertEquals("Batch compress uses the same four levels", expected, shown)
                for (id in listOf(
                    R.id.batchLevelBest, R.id.batchLevelBalanced,
                    R.id.batchLevelSmaller, R.id.batchLevelSmallest
                )) {
                    assertNotNull("missing Batch level row $id", sheet.findViewById<View>(id))
                }
            }
        }
    }
}
