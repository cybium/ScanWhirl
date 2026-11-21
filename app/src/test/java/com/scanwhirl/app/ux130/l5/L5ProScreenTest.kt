package com.scanwhirl.app.ux130.l5

import android.app.Activity
import android.view.View
import android.view.ViewGroup
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
        val expectedPx = 13.5f * label!!.resources.displayMetrics.scaledDensity
        assertTrue(
            "13.5sp at 2x needs $expectedPx px, got ${label.textSize}px (capped on base at 32px)",
            label.textSize >= expectedPx - 1f
        )
        assertTrue("the label wraps instead of being clipped", label.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Test
    fun `missing price is honest while the safe billing path stays enabled`() {
        val (activity, pro) = screen()
        pro.setPrice(null)
        val status = byId(activity, pro, "swProPriceStatus")
        assertNotNull("a named missing-price status (RED on base)", status)
        assertEquals(
            "Price unavailable. Check Google Play and try again.",
            (status as TextView).text.toString()
        )
        assertTrue("no price does not mean payment is disabled", pro.buyButton.isEnabled)
        pro.setPrice("₹299")
        assertEquals(View.GONE, status.visibility)
        assertTrue("the buy path is still live when Play answers", pro.buyButton.isEnabled)
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

    @Test
    fun `Hindi paywall copy is present`() {
        val (_, pro) = screen(lang = "hi")
        val labels = texts(pro).map { it.text.toString() }
        assertTrue("Hindi Get Pro", labels.any { it == "Pro पाएँ" })
        assertTrue("Hindi Continue free", labels.any { it == "मुफ़्त जारी रखें" })
        assertTrue("Hindi Restore purchase", labels.any { it == "खरीदारी वापस लाएँ" })
    }
}
