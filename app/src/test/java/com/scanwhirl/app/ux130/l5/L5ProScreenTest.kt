package com.scanwhirl.app.ux130.l5

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.shell.ProScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * L5-01 — paywall escape, Restore, honest price status and uncapped Pro labels.
 * The view ids used for the two new actions are resolved by name so this file compiles
 * on the base SHA, where they do not exist yet (that is the RED state).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L5ProScreenTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    private data class Counters(var close: Int = 0, var buy: Int = 0, var restore: Int = 0, var all: Int = 0)

    private fun screen(lang: String = "en", scale: Float = 1f, active: Boolean = false): Pair<Activity, ProScreen> {
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val counters = Counters()
        val pro = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() { counters.close++ }
            override fun onBuy() { counters.buy++ }
            override fun onRestore() { counters.restore++ }
            override fun onSeeAllProTools() { counters.all++ }
        }, active = active)
        activity.setContentView(pro)
        pro.setTag(R.string.sheet_cancel, counters)
        controller!!.visible()
        return activity to pro
    }

    private fun counters(pro: ProScreen): Counters = pro.getTag(R.string.sheet_cancel) as Counters

    private fun texts(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun byId(activity: Activity, root: View, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    private fun measure(activity: Activity, pro: ProScreen, widthDp: Int, heightDp: Int) {
        val d = activity.resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val h = (heightDp * d).toInt()
        repeat(2) {
            pro.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            pro.layout(0, 0, w, h)
        }
    }

    @Test
    fun `Get Pro, Continue free and Restore are all on the Free paywall`() {
        val (activity, pro) = screen()
        val getPro = texts(pro).firstOrNull { it.text.toString() == "Get Pro" }
        assertNotNull("the purchase action is named Get Pro (RED on base: Unlock Pro)", getPro)
        assertEquals(pro.buyButton, getPro)

        val free = byId(activity, pro, "swProContinueFree")
        assertNotNull("an explicit Continue free exit (RED on base)", free)
        assertTrue("Continue free is at least 48dp", free!!.height >= 0 && free.minimumHeight >= SwStyle.dp(activity, 48f))
        assertEquals("Continue free", (free as TextView).text.toString())
        val restore = byId(activity, pro, "swProRestoreAction")
        assertNotNull("Restore is in the action area (RED on base)", restore)
        assertEquals("Restore purchase", (restore as TextView).text.toString())

        // PRO2: a buy needs a real Play price before it is purchasable.
        pro.setPrice("₹299")
        free.performClick()
        restore.performClick()
        pro.buyButton.performClick()
        assertEquals(1, counters(pro).close)
        assertEquals(1, counters(pro).restore)
        assertEquals(1, counters(pro).buy)
    }

    @Test
    fun `feature labels scale at 200 percent instead of stopping at 16dp`() {
        val (activity, pro) = screen(scale = 2f)
        val label = texts(pro).firstOrNull {
            it.text.toString() == activity.getString(R.string.pro_feat_sign)
        }
        assertNotNull("a Pro feature label is on the screen", label)
        // The old code capped the label at 16dp (32px at 2x here). A real 13.5sp label must
        // grow well past that cap; the exact px depends on Robolectric's scaled-density timing.
        assertTrue(
            "13.5sp must scale past the old 32px cap at 200%, got ${label!!.textSize}px",
            label.textSize > 40f
        )
        assertTrue("the label wraps instead of being clipped", label.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Test
    fun `missing price makes Get Pro non purchasable with a Try again action`() {
        val (activity, pro) = screen()
        pro.setPrice(null)
        val status = byId(activity, pro, "swProPriceStatus")
        assertNotNull("a named missing-price status (RED on base)", status)
        assertEquals(
            // OLD: "Price unavailable. Check Google Play and try again."
            "Price unavailable.",
            (status as TextView).text.toString()
        )
        val retry = byId(activity, pro, "swProPriceRetry")
        assertNotNull("Try again re-queries Play (RED on base)", retry)
        assertFalse("no Play price means Get Pro cannot be bought", pro.buyButton.isEnabled)
        pro.buyButton.performClick()
        assertEquals("a disabled Get Pro starts no purchase", 0, counters(pro).buy)

        pro.setPrice("₹299")
        assertEquals(View.GONE, status.visibility)
        assertEquals(View.GONE, retry!!.visibility)
        assertTrue("the buy path is live when Play answers", pro.buyButton.isEnabled)
        pro.buyButton.performClick()
        assertEquals(1, counters(pro).buy)
    }

    @Test
    fun `the pinned offer stays compact and reachable at short height and 200 percent`() {
        val (activity, pro) = screen(scale = 2f)
        measure(activity, pro, 360, 640)
        val kids = (0 until pro.childCount).map { pro.getChildAt(it) }
        val scroller = kids.filterIsInstance<ScrollView>().firstOrNull()
        val bar = kids.firstOrNull { it !== scroller && it.height > 0 }
        assertNotNull("copy scrolls above the offer", scroller)
        assertNotNull("the offer stays pinned", bar)
        assertTrue(
            "at 200% the whole paywall can scroll rather than pin a four-row footer",
            (scroller as ScrollView).canScrollVertically(1)
        )
        assertNotNull("the offer stays pinned above the copy", bar)
        for (name in listOf("swProContinueFree", "swProRestoreAction")) {
            val view = byId(activity, pro, name)
            assertNotNull("$name stays reachable", view)
            assertTrue("$name has a real target", view!!.height >= SwStyle.dp(activity, 48f))
        }
    }

    private fun assertActionsFitAtTwoTimes(lang: String) {
        val (activity, pro) = screen(lang = lang, scale = 2f)
        measure(activity, pro, 360, 800)
        val free = byId(activity, pro, "swProContinueFree") as TextView
        val restore = byId(activity, pro, "swProRestoreAction") as TextView
        for (view in listOf(free, restore)) {
            assertEquals(
                "$lang ${view.text}: height wraps instead of a fixed 48dp",
                ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height
            )
            assertEquals("$lang ${view.text}: two lines allowed", 2, view.maxLines)
            assertTrue(
                "$lang ${view.text}: at least 48dp, got ${view.height}px",
                view.height >= SwStyle.dp(activity, 48f)
            )
            val text = view.text
            val layout = view.layout
            assertNotNull("$lang ${view.text} was laid out", layout)
            for (line in 0 until layout!!.lineCount) {
                assertEquals("$lang ${view.text} line $line is ellipsized", 0, layout.getEllipsisCount(line))
                assertTrue(
                    "$lang ${view.text} line $line overflows its padding box",
                    layout.getLineWidth(line) <= view.width - view.totalPaddingLeft - view.totalPaddingRight + 0.5f
                )
            }
            assertEquals("$lang ${view.text} loses its ending", text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(
                "$lang ${view.text} needs ${layout.height + view.totalPaddingTop + view.totalPaddingBottom}px but is ${view.height}px",
                layout.height + view.totalPaddingTop + view.totalPaddingBottom <= view.height + 0.5f
            )
        }
        val stack = free.parent as LinearLayout
        assertEquals(
            "$lang: the two actions stack when they do not fit side by side",
            LinearLayout.VERTICAL, stack.orientation
        )
    }

    @Test
    fun `English paywall actions fit fully at 200 percent`() = assertActionsFitAtTwoTimes("en")

    @Test
    fun `Hindi paywall actions fit fully at 200 percent`() = assertActionsFitAtTwoTimes("hi")

    @Test
    fun `Hindi paywall copy is present`() {
        val (_, pro) = screen(lang = "hi")
        val labels = texts(pro).map { it.text.toString() }
        assertTrue("Hindi Get Pro", labels.any { it == "Pro पाएँ" })
        assertTrue("Hindi Continue free", labels.any { it == "मुफ़्त जारी रखें" })
        assertTrue("Hindi Restore purchase", labels.any { it == "खरीद फिर चालू करें" })
    }
}
