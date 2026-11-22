package com.scanwhirl.app.ux130.v2r

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ProScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PRO2 critic fix: the Pro screen keeps ONE "See all N Pro tools" link — the counted one that
 * opens the Tools hub. The old second, un-counted in-place catalogue link is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V2RProToolsExpansionTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    private fun screen(): Triple<Activity, ProScreen, IntArray> {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val all = IntArray(1)
        val pro = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() { all[0]++ }
        })
        activity.setContentView(pro)
        return Triple(activity, pro, all)
    }

    private fun texts(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test(timeout = 30_000)
    fun `the counted See all link is the only one and it opens the Tools hub`() {
        val (activity, pro, all) = screen()
        val counted = activity.getString(R.string.pro_more_tools, ToolRegistry.proCount())
        val more = pro.findViewById<View>(R.id.swProMore) as TextView
        assertEquals("the counted label is preserved for ProCountTest", counted, more.text.toString())
        assertEquals(
            "exactly one counted See all link",
            1,
            texts(pro).count { it.text.toString() == counted }
        )
        assertNull(
            "no second in-place catalogue link",
            pro.findViewById<View>(R.id.v2rSeeAllTools)
        )
        assertNull("no hidden in-place catalogue", pro.findViewById<View>(R.id.v2rAllTools))

        more.performClick()
        assertEquals("the single See all link opens the Tools hub", 1, all[0])
    }

    @Test(timeout = 30_000)
    fun `Hindi keeps the single counted See all link`() {
        RuntimeEnvironment.setQualifiers("hi-rIN-w360dp-h800dp-xhdpi")
        val (activity, pro, _) = screen()
        val counted = activity.getString(R.string.pro_more_tools, ToolRegistry.proCount())
        assertEquals(1, texts(pro).count { it.text.toString() == counted })
        assertNull(pro.findViewById<View>(R.id.v2rSeeAllTools))
        assertNotNull(pro.findViewById<View>(R.id.swProMore))
    }
}
