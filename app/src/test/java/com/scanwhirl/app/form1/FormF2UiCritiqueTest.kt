package com.scanwhirl.app.form1

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import kotlin.math.abs

/**
 * F2 UI-critique fixes for the Form photo screen (shots B1_19 to B1_22). Every lifecycle test
 * owns its ActivityController and destroys it in a finally block; every async wait is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormF2UiCritiqueTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        app.getSharedPreferences(FormPresets.PREFS_SAVED_PRESET, 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
        app.getSharedPreferences(FormPresets.PREFS_SAVED_PRESET, 0).edit().clear().commit()
    }

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun controller(): ActivityController<FormReadyActivity> =
        Robolectric.buildActivity(FormReadyActivity::class.java).setup()

    private fun textOf(a: FormReadyActivity, id: Int): String = a.findViewById<TextView>(id).text.toString()

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun setBand(a: FormReadyActivity, min: String, max: String) {
        a.findViewById<EditText>(R.id.formReadyMinKb).setText(min)
        a.findViewById<EditText>(R.id.formReadyMaxKb).setText(max)
        idle()
    }

    private fun sizeSp(a: FormReadyActivity, view: TextView): Float =
        view.textSize / a.resources.displayMetrics.scaledDensity

    private fun assertRole(
        a: FormReadyActivity, view: TextView, size: Float, colorRes: Int, label: String
    ) {
        assertEquals("$label size", size, sizeSp(a, view), 0.5f)
        assertEquals("$label colour", ContextCompat.getColor(app, colorRes), view.textColors.defaultColor)
    }

    @Test(timeout = 30_000)
    fun `choose photo sits directly below the presets and the empty crop canvas stays hidden`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val row3 = a.findViewById<View>(R.id.formReadyPresetRow3)
            val column = row3.parent as ViewGroup
            val pick = a.findViewById<View>(R.id.formReadyPick)
            assertEquals(
                "Choose photo must be the very next control after the preset grid",
                pick,
                column.getChildAt(column.indexOfChild(row3) + 1)
            )
            assertEquals(View.VISIBLE, pick.visibility)
            assertEquals("empty preview must not take up space", View.GONE, a.findViewById<View>(R.id.formReadyCrop).visibility)

            a.acceptPickedBytes(jpeg())
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
            assertEquals("preview appears only once a photo exists", View.VISIBLE, a.findViewById<View>(R.id.formReadyCrop).visibility)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `short warning is two lines and the rest hides behind an expandable about control`() {
        for (lang in listOf("en", "hi")) {
            RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
            val c = controller()
            try {
                val a = c.get()
                idle()
                assertEquals(app.getString(R.string.form1_notice), textOf(a, R.id.formReadyNotice))
                assertTrue("warning must stay short", textOf(a, R.id.formReadyNotice).length <= 90)
                val details = a.findViewById<View>(R.id.formReadyAboutDetails)
                assertEquals(View.GONE, details.visibility)
                assertTrue("rest of the warning must be present", textOf(a, R.id.formReadyAboutDetails).isNotBlank())

                val toggle = a.findViewById<View>(R.id.formReadyAboutToggle)
                toggle.performClick()
                idle()
                assertEquals("About must expand", View.VISIBLE, details.visibility)
                toggle.performClick()
                idle()
                assertEquals("About must collapse again", View.GONE, details.visibility)
            } finally {
                c.pause().stop().destroy()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `preset saved feedback is neutral beside the preset buttons and never clipped`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val actions = a.findViewById<View>(R.id.formReadySavePreset).parent as ViewGroup
            val status = a.findViewById<TextView>(R.id.formReadyPresetStatus)
            assertEquals(
                "feedback must sit next to the preset controls",
                status,
                (status.parent as ViewGroup).getChildAt((status.parent as ViewGroup).indexOfChild(actions) + 1)
            )
            assertNull("feedback must never clip", status.ellipsize)
            assertEquals(Int.MAX_VALUE, status.maxLines)
            assertFalse("feedback must be hidden until an action", status.isShown)

            a.findViewById<View>(R.id.formReadySavePreset).performClick()
            idle()
            assertEquals(app.getString(R.string.form1_preset_saved), textOf(a, R.id.formReadyPresetStatus))
            assertEquals(View.VISIBLE, status.visibility)
            assertTrue(
                "preset feedback must not use the red error colour",
                status.textColors.defaultColor != a.findViewById<TextView>(R.id.formReadyError).textColors.defaultColor
            )
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `preset buttons have an eight dp gap between them`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val density = a.resources.displayMetrics.density
            root.measure(
                View.MeasureSpec.makeMeasureSpec((360f * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((800f * density).toInt(), View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            for (id in listOf(R.id.formReadyPresetRow1, R.id.formReadyPresetRow2)) {
                val row = a.findViewById<ViewGroup>(id)
                val gap = row.getChildAt(1).left - row.getChildAt(0).right
                assertTrue("row $id gap $gap px, expected ${8f * density}", abs(gap - 8f * density) <= 1f)
            }
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `each failing KB rule gets its own specific message`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val error = a.findViewById<TextView>(R.id.formReadyBandError)

            setBand(a, "", "50")
            assertEquals(app.getString(R.string.form1_kb_blank), textOf(a, R.id.formReadyBandError))
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            setBand(a, "12.5", "50")
            assertEquals(app.getString(R.string.form1_kb_decimal), textOf(a, R.id.formReadyBandError))
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            setBand(a, "0", "50")
            assertEquals(app.getString(R.string.form1_kb_min_nonpositive), textOf(a, R.id.formReadyBandError))
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            setBand(a, "20", "0")
            assertEquals(app.getString(R.string.form1_kb_max_below_min, 20), textOf(a, R.id.formReadyBandError))
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            setBand(a, "90", "50")
            assertEquals(app.getString(R.string.form1_kb_max_below_min, 90), textOf(a, R.id.formReadyBandError))
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            setBand(a, "20", "50")
            assertEquals(View.GONE, error.visibility)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `cleanup control exists only in signature mode and never applies elsewhere`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val box = a.findViewById<View>(R.id.formReadyCleanup)
            assertEquals("photo mode must not show signature cleanup", View.GONE, box.visibility)

            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            assertEquals(View.VISIBLE, box.visibility)
            assertTrue(a.currentCleanupForTest())

            a.findViewById<View>(R.id.formReadySavePreset).performClick()
            idle()
            a.findViewById<View>(R.id.formReadyPresetPhoto).performClick()
            idle()
            assertEquals(View.GONE, box.visibility)
            assertFalse(a.currentCleanupForTest())

            a.findViewById<View>(R.id.formReadyUsePreset).performClick()
            idle()
            assertEquals("a saved cleanup flag must not leak into photo mode", View.GONE, box.visibility)
            assertFalse(a.currentCleanupForTest())

            a.setCustomValuesForTest(140f, 60f, FormUnit.PX, 200, 10, 20)
            idle()
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `copy diet roles promote deciding values without brightening every line`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            assertRole(a, a.findViewById(R.id.formReadyTitle), 22f, R.color.f2_text_primary, "title")
            assertRole(a, a.findViewById(R.id.formReadyNotice), 14f, R.color.f2_text_secondary, "summary")
            assertRole(a, a.findViewById(R.id.formReadyAboutToggle), 14f, R.color.f2_action_accent, "More")
            assertRole(a, a.findViewById(R.id.formReadyExample), 14f, R.color.f2_text_primary, "preset facts")
            assertRole(a, a.findViewById(R.id.formReadyReadout), 16f, R.color.f2_text_primary, "readout")
            assertRole(a, a.findViewById(R.id.formReadyWidthLabel), 14f, R.color.f2_text_primary, "field label")
            assertRole(a, a.findViewById<EditText>(R.id.formReadyWidth), 16f, R.color.f2_text_primary, "value")
            assertRole(a, a.findViewById(R.id.formReadyBandError), 14f, R.color.f2_danger, "field error")
            assertRole(a, a.findViewById(R.id.formReadyError), 14f, R.color.f2_danger, "blocking error")
            assertRole(
                a, a.findViewById(R.id.formReadyPresetStatus), 14f,
                R.color.f2_text_secondary, "neutral feedback"
            )
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `make file is pinned only when the form is actionable`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val bar = a.findViewById<View>(R.id.formReadyApplyBar)
            assertEquals(View.GONE, bar.visibility)

            setBand(a, "", "50")
            assertEquals(View.GONE, bar.visibility)

            setBand(a, "20", "50")
            a.acceptPickedBytes(jpeg())
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
            assertEquals("pinned Make file must appear when the form is ready", View.VISIBLE, bar.visibility)

            setBand(a, "90", "50")
            assertEquals("pinned Make file must retreat when validation fails", View.GONE, bar.visibility)

            var parent: ViewParent? = a.findViewById<View>(R.id.formReadyApply).parent
            while (parent != null) {
                assertFalse("Make file must not scroll away with the form", parent is ScrollView)
                parent = parent.parent
            }
        } finally {
            c.pause().stop().destroy()
        }
    }
}
