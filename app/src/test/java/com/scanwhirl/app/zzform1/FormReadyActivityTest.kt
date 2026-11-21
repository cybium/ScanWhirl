package com.scanwhirl.app.zzform1

import android.app.Application
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.ExactKbEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * FORM1: the screen as a person uses it (with a test seam for the SAF picker, which cannot
 * run under Robolectric). Every async step is bounded by a timeout so a hung worker fails
 * instead of hanging the gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormReadyActivityTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

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

    private fun makeActivity(): FormReadyActivity =
        Robolectric.buildActivity(FormReadyActivity::class.java).setup().get()

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

    @Test
    fun `intent and tool id are exposed for the lead to wire`() {
        assertEquals("form_ready", FormReadyActivity.TOOL_ID)
        val intent = FormReadyActivity.intent(app)
        assertEquals(FormReadyActivity::class.java.name, intent.component!!.className)
        assertNotNull(intent)
    }

    @Test
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

    @Test
    fun `custom unit and dpi give a live pixel readout`() {
        val a = makeActivity()
        idle()
        a.findViewById<View>(R.id.formReadyPresetCustom).performClick()
        idle()
        a.setCustomValuesForTest(3.5f, 4.5f, com.scanwhirl.app.util.FormUnit.CM, 300, 20, 50)
        idle()
        assertEquals(413, a.currentWidthPxForTest())
        assertEquals(531, a.currentHeightPxForTest())
        val readout = textOf(a, R.id.formReadyReadout)
        assertTrue(readout, readout.contains("413") && readout.contains("531"))
    }

    @Test
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
}
