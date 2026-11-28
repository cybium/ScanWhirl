package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.form1.FormAsyncIdle
import com.scanwhirl.app.ui.result.ResultSpec
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.FormUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * J1 harms prevented: a result that claims "saved" before any write, and an Adjust that returns a
 * fresh setup instead of the exact source and rules the person was working with.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class J1ResultHonestyTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val async = FormAsyncIdle()
    private val controllers = mutableListOf<ActivityController<out android.app.Activity>>()

    @Before
    fun setUp() {
        async.install()
        org.robolectric.shadows.ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        controllers.clear()
        org.robolectric.shadows.ShadowDialog.reset()
        async.reset()
    }

    private fun waitUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean =
        async.waitUntil(timeoutMs, condition)

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun j1ResultIntent(): Intent {
        val file = RsFixtures.jpeg("j1-portrait", 413, 531, app)
        return ToolResultActivity.intent(app, ResultSpec(
            "form_ready", files = listOf(file), mime = "image/jpeg",
            note = "413 × 531 px · 32 KB\nOK 32 KB · 200 DPI",
            sourceKept = true,
            facts = ResultSpec.Facts(bytes = file.length(), width = 413, height = 531),
            adjustIntent = Intent(app, FormReadyActivity::class.java)
        ))
    }

    @Test(timeout = 30_000)
    fun `never says saved before the write`() {
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, j1ResultIntent()).setup()
        controllers += controller
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val where = activity.findViewById<TextView>(R.id.toolResultName).text.toString()
        assertTrue("unsaved photo must say so: $where",
            where.startsWith(app.getString(R.string.rs_not_saved_yet)))
        val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
        assertTrue("measured pixels must be shown: $summary", summary.contains("413 × 531"))
        assertTrue("measured size must be shown: $summary", summary.contains("KB"))
        val primary = activity.window.decorView.findViewWithTag<TextView>("result-primary")
        assertNotNull("one filled primary is required", primary)
        assertEquals(app.getString(R.string.rs_save_to_phone), primary.text.toString())
        assertTrue("save must be actionable before it succeeds", primary.isEnabled)
    }

    @Test(timeout = 30_000)
    fun `Adjust restores the exact source and rules`() {
        val controller = Robolectric.buildActivity(FormReadyActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        activity.setCustomValuesForTest(413f, 531f, FormUnit.PX, 200, 20, 50)
        activity.acceptPickedBytes(jpeg(), "fictional-portrait.png")
        assertTrue("photo never loaded", waitUntil { activity.hasImageForTest() })
        activity.findViewById<View>(R.id.formReadyApply).performClick()
        assertTrue("no file made", waitUntil { activity.lastOutputForTest() != null })
        shadowOf(Looper.getMainLooper()).idle()

        val adjust = activity.lastAdjustIntentForTest
        assertNotNull("the result must be able to return to the exact inputs", adjust)
        assertEquals(FormReadyActivity::class.java.name, adjust!!.component!!.className)
        assertEquals(413f, adjust.getFloatExtra("form1_width", 0f), 0f)
        assertEquals(531f, adjust.getFloatExtra("form1_height", 0f), 0f)
        assertEquals(20, adjust.getIntExtra("form1_min", 0))
        assertEquals(50, adjust.getIntExtra("form1_max", 0))
        assertTrue(adjust.hasExtra("form1_image"))

        // A fresh process replaying that same Adjust snapshot sees the same inputs.
        val resumed = Robolectric.buildActivity(FormReadyActivity::class.java, adjust).setup()
        controllers += resumed
        val restored = resumed.get()
        assertTrue("source did not come back", waitUntil { restored.hasImageForTest() })
        assertEquals(413, restored.currentWidthPxForTest())
        assertEquals(531, restored.currentHeightPxForTest())
        assertEquals(20, restored.currentMinKbForTest())
        assertEquals(50, restored.currentMaxKbForTest())
        assertEquals(200, restored.currentDpiForTest())
    }
}
