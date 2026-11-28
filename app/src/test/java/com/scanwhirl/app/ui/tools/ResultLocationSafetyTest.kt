package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.result.ResultSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ResultLocationSafetyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun imageIntent(): Intent = ToolResultActivity.intent(app,
        ResultSpec("form_ready", files = listOf(RsFixtures.jpeg("location", 413, 531, app)), mime = "image/jpeg"))

    @Test(timeout = 30_000)
    fun `image result never says saved before the write`() {
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, imageIntent()).setup()
        try {
            idle()
            val activity = controller.get()
            assertEquals(app.getString(R.string.rs_not_saved_yet),
                activity.findViewById<TextView>(R.id.toolResultName).text.toString())
            val primary = activity.window.decorView.findViewWithTag<TextView>("result-primary")
            assertEquals(app.getString(R.string.rs_save_to_phone), primary.text.toString())
            assertTrue(primary.isEnabled)
            assertFalse(activity.findViewById<TextView>(R.id.toolResultName).isClickable)
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `PDF result has exactly one filled action`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, 1, "Fictional result")
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, ResultSpec("split", docIds = listOf(id)))).setup()
        try {
            idle()
            val activity = controller.get()
            val filled = mutableListOf<View>()
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v.isClickable && (v.background as? GradientDrawable)?.color?.defaultColor ==
                    SwStyle.color(activity, R.color.cyan_primary)) filled += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(activity.window.decorView)
            assertEquals(1, filled.size)
            assertEquals(R.id.toolResultShare, filled.single().id)
            assertTrue(activity.findViewById<TextView>(R.id.toolResultName).text.contains("Fictional result"))
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `cancelled chooser leaves the where-line unchanged`() {
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, imageIntent()).setup()
        try {
            val activity = controller.get()
            idle()
            val before = activity.findViewById<TextView>(R.id.toolResultName).text.toString()
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            activity.findViewById<View>(R.id.toolResultSave).performClick()
            val chooser = requireNotNull(shadowOf(activity).nextStartedActivityForResult).intent
            shadowOf(activity).receiveResult(chooser, Activity.RESULT_CANCELED, null)
            idle()
            assertEquals(before, activity.findViewById<TextView>(R.id.toolResultName).text.toString())
            assertTrue(activity.window.decorView.findViewWithTag<View>("result-primary").isEnabled)
        } finally { controller.destroy() }
    }

    @Test(timeout = 30_000)
    fun `Adjust restores the exact input intent instead of using the output`() {
        val original = Intent(app, ToolSetupActivity::class.java)
            .putExtra("source_uri", "content://fictional/photo")
            .putExtra("min_kb", 20).putExtra("max_kb", 50)
            .putExtra("crop", intArrayOf(1, 2, 413, 531))
        val spec = ResultSpec("form_ready", files = listOf(RsFixtures.jpeg("adjust", 413, 531, app)),
            mime = "image/jpeg", adjustIntent = original)
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, spec)).setup()
        try {
            val activity = controller.get()
            activity.window.decorView.findViewWithTag<View>("result-adjust").performClick()
            val restored = shadowOf(activity).nextStartedActivity
            assertEquals(original.component, restored.component)
            assertEquals(original.getStringExtra("source_uri"), restored.getStringExtra("source_uri"))
            assertEquals(20, restored.getIntExtra("min_kb", 0))
            assertEquals(50, restored.getIntExtra("max_kb", 0))
            assertArrayEquals(original.getIntArrayExtra("crop"), restored.getIntArrayExtra("crop"))
            assertTrue(restored.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue(restored.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        } finally { controller.destroy() }
    }
}
