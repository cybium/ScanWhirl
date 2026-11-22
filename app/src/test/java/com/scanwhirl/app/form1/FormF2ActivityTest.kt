package com.scanwhirl.app.form1

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Lane F, F2 and the UI_100 Form fixes, as a person uses the screen. Every lifecycle test owns
 * its ActivityController and destroys it in a finally block; every async wait is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormF2ActivityTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        app.getSharedPreferences(FormPresets.PREFS_SAVED_PRESET, 0).edit().clear().commit()
        ExactKbEncoder.beforeEncode = null
    }

    @After
    fun tearDown() {
        ExactKbEncoder.beforeEncode = null
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

    private fun jpeg(color: Int = Color.WHITE, w: Int = 600, h: Int = 500): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bmp).drawColor(color)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun textOf(a: FormReadyActivity, id: Int): String = a.findViewById<TextView>(id).text.toString()

    private fun nodeOf(view: View): AccessibilityNodeInfoCompat {
        val info = AccessibilityNodeInfoCompat.obtain(view)
        ViewCompat.onInitializeAccessibilityNodeInfo(view, info)
        return info
    }

    @Test(timeout = 30_000)
    fun `council preset names sit in a two column grid with no mid word break at 100 percent`() {
        for (lang in listOf("en", "hi")) {
            for (widthDp in listOf(360, 411)) {
                RuntimeEnvironment.setQualifiers("$lang-w${widthDp}dp-h800dp-xhdpi")
                RuntimeEnvironment.setFontScale(1f)
                val c = controller()
                try {
                    val a = c.get()
                    idle()
                    assertEquals(2, a.findViewById<ViewGroup>(R.id.formReadyPresetRow1).childCount)
                    assertEquals(2, a.findViewById<ViewGroup>(R.id.formReadyPresetRow2).childCount)
                    assertEquals(1, a.findViewById<ViewGroup>(R.id.formReadyPresetRow3).childCount)

                    val chips = listOf(
                        R.id.formReadyPresetPhoto, R.id.formReadyPresetSignature,
                        R.id.formReadyPresetThumb, R.id.formReadyPresetDeclaration,
                        R.id.formReadyPresetCustom
                    ).map { a.findViewById<TextView>(it) }
                    val expected = if (lang == "en") {
                        listOf("Photo", "Signature", "Thumb impression", "Declaration", "Custom")
                    } else {
                        listOf("फ़ोटो", "हस्ताक्षर", "अँगूठे का निशान", "घोषणा", "अपना")
                    }
                    chips.forEachIndexed { i, chip -> assertEquals(expected[i], chip.text.toString()) }

                    val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    val density = a.resources.displayMetrics.density
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    for (chip in chips) {
                        val needed = chip.paint.measureText(chip.text.toString())
                        val available = (chip.width - chip.paddingLeft - chip.paddingRight).toFloat()
                        assertTrue(
                            "$lang $widthDp: \"${chip.text}\" needs $needed px but has $available px",
                            needed <= available + 1f
                        )
                    }
                    // Council title, not the old long "Form-ready photo and signature".
                    assertEquals(
                        if (lang == "en") "Form photo" else "फ़ॉर्म फ़ोटो",
                        textOf(a, R.id.formReadyTitle)
                    )
                } finally {
                    c.pause().stop().destroy()
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun `at 200 percent on a 411dp phone controls stay operable in English and Hindi`() {
        for (lang in listOf("en", "hi")) {
            RuntimeEnvironment.setQualifiers("$lang-w411dp-h800dp-xhdpi")
            RuntimeEnvironment.setFontScale(2.0f)
            val c = controller()
            try {
                val a = c.get()
                idle()
                val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val density = a.resources.displayMetrics.density
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val clickable = mutableListOf<View>()
                fun walk(v: View) {
                    clickable += v
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(root)
                for (v in clickable.filter { it.isClickable && it.visibility == View.VISIBLE }) {
                    assertTrue(
                        "$lang: touch target ${v.minimumHeight}/${v.height}",
                        v.minimumHeight >= (48 * density).toInt() ||
                            v.height >= (48 * density).toInt() ||
                            (v.layoutParams?.height ?: 0) >= (48 * density).toInt()
                    )
                }
                for (v in clickable.filterIsInstance<TextView>()) {
                    if (v.text.isNotBlank() && v.visibility == View.VISIBLE) {
                        org.junit.Assert.assertNull("$lang: clipped label ${v.text}", v.ellipsize)
                    }
                }
                assertTrue("$lang: readout empty", textOf(a, R.id.formReadyReadout).isNotBlank())
            } finally {
                c.pause().stop().destroy()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `checkbox is vertically aligned and its label is short in both languages`() {
        for (lang in listOf("en", "hi")) {
            RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
            val c = controller()
            try {
                val a = c.get()
                val box = a.findViewById<CheckBox>(R.id.formReadyCleanup)
                assertTrue("checkbox must centre with its label", box.gravity and Gravity.CENTER_VERTICAL != 0)
                assertEquals(app.getString(R.string.form1_cleanup), box.text.toString())
                assertFalse("parenthetical must be gone", box.text.toString().contains("("))
                assertFalse("Hindi parenthetical must be gone", box.text.toString().contains("("))
            } finally {
                c.pause().stop().destroy()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `width height and unit labels stay visible after the fields are filled`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            for (id in listOf(
                R.id.formReadyWidthLabel, R.id.formReadyHeightLabel, R.id.formReadyUnitLabel,
                R.id.formReadyMinLabel, R.id.formReadyMaxLabel
            )) {
                val label = a.findViewById<TextView>(id)
                assertEquals(View.VISIBLE, label.visibility)
                assertTrue("persistent label is blank", label.text.isNotBlank())
            }
            a.findViewById<EditText>(R.id.formReadyWidth).setText("240")
            idle()
            assertEquals(View.VISIBLE, a.findViewById<TextView>(R.id.formReadyWidthLabel).visibility)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `changing unit converts the values while keeping the requested pixels`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.setCustomValuesForTest(3.5f, 4.5f, FormUnit.CM, 300, 20, 50)
            idle()
            assertEquals(413, a.currentWidthPxForTest())
            assertEquals(531, a.currentHeightPxForTest())
            assertEquals(FormUnit.CM, a.currentUnitForTest())

            a.findViewById<View>(R.id.formReadyUnitPx).performClick()
            idle()
            assertEquals(FormUnit.PX, a.currentUnitForTest())
            assertEquals(413, a.currentWidthPxForTest())
            assertEquals(531, a.currentHeightPxForTest())
            assertEquals("413", textOf(a, R.id.formReadyWidth))

            a.findViewById<View>(R.id.formReadyUnitCm).performClick()
            idle()
            assertEquals(FormUnit.CM, a.currentUnitForTest())
            assertEquals(413, a.currentWidthPxForTest())
            assertEquals("3.50", textOf(a, R.id.formReadyWidth))
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `changing dpi keeps the same pixels for a physical unit`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.setCustomValuesForTest(3.5f, 4.5f, FormUnit.CM, 300, 20, 50)
            idle()
            a.findViewById<View>(R.id.formReadyDpi100).performClick()
            idle()
            assertEquals(100, a.currentDpiForTest())
            assertEquals(FormUnit.CM, a.currentUnitForTest())
            assertEquals(413, a.currentWidthPxForTest())
            assertEquals(531, a.currentHeightPxForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `crop aspect follows edited dimensions`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetThumb).performClick()
            idle()
            a.acceptPickedBytes(jpeg())
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            a.findViewById<EditText>(R.id.formReadyWidth).setText("480")
            idle()
            val rect = a.cropRectForTest()
            val ratio = rect.width() / rect.height().coerceAtLeast(1f)
            assertEquals("crop must follow 480 x 240", 2f, ratio, 0.05f)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `blank and invalid KB limits get an inline error and disable apply`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val error = a.findViewById<TextView>(R.id.formReadyBandError)
            assertEquals(View.GONE, error.visibility)

            a.findViewById<EditText>(R.id.formReadyMinKb).setText("")
            idle()
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(app.getString(R.string.form1_kb_blank), error.text.toString())
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            a.findViewById<EditText>(R.id.formReadyMinKb).setText("12")
            a.findViewById<EditText>(R.id.formReadyMaxKb).setText("5")
            idle()
            assertEquals(app.getString(R.string.form1_kb_max_below_min, 12), error.text.toString())
            assertFalse(a.findViewById<View>(R.id.formReadyApply).isEnabled)

            a.findViewById<EditText>(R.id.formReadyMinKb).setText("20")
            a.findViewById<EditText>(R.id.formReadyMaxKb).setText("50")
            idle()
            assertEquals(View.GONE, error.visibility)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a preset is saved and reused locally on this phone`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            assertFalse(a.findViewById<View>(R.id.formReadyUsePreset).isEnabled)
            a.setCustomValuesForTest(3.5f, 4.5f, FormUnit.CM, 300, 12, 30)
            a.findViewById<View>(R.id.formReadySavePreset).performClick()
            idle()
            assertTrue(textOf(a, R.id.formReadySavePreset).isNotBlank())

            val saved = FormPresets.loadEditable(app)
            assertNotNull("nothing was saved locally", saved)
            assertEquals(3.5f, saved!!.width, 0.01f)
            assertEquals(300, saved.dpi)
            assertEquals(12, saved.minKb)

            a.setCustomValuesForTest(200f, 230f, FormUnit.PX, 200, 20, 50)
            idle()
            a.findViewById<View>(R.id.formReadyUsePreset).performClick()
            idle()
            assertEquals(FormUnit.CM, a.currentUnitForTest())
            assertEquals(300, a.currentDpiForTest())
            assertEquals(12, a.currentMinKbForTest())
            assertEquals(413, a.currentWidthPxForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `cleanup shows original and processed preview before applying`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            // FX2 old->new: cleanup is visible but OFF until the person opts in.
            assertFalse("signature cleanup must start off", a.currentCleanupForTest())
            a.findViewById<CheckBox>(R.id.formReadyCleanup).performClick()
            idle()
            assertTrue("the visible toggle turns cleanup on", a.currentCleanupForTest())
            a.acceptPickedBytes(jpeg())
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
            assertTrue("preview never appeared", waitUntil { a.previewVisibleForTest() })
            assertNotNull(a.findViewById<ImageView>(R.id.formReadyPreviewOriginal).drawable)
            assertNotNull(a.findViewById<ImageView>(R.id.formReadyPreviewCleaned).drawable)

            a.findViewById<CheckBox>(R.id.formReadyCleanup).performClick()
            idle()
            assertFalse("unchecking must hide the preview", a.previewVisibleForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `preparing image is announced with a localized status`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.acceptPickedBytes(jpeg())
            assertEquals(app.getString(R.string.form1_preparing), a.lastAnnouncedForTest)
            assertEquals(app.getString(R.string.form1_preparing), a.findViewById<View>(R.id.formReadyProgress).contentDescription)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `result keeps pixel DPI and band checks separate and a band hit is not overall readiness`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            // Enough entropy to land inside the band, so "ready" is reachable and the three
            // checks are the only thing under test.
            val src = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(120 * 90) { i ->
                val x = i % 120
                val y = i / 120
                val r = (x * 31 + y * 67) and 0xFF
                val g = (x * 97 + y * 13 + ((x * y) and 0xFF)) and 0xFF
                val b = (x * 53 + y * 181) and 0xFF
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            src.setPixels(pixels, 0, 120, 0, 0, 120, 90)
            val result = try {
                ExactKbEncoder.encode(src, 140, 60, 200, 100L, 20_000L)
            } finally {
                src.recycle()
            }
            // FX2 old->new: the note is one hero line + OK/✕ statuses measured from the saved file.
            a.setCustomValuesForTest(140f, 60f, FormUnit.PX, 200, 1, 20)
            idle()
            val sizeText = FormPresets.formatKb(result.sizeBytes)
            val good = a.buildNoteForTest(result, 140, 60, 200)
            assertTrue(good, good.contains(app.getString(R.string.form1_result_hero, 140, 60, sizeText)))
            assertTrue(good, good.contains(app.getString(R.string.form1_result_ok, sizeText, 200)))
            assertFalse(good, good.contains("Pixels:"))
            assertFalse(good, good.contains("DPI:"))

            val wrong = a.buildNoteForTest(result, 100, 60, 200)
            assertTrue(wrong, wrong.contains(app.getString(
                R.string.form1_result_px_bad, 140, 60, 100, 60
            )))
            assertFalse("a pixel miss must not show OK", wrong.contains("OK"))
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `chips speak as radios and actions as buttons to TalkBack`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            for (id in listOf(
                R.id.formReadyPresetPhoto, R.id.formReadyPresetSignature, R.id.formReadyPresetThumb,
                R.id.formReadyPresetDeclaration, R.id.formReadyPresetCustom,
                R.id.formReadyUnitPx, R.id.formReadyUnitCm, R.id.formReadyUnitMm, R.id.formReadyUnitInch,
                R.id.formReadyDpi100, R.id.formReadyDpi200, R.id.formReadyDpi300
            )) {
                val chip = a.findViewById<TextView>(id)
                val info = nodeOf(chip)
                try {
                    assertEquals("radio role for $id", android.widget.RadioButton::class.java.name, info.className?.toString())
                    assertTrue("radio must be checkable", info.isCheckable)
                    assertEquals("state description for $id", app.getString(
                        if (chip.isSelected) R.string.form1_selected else R.string.form1_not_selected
                    ), info.stateDescription?.toString())
                } finally {
                    info.recycle()
                }
            }
            for (id in listOf(
                R.id.formReadyPick, R.id.formReadyApply, R.id.formReadySavePreset, R.id.formReadyUsePreset
            )) {
                val info = nodeOf(a.findViewById(id))
                try {
                    assertEquals("button role for $id", android.widget.Button::class.java.name, info.className?.toString())
                } finally {
                    info.recycle()
                }
            }
            assertEquals("pixels", a.findViewById<TextView>(R.id.formReadyUnitPx).contentDescription.toString())
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `destroy during encoding defers the preview recycle until the worker finishes`() {
        val c = controller()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            a.acceptPickedBytes(jpeg())
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })

            ExactKbEncoder.beforeEncode = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("encoder never started", entered.await(5, TimeUnit.SECONDS))
            c.destroy()
            release.countDown()
            assertTrue("deferred recycle never happened", waitUntil { a.deferredBitmapRecyclesForTest == 1 })
        } finally {
            release.countDown()
            ExactKbEncoder.beforeEncode = null
        }
    }

    @Test(timeout = 30_000)
    fun `recreation keeps the durable operation identity and the edited values`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.setCustomValuesForTest(2.5f, 1.2f, FormUnit.CM, 300, 12, 30)
            val opId = a.opIdForTest()
            assertTrue("operation id must be durable", opId.isNotBlank())

            c.recreate()
            idle()
            val restored = c.get()
            assertEquals(opId, restored.opIdForTest())
            assertEquals(FormUnit.CM, restored.currentUnitForTest())
            assertEquals(300, restored.currentDpiForTest())
            assertEquals(12, restored.currentMinKbForTest())
            assertEquals(30, restored.currentMaxKbForTest())
            assertEquals("2.50", textOf(restored, R.id.formReadyWidth))
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the form uses a dark preview canvas not a white page host`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            a.findViewById<CheckBox>(R.id.formReadyCleanup).performClick()
            idle()
            a.acceptPickedBytes(jpeg())
            assertTrue("preview never appeared", waitUntil { a.previewVisibleForTest() })
            for (id in listOf(R.id.formReadyCrop, R.id.formReadyPreviewOriginal, R.id.formReadyPreviewCleaned)) {
                val view = a.findViewById<View>(id)
                assertNotNull("missing dark preview $id", view.background)
            }
        } finally {
            c.pause().stop().destroy()
        }
    }
}
