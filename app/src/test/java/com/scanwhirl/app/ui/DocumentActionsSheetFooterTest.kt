package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
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
import org.robolectric.shadows.ShadowDialog

/**
 * W1 add-on (fresh atlas, `007_actions_sheet_2.0.png`): the Actions sheet is taller than the
 * phone at 200 % type, so it scrolls above its fixed Close row. The LAST row ("Move to Trash")
 * must be reachable: scrolled to the end, its bottom sits above the Close row's top, nothing of
 * it is clipped, and the Close row is never covered. Same at 100 %, on 360 dp and 320 dp phones,
 * in English and Hindi.
 *
 * Red first: the sheet's scroll area ended flush under the footer, so the last row could never be
 * brought fully above the Close row.
 */
abstract class DocumentActionsSheetFooterBase {

    protected lateinit var app: Application
    protected lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        repo = ScanRepository(app)
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private val density: Float get() = app.resources.displayMetrics.density

    private fun px(dp: Float): Int = Math.round(dp * density)

    private val screenWidthDp: Int get() = app.resources.configuration.screenWidthDp

    private val screenHeightDp: Int get() = app.resources.configuration.screenHeightDp

    /** Bottom edge of [v] in the sheet root's coordinates. */
    private fun absBottom(v: View): Int {
        var b = v.bottom
        var p = v.parent as? View
        while (p != null) {
            if (p.id == R.id.swSheetRoot) return b
            b += p.top
            p = p.parent as? View
        }
        return b
    }

    /** Top edge of [v] in the sheet root's coordinates. */
    private fun absTop(v: View): Int {
        var t = v.top
        var p = v.parent as? View
        while (p != null) {
            if (p.id == R.id.swSheetRoot) return t
            t += p.top
            p = p.parent as? View
        }
        return t
    }

    private fun latestSheet(): android.app.Dialog? = ShadowDialog.getLatestDialog()?.takeIf {
        it.isShowing && it.findViewById<View>(R.id.swSheetRoot) != null
    }

    /**
     * Opens the document screen, its Actions sheet, and lays the sheet out exactly as the atlas
     * does: content width, sheet height at most the screen (minus the 12 dp margins).
     */
    private fun openSheetAt(fontScale: Float, block: (android.app.Dialog, View) -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Physics notes")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id))
            .use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "the document loads before the sheet",
                        pollUntil {
                            activity.findViewById<TextView>(R.id.pageCount)
                                ?.text?.isNotEmpty() == true
                        }
                    )
                    activity.findViewById<View>(R.id.btnMore).performClick()
                    idle()
                    var dialog: android.app.Dialog? = null
                    assertTrue("the Actions sheet opens", pollUntil {
                        latestSheet().also { dialog = it } != null
                    })
                    val sheet = requireNotNull(dialog)
                    val root = requireNotNull(sheet.findViewById<View>(R.id.swSheetRoot))
                    val w = px(screenWidthDp.toFloat())
                    val h = px(screenHeightDp.toFloat())
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
                    )
                    val sh = root.measuredHeight.coerceIn(1, h)
                    root.layout(0, 0, w, sh)
                    idle()
                    block(sheet, root)
                }
            }
    }

    private fun rows(root: View): ViewGroup =
        requireNotNull(root.findViewWithTag<ViewGroup>("docActions:rows")) {
            "the sheet lists its rows"
        }

    /** The scroll owner of the sheet: the rows' own parent. */
    private fun scrollOf(root: View): View =
        requireNotNull(rows(root).parent as? View) { "the rows live in a scroll view" }

    /** [v]'s bottom as drawn inside [scroll]'s viewport, in root coordinates. */
    private fun onScreenBottom(v: View, scroll: View): Int = absBottom(v) - scroll.scrollY

    private fun onScreenTop(v: View, scroll: View): Int = absBottom(v) - v.height - scroll.scrollY

    private fun maxScroll(scroll: View): Int {
        val child = scroll as ViewGroup
        val content = if (child.childCount > 0) child.getChildAt(0).height else 0
        return (content - scroll.height + scroll.paddingBottom).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ 1. the last row

    @Test
    fun `scrolled to the end the last row is fully above the Close row at 100 percent`() =
        lastRowAboveFooter(1.0f)

    @Test
    fun `scrolled to the end the last row is fully above the Close row at 200 percent`() =
        lastRowAboveFooter(2.0f)

    private fun lastRowAboveFooter(scale: Float) {
        openSheetAt(scale) { dialog, root ->
            val list = rows(root)
            val scroll = scrollOf(root)
            assertTrue("the sheet has rows", list.childCount >= 2)
            val footer = requireNotNull(dialog.findViewById<View>(R.id.swSheetSecondary)) {
                "the sheet has its Close row"
            }
            assertEquals(
                "the footer is the sheet's own Close",
                app.getString(R.string.sheet_close),
                (footer as TextView).text.toString()
            )

            // Scroll all the way down, as a person does.
            scroll.scrollTo(0, maxScroll(scroll))
            idle()
            assertEquals(
                "the sheet is scrolled to its end",
                maxScroll(scroll),
                scroll.scrollY
            )

            val last = list.getChildAt(list.childCount - 1)
            val rowBottom = onScreenBottom(last, scroll)
            val scrollBottom = absTop(scroll) + scroll.height
            assertTrue(
                "the last row is laid out (bottom ${rowBottom}px, footer top ${absTop(footer)}px)",
                last.height > 0
            )
            assertTrue(
                "the last row is at least 48 dp tall (was ${last.height}px)",
                last.height >= px(48f)
            )
            assertTrue(
                "the last row stays inside the scroll viewport " +
                    "(row bottom ${rowBottom}px, scroll bottom ${scrollBottom}px)",
                rowBottom <= scrollBottom
            )
            assertTrue(
                "the last row keeps the content's own bottom inset above the Close row at " +
                    "${scale}x (row bottom ${rowBottom}px, footer top ${absTop(footer)}px)",
                rowBottom + px(12f) <= absTop(footer)
            )
            assertTrue(
                "the last row is fully inside the sheet (row bottom ${rowBottom}px, " +
                    "sheet ${root.height}px)",
                rowBottom <= root.height
            )
            assertTrue(
                "the Close row is not covered by the scroll",
                absTop(footer) >= scrollBottom - px(1f)
            )
            // TalkBack: the row keeps its spoken name with the reason-free Trash row.
            val visible = last.isShown
            assertTrue("the last row is shown after scrolling to the end", visible)
            val label = (last as ViewGroup).getChildAt(1) as ViewGroup
            assertNotNull("it carries a label", (label.getChildAt(0) as TextView).text)
        }
    }

    // ------------------------------------------------------------------ 2. every row reachable

    @Test
    fun `every named row is reachable by scrolling at 200 percent`() {
        openSheetAt(2.0f) { dialog, root ->
            val list = rows(root)
            val scroll = scrollOf(root)
            val footer = requireNotNull(dialog.findViewById<View>(R.id.swSheetSecondary))
            // Walk down in viewport-sized steps, as a person swipes, and remember what was fully
            // inside the viewport at least once.
            val seen = linkedSetOf<View>()
            var guard = 0
            while (true) {
                for (i in 0 until list.childCount) {
                    val row = list.getChildAt(i)
                    val top = onScreenTop(row, scroll)
                    val bottom = onScreenBottom(row, scroll)
                    if (top >= absTop(scroll) - px(1f) &&
                        bottom <= absTop(scroll) + scroll.height + px(1f)
                    ) {
                        seen += row
                    }
                }
                if (!scroll.canScrollVertically(1) || guard++ > 40) break
                scroll.scrollBy(0, scroll.height / 2)
                idle()
            }
            val total = list.childCount
            assertEquals(
                "all $total rows (work, organize, tools, danger) were fully visible at some scroll " +
                    "position; seen ${seen.size}",
                total,
                seen.size
            )
            assertTrue(
                "the Close row stays inside the sheet after scrolling",
                absBottom(footer) <= root.height
            )
        }
    }
}

/** 360 dp phone, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SheetFooterEn360Test : DocumentActionsSheetFooterBase()

/** 360 dp phone, Hindi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class SheetFooterHi360Test : DocumentActionsSheetFooterBase()

/** 320 dp phone, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class SheetFooterEn320Test : DocumentActionsSheetFooterBase()

/** 320 dp phone, Hindi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w320dp-h640dp-xhdpi")
class SheetFooterHi320Test : DocumentActionsSheetFooterBase()
