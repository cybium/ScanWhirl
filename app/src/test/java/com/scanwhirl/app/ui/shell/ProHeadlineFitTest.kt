package com.scanwhirl.app.ui.shell

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATLAS-F2 (026_pro_screen_2.0): the promise line "Every Pro tool, one payment, yours forever."
 * ran edge to edge and its first line was clipped at both screen edges, because the subtitle
 * had no side margin and was capped at two lines.
 *
 * The promise and the "ScanWhirl Pro" title must wrap inside the page margins — no line may
 * leave the padding box, no text may be dropped, and the full sentence must be laid out at
 * 200 % on a 320dp phone, in English and Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ProHeadlineFitTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        controller?.destroy()
        controller = null
    }

    private fun proScreen(): Pair<Activity, ProScreen> {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val screen = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        })
        activity.setContentView(screen)
        controller!!.visible()
        val density = activity.resources.displayMetrics.density
        val w = View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY)
        repeat(3) {
            screen.measure(w, h)
            screen.layout(0, 0, View.MeasureSpec.getSize(w), View.MeasureSpec.getSize(h))
        }
        return activity to screen
    }

    private fun texts(root: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (root is TextView) out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) texts(root.getChildAt(i), out)
        return out
    }

    private fun checkInside(activity: Activity, tv: TextView, name: String) {
        val density = activity.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val scale = RuntimeEnvironment.getFontScale()
        val label = "$name @ ${scale}x"
        assertTrue(
            "$label must keep a side margin (left ${tv.paddingLeft}px, right ${tv.paddingRight}px)",
            tv.paddingLeft >= margin && tv.paddingRight >= margin
        )
        val layout = tv.layout
        assertNotNull("$label was laid out", layout)
        val lay = layout!!
        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        assertTrue("$label has a usable width", avail > 0)
        for (line in 0 until lay.lineCount) {
            assertTrue(
                "$label line $line is ${lay.getLineWidth(line).toInt()}px wide but only " +
                    "${avail}px fit between the margins",
                lay.getLineWidth(line) <= avail + 0.5f
            )
            assertEquals("$label line $line is ellipsized", 0, lay.getEllipsisCount(line))
        }
        assertEquals(
            "$label drops the end of its sentence (shows ${lay.getLineEnd(lay.lineCount - 1)} " +
                "of ${tv.text.length} chars)",
            tv.text.length,
            lay.getLineEnd(lay.lineCount - 1)
        )
        val needed = lay.height + tv.totalPaddingTop + tv.totalPaddingBottom
        assertTrue(
            "$label needs ${needed}px but its view is ${tv.height}px tall",
            needed <= tv.height + 0.5f
        )
    }

    @Test
    fun `the Pro all-tools action and title wrap inside the margins at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        val (activity, screen) = proScreen()
        val all = texts(screen)
        // OLD: the compact sales promise (pro_sub_compact) was deleted by COPY_DIET PRO2.
        val expected = activity.getString(
            R.string.copydiet_pro2_all_tools,
            com.scanwhirl.app.ui.kit.ToolRegistry.proCount()
        )
        val promise = all.firstOrNull { it.text.toString() == expected }
        assertNotNull("the single All tools action is on the screen", promise)
        checkInside(activity, promise!!, "All Pro tools")

        val title = all.firstOrNull {
            it.text.toString().startsWith(activity.getString(R.string.pro_title_prefix).trim()) &&
                it.text.toString().contains(activity.getString(R.string.pro_title_pro))
        }
        assertNotNull("the ScanWhirl Pro title is on the screen", title)
        checkInside(activity, title!!, "Pro title")
    }

    @Test
    fun `the Hindi Pro all-tools action wraps inside the margins at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("hi-rIN-w320dp-h640dp-xhdpi")
        val (activity, screen) = proScreen()
        val expected = activity.getString(
            R.string.copydiet_pro2_all_tools,
            com.scanwhirl.app.ui.kit.ToolRegistry.proCount()
        )
        val promise = texts(screen).firstOrNull { it.text.toString() == expected }
        assertNotNull("the Hindi All tools action is on the screen", promise)
        checkInside(activity, promise!!, "All tools (hi)")
    }
}
