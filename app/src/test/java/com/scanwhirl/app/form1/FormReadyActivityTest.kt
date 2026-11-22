package com.scanwhirl.app.form1

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormUnit
import org.junit.After
import org.junit.Before
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import java.io.ByteArrayOutputStream

/**
 * FORM1: the screen as a person uses it (with a test seam for the SAF picker, which cannot
 * run under Robolectric). Every async step is bounded by a timeout so a hung worker fails
 * instead of hanging the gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class FormReadyActivityTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val controllers = mutableListOf<ActivityController<FormReadyActivity>>()

    @Before
    fun setUp() {
        // Both are process-wide test hooks. A test that fails mid-flight must not leak a
        // blocking encode gate or a stale sheet into the next test in the same fork.
        ExactKbEncoder.beforeEncode = null
        org.robolectric.shadows.ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        controllers.clear()
        ExactKbEncoder.beforeEncode = null
        org.robolectric.shadows.ShadowDialog.reset()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    private fun waitUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        idle()
        return condition()
    }

    private fun makeActivity(): FormReadyActivity =
        Robolectric.buildActivity(FormReadyActivity::class.java).setup().also { controllers += it }.get()

    private fun jpeg(color: Int, w: Int = 600, h: Int = 500): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawColor(color)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun textOf(activity: FormReadyActivity, id: Int): String =
        activity.findViewById<TextView>(id).text.toString()

    private fun walk(root: View, out: MutableList<View>) {
        out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), out)
    }

    @Test(timeout = 30_000)
    fun `intent and tool id are exposed for the lead to wire`() {
        assertEquals("form_ready", FormReadyActivity.TOOL_ID)
        val intent = FormReadyActivity.intent(app)
        assertEquals(FormReadyActivity::class.java.name, intent.component!!.className)
        assertNotNull(intent)
    }

    @Test(timeout = 30_000)
    fun `selecting a preset updates dimensions band and crop aspect`() {
        val a = makeActivity()
        idle()
        a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
        idle()
        assertEquals(140, a.currentWidthPxForTest())
        assertEquals(60, a.currentHeightPxForTest())
        assertEquals(10, a.currentMinKbForTest())
        assertEquals(20, a.currentMaxKbForTest())
        assertTrue(a.currentCleanupForTest())
        assertTrue(textOf(a, R.id.formReadyReadout).contains("140"))
        assertTrue(textOf(a, R.id.formReadyReadout).contains("60"))
    }

    @Test(timeout = 30_000)
    fun `custom unit and dpi give a live pixel readout`() {
        val a = makeActivity()
        idle()
        a.findViewById<View>(R.id.formReadyPresetCustom).performClick()
        idle()
        a.setCustomValuesForTest(3.5f, 4.5f, FormUnit.CM, 300, 20, 50)
        idle()
        assertEquals(413, a.currentWidthPxForTest())
        assertEquals(531, a.currentHeightPxForTest())
        val readout = textOf(a, R.id.formReadyReadout)
        assertTrue(readout, readout.contains("413") && readout.contains("531"))
    }

    @Test(timeout = 30_000)
    fun `full flow makes a measured file and opens the shared result`() {
        val a = makeActivity()
        idle()
        a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
        idle()
        a.acceptPickedBytes(jpeg(Color.WHITE))
        assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })

        a.findViewById<View>(R.id.formReadyApply).performClick()
        // Robolectric's nextStartedActivity CONSUMES the intent, so poll on our own output seam
        // and read the started intent exactly once.
        assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
        idle()
        val next = shadowOf(a).nextStartedActivity
        assertNotNull("result never started", next)
        assertEquals(ToolResultActivity::class.java.name, next!!.component!!.className)
        val file = a.lastOutputForTest()!!
        assertTrue("file missing: ${file.path}", file.isFile && file.length() > 0L)
        assertTrue("larger than 20 KB: ${file.length()}", file.length() <= 20_000L)
        assertEquals(200, ExactKbEncoder.readJfifDpi(file.readBytes()))
        val note = a.lastNoteForTest.orEmpty()
        assertTrue("note missing measured size: $note", note.contains("bytes"))
        assertTrue("note missing pixels: $note", note.contains("140") && note.contains("60"))
        assertTrue("note missing DPI: $note", note.contains("DPI"))
        assertTrue("note must say the original is untouched: $note", note.contains("original"))
    }

    @Test(timeout = 30_000)
    fun `a 12000 pixel entry is refused before any bitmap is allocated`() {
        var encodeCalls = 0
        ExactKbEncoder.beforeEncode = { encodeCalls++ }
        try {
            val a = makeActivity()
            idle()
            a.setCustomValuesForTest(12000f, 12000f, FormUnit.PX, 200, 20, 50)
            idle()
            val error = a.findViewById<TextView>(R.id.formReadyError)
            assertEquals(app.getString(R.string.form1_size_too_large), error.text.toString())
            assertTrue(error.visibility == View.VISIBLE)
            val apply = a.findViewById<View>(R.id.formReadyApply)
            assertFalse("Apply must be disabled for an impossible size", apply.isEnabled)
            apply.performClick()
            idle()
            assertNull("no file may be produced", a.lastOutputForTest())
            assertNull("no result may open", shadowOf(a).nextStartedActivity)
            assertEquals("encoder must never see this size", 0, encodeCalls)
        } finally {
            ExactKbEncoder.beforeEncode = null
        }
    }

    @Test(timeout = 30_000)
    fun `an OutOfMemoryError during encode is a localized failure not a crash`() {
        val a = makeActivity()
        idle()
        a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
        idle()
        a.acceptPickedBytes(jpeg(Color.WHITE))
        assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
        ExactKbEncoder.beforeEncode = { throw OutOfMemoryError("injected test OOM") }
        try {
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("no honest failure message", waitUntil {
                val e = a.findViewById<TextView>(R.id.formReadyError)
                e.visibility == View.VISIBLE && e.text.toString() == app.getString(R.string.form1_out_of_memory)
            })
            assertNull("no file after OOM", a.lastOutputForTest())
            assertNull("no result after OOM", shadowOf(a).nextStartedActivity)
        } finally {
            ExactKbEncoder.beforeEncode = null
        }
    }

    @Test(timeout = 30_000)
    fun `ActivityController recreate restores preset fields and crop rectangle`() {
        val controller: ActivityController<FormReadyActivity> =
            Robolectric.buildActivity(FormReadyActivity::class.java).setup()
        val a = controller.get()
        idle()
        a.findViewById<View>(R.id.formReadyPresetCustom).performClick()
        idle()
        a.acceptPickedBytes(jpeg(Color.WHITE))
        assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
        a.setCustomValuesForTest(2.5f, 1.2f, FormUnit.CM, 300, 12, 30)
        val rect = RectF(10f, 20f, 100f, 60f)
        a.setCropRectForTest(rect)
        idle()

        controller.recreate()
        idle()
        val restored = controller.get()
        // Wait on the crop view's own published state, not just the activity's bitmap field:
        // setImage() and setCropRect() run together on the main thread after the decode.
        assertTrue("crop view must be ready after recreation", waitUntil { restored.cropReadyForTest() })
        assertEquals("2.5 cm at 300 DPI", 295, restored.currentWidthPxForTest())
        assertEquals("1.2 cm at 300 DPI", 142, restored.currentHeightPxForTest())
        assertEquals(12, restored.currentMinKbForTest())
        assertEquals(30, restored.currentMaxKbForTest())
        assertEquals(300, restored.currentDpiForTest())
        assertEquals("2.50", textOf(restored, R.id.formReadyWidth))
        assertEquals("1.20", textOf(restored, R.id.formReadyHeight))
        assertEquals(rect, restored.cropRectForTest())
        idle()
        assertEquals("the crop must not be re-fitted after it is shown", rect, restored.cropRectForTest())
        assertTrue(textOf(restored, R.id.formReadyReadout).contains("295"))
        controller.pause().stop().destroy()
    }

    @Test(timeout = 30_000)
    fun `labels and controls fit at 200 percent font in English and Hindi`() {
        for (lang in listOf("en", "hi")) {
            RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
            RuntimeEnvironment.setFontScale(2.0f)
            val controller = Robolectric.buildActivity(FormReadyActivity::class.java).setup()
            val a = controller.get()
            idle()
            val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val density = a.resources.displayMetrics.density
            val w = (360f * density).toInt()
            val h = (800f * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
            val views = mutableListOf<View>()
            walk(root, views)
            val clickable = views.filter { it.isClickable && it.visibility == View.VISIBLE }
            assertTrue("$lang: no controls to audit", clickable.isNotEmpty())
            for (v in clickable) {
                val lpHeight = (v.layoutParams?.height ?: 0)
                assertTrue(
                    "$lang: ${v.javaClass.simpleName} touch target ${v.minimumHeight}/${lpHeight}px",
                    v.minimumHeight >= (48 * density).toInt() ||
                        lpHeight >= (48 * density).toInt() ||
                        v.height >= (48 * density).toInt()
                )
            }
            for (v in views.filterIsInstance<TextView>()) {
                if (v.text.isNotBlank() && v.visibility == View.VISIBLE) {
                    assertNull("$lang: clipped label \"${v.text}\"", v.ellipsize)
                }
            }
            assertTrue("$lang: readout is empty", textOf(a, R.id.formReadyReadout).isNotBlank())
            assertTrue("$lang: form title is empty", textOf(a, R.id.formReadyExample).isNotBlank())
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `selected chips expose their state to TalkBack`() {
        val a = makeActivity()
        idle()
        val chosen = a.findViewById<TextView>(R.id.formReadyPresetSignature)
        val other = a.findViewById<TextView>(R.id.formReadyPresetPhoto)
        chosen.performClick()
        idle()
        assertTrue("selected chip must carry isSelected", chosen.isSelected)
        assertFalse("unselected chip must not carry isSelected", other.isSelected)
        for ((chip, expected) in listOf(chosen to true, other to false)) {
            val info = AccessibilityNodeInfoCompat.obtain(chip)
            try {
                ViewCompat.onInitializeAccessibilityNodeInfo(chip, info)
                assertTrue("chip must be checkable", info.isCheckable)
                assertEquals("chip checked state", expected, info.isChecked)
                assertEquals(
                    "state description",
                    app.getString(if (expected) R.string.form1_selected else R.string.form1_not_selected),
                    info.stateDescription?.toString()
                )
            } finally {
                info.recycle()
            }
        }
    }
}
