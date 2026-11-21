package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.widget.EditText
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * T1 capture data safety: rotating the Business card screen must keep the captured image and the
 * user's edits, and must not run OCR again over fields the person has already corrected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1BusinessCardRotationTest {

    private val cardText = """
        Rahul Sharma
        Sales Manager
        Acme Technologies Pvt Ltd
        M: +91 98765 43210
        E-mail: rahul@acme.in
    """.trimIndent()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @After
    fun tearDown() {
        BusinessCardActivity.recognizeForTest = null
    }

    @Test
    fun `rotation keeps the card image and edited fields without re-running OCR`() {
        var ocrCalls = 0
        BusinessCardActivity.recognizeForTest = { ocrCalls++; cardText }
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            scenario.onActivity { it.acceptCapture(jpeg()) }
            assertTrue("the form appears after OCR", waitUntil {
                var ready = false
                scenario.onActivity { ready = it.findViewById<EditText>(R.id.bcName) != null }
                ready
            })
            scenario.onActivity { it.findViewById<EditText>(R.id.bcName).setText("Edited Name") }
            assertEquals("OCR ran once before rotation", 1, ocrCalls)

            scenario.recreate()
            idle()
            assertTrue("the form returns after rotation", waitUntil {
                var ready = false
                scenario.onActivity { ready = it.findViewById<EditText>(R.id.bcName) != null }
                ready
            })
            scenario.onActivity { activity ->
                assertEquals("the user's edit survives", "Edited Name",
                    activity.findViewById<EditText>(R.id.bcName).text.toString())
                assertEquals("the other fields survive", "Sales Manager",
                    activity.findViewById<EditText>(R.id.bcJob).text.toString())
                assertTrue("the card image survives", activity.findViewById<ImageView>(R.id.bcPreview).isVisible)
                assertEquals("OCR must not run again over the edited form", 1, ocrCalls)
            }
        }
    }
}
