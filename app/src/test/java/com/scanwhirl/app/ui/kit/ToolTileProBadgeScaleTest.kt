package com.scanwhirl.app.ui.kit

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.shell.ToolsHubScreen
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
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

/**
 * B2-M1 (real-phone sweep 2026-11-19, build 2 = 1485874c): on the Home quick row at 200 % system
 * font the gold PRO badge broke apart into "PR" / "O" over two lines and sat on top of the tool
 * glyph, on the Sign and To Word tiles, in English and in Hindi.
 *
 * UI_PLAN §0.11: Pro is always gold plus the word "Pro" or a lock. A one-line badge with the
 * whole word is the rule; it either fits in the corridor beside the glyph or moves below the
 * label, but it never wraps and never covers the icon.
 *
 * Measured on the real view tree at 360dp in English and Hindi, Free (so every Pro tile shows the
 * badge), at 100 %, 130 % and 200 % font.
 */
abstract class ToolTileProBadgeBase {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        // Free: every Pro tile draws its badge (the sweep's "act as a Free user" switch).
        ProGate.setTestOverride(app, false)
        Prefs.setQuickTools(app, Prefs.DEFAULT_QUICK_TOOLS)
        Prefs.setLocaleTag(app, "")
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Badge doc")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
        Prefs.resetQuickTools(app)
        Prefs.setLocaleTag(app, "")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layoutRoot(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        idle()
        return root
    }

    private fun offsetX(v: View, ancestor: View): Int {
        var x = 0
        var c: View? = v
        while (c != null && c !== ancestor) {
            x += c.left
            c = c.parent as? View
        }
        return x
    }

    private fun offsetY(v: View, ancestor: View): Int {
        var y = 0
        var c: View? = v
        while (c != null && c !== ancestor) {
            y += c.top
            c = c.parent as? View
        }
        return y
    }

    private fun rect(v: View, root: View): IntArray {
        val l = offsetX(v, root)
        val t = offsetY(v, root)
        return intArrayOf(l, t, l + v.width, t + v.height)
    }

    private fun overlap(a: IntArray, b: IntArray): Boolean =
        a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    /**
     * Problems for one tile's PRO badge. A badge that is not shown (Free-only, Soon tiles) is
     * skipped: this test only judges the badge that is actually drawn.
     */
    private fun badgeProblems(tile: ToolTile, root: View, scale: Float): List<String> {
        val tag = tile.proTag
        if (!tag.isVisible) return emptyList()
        val where = "@$scale \"${tile.labelView.text}\""
        val out = mutableListOf<String>()
        if (tag.width <= 0 || tag.height <= 0) {
            out += "$where: the PRO badge was never laid out (${tag.width}x${tag.height})"
            return out
        }
        val layout = tag.layout
        if (layout == null) {
            out += "$where: the PRO badge has no text layout"
            return out
        }
        if (layout.lineCount != 1) {
            out += "$where: the PRO badge draws ${layout.lineCount} lines " +
                "(B2-M1: \"PR\" / \"O\" at 200 %)"
        }
        val fits = tag.width - tag.totalPaddingLeft - tag.totalPaddingRight
        if (layout.getLineMax(0) > fits + 0.5f) {
            out += "$where: the PRO badge line is ${layout.getLineMax(0)}px but its box fits " +
                "$fits px (clipped word)"
        }
        // Never on the glyph itself, at any font size.
        val glyph = rect(tile.iconView, root)
        if (overlap(rect(tag, root), glyph)) {
            out += "$where: the PRO badge covers the tool icon (${rect(tag, root).toList()} vs " +
                "${glyph.toList()})"
        }
        // B2-M4's neighbour: at large fonts the badge must leave the 48dp icon container
        // entirely (the sweep saw it sitting on the icon at 200 %).
        if (scale >= 1.6f) {
            val box = rect(tile.iconBox, root)
            if (overlap(rect(tag, root), box)) {
                out += "$where: the PRO badge sits on the icon container at ${scale}x font " +
                    "(${rect(tag, root).toList()} vs ${box.toList()})"
            }
        }
        val tileRect = rect(tile, root)
        val tagRect = rect(tag, root)
        if (tagRect[0] < tileRect[0] || tagRect[2] > tileRect[2]) {
            out += "$where: the PRO badge leaves its tile (${tagRect.toList()} vs ${tileRect.toList()})"
        }
        if (tagRect[3] > tileRect[3]) {
            out += "$where: the PRO badge hangs below its tile (${tagRect.toList()} vs ${tileRect.toList()})"
        }
        return out
    }

    /** ATLAS-F2 (002): the Pro chip on a full-width row shares the name's line, after it. */
    private fun rowBadgeProblems(row: ToolRow, root: View, scale: Float): List<String> {
        val tag = row.proTag
        if (!tag.isVisible) return emptyList()
        val where = "@$scale \"${row.labelView.text}\""
        val out = mutableListOf<String>()
        if (tag.width <= 0 || tag.height <= 0) {
            out += "$where: the PRO chip was never laid out (${tag.width}x${tag.height})"
            return out
        }
        val layout = tag.layout
        if (layout == null) {
            out += "$where: the PRO chip has no text layout"
            return out
        }
        if (layout.lineCount != 1) {
            out += "$where: the PRO chip draws ${layout.lineCount} lines (the word must be one line)"
        }
        val fits = tag.width - tag.totalPaddingLeft - tag.totalPaddingRight
        if (layout.getLineMax(0) > fits + 0.5f) {
            out += "$where: the PRO chip line is ${layout.getLineMax(0)}px but its box fits $fits px"
        }
        if (overlap(rect(tag, root), rect(row.iconBox, root))) {
            out += "$where: the PRO chip covers the tool icon"
        }
        val rowRect = rect(row, root)
        val tagRect = rect(tag, root)
        if (tagRect[0] < rowRect[0] || tagRect[2] > rowRect[2]) {
            out += "$where: the PRO chip leaves its row (${tagRect.toList()} vs ${rowRect.toList()})"
        }
        if (tagRect[3] > rowRect[3]) {
            out += "$where: the PRO chip hangs below its row"
        }
        return out
    }

    @Test
    fun `the pro badge stays one line and off the icon at 100 130 and 200 percent font`() {
        val problems = mutableListOf<String>()
        val diag = mutableListOf<String>()
        var badges = 0
        for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    val root = layoutRoot(activity)
                    // The Home quick row is gone (UI-B1 #1); the same entry is measured on the
                    // Tools tab, where every Pro entry still draws its badge in Free.
                    activity.findViewById<View>(R.id.swNavTools).performClick()
                    idle()
                    val hubRoot = layoutRoot(activity)
                    val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                    for (row in ToolsHubScreen.rows(body)) {
                        problems += rowBadgeProblems(row, hubRoot, scale).map { "hub $it" }
                        if (row.proTag.isVisible) {
                            badges++
                            diag += "@$scale hub \"${row.labelView.text}\" row=${row.width} " +
                                "tag=${row.proTag.width}x${row.proTag.height} " +
                                "lines=${row.proTag.layout?.lineCount} " +
                                "tagTop=${row.proTag.top} labelTop=${row.labelView.top}"
                        }
                    }
                }
            }
        }
        assertTrue("no PRO badge was found: the test would prove nothing", badges > 0)
        assertTrue(
            "the PRO badge breaks or covers the icon (B2-M1):\n" + problems.joinToString("\n") +
                "\n--- badges ---\n" + diag.joinToString("\n"),
            problems.isEmpty()
        )
    }
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ToolTileProBadgeEnTest : ToolTileProBadgeBase()

/** The same phone in Hindi, where the sweep also saw "PR" / "O". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class ToolTileProBadgeHiTest : ToolTileProBadgeBase()
