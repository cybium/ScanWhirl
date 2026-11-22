package com.scanwhirl.app.ux130.v2r

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ProScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** V2R phone finding (b): "See all Pro tools" must expand the honest full catalogue in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V2RProToolsExpansionTest {

    private var controller: org.robolectric.android.controller.ActivityController<Activity>? = null

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    private fun screen(): Pair<Activity, ProScreen> {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller!!.get()
        val pro = ProScreen(activity, object : ProScreen.Callbacks {
            override fun onClose() = Unit
            override fun onBuy() = Unit
            override fun onRestore() = Unit
            override fun onSeeAllProTools() = Unit
        })
        activity.setContentView(pro)
        return activity to pro
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

    private fun measure(activity: Activity, root: View) {
        val d = activity.resources.displayMetrics.density
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((800 * d).toInt(), View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }

    @Test
    fun `see all keeps its counted label and expands every named tool with honest tiers`() {
        val (activity, pro) = screen()
        val more = pro.findViewById<View>(R.id.swProMore) as TextView
        assertTrue(
            "the counted label is preserved for ProCountTest",
            more.text.toString().startsWith("See all ") && more.text.toString().endsWith(" Pro tools")
        )
        assertNull("the catalogue is not built until asked", pro.findViewById<View>(R.id.v2rAllTools))

        pro.findViewById<View>(R.id.v2rSeeAllTools)!!.performClick()
        val box = pro.findViewById<View>(R.id.v2rAllTools)
        assertNotNull("the catalogue expands in place", box)
        assertTrue("the catalogue is visible", box!!.isShown)
        val labels = texts(box).map { it.text.toString() }
        for (tool in ToolRegistry.all) {
            assertTrue("${tool.id} is named in the list: $labels", labels.contains(activity.getString(tool.title)))
        }
        assertTrue("Pro tier is labelled", labels.any { it == "Pro" })
        assertTrue("Free tier is labelled", labels.any { it == "Free" })
        val rowHeights = (0 until (box as ViewGroup).childCount).map { box.getChildAt(it) }
            .filter { it is ViewGroup && it.minimumHeight > 0 }
        assertTrue("every tool row keeps a 48dp target", rowHeights.all {
            it.minimumHeight >= SwStyle.dp(activity, 48f)
        })

        pro.findViewById<View>(R.id.v2rShowLess)!!.performClick()
        assertFalse("the catalogue collapses again", box.isShown)
    }

    @Test
    fun `Hindi catalogue uses the glossary tier words`() {
        RuntimeEnvironment.setQualifiers("hi-rIN-w360dp-h800dp-xhdpi")
        val (_, pro) = screen()
        pro.findViewById<View>(R.id.v2rSeeAllTools)!!.performClick()
        val box = pro.findViewById<View>(R.id.v2rAllTools)!!
        val labels = texts(box).map { it.text.toString() }
        assertTrue("Hindi free tier", labels.any { it == "मुफ़्त" })
        assertTrue("Hindi soon label", labels.any { it.contains("जल्द") })
        assertFalse("no loanword for free", labels.any { it == "फ्री" })
    }
}
