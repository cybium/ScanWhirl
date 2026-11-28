package com.scanwhirl.app.ui.book

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.util.BookSplit
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * R06 NEW-1: Book pages is the one phone activity allowed to rotate, so the spreads the owner
 * picked (and the fold lines they dragged) must survive `recreate()`. The first test fails on the
 * old code, which kept every card only in an instance field and rebuilt an empty screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookPagesRotationFixTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setTestOverride(app, true)
    }

    @After
    fun tearDown() {
        Faults.resetAll()
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(app.mainLooper).idle()

    private fun pollUntil(timeoutMs: Long = 12000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun jpeg(darkBand: IntRange? = null, portrait: Boolean = false): ByteArray {
        val bitmap = if (portrait) {
            Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        }
        bitmap.eraseColor(Color.WHITE)
        if (darkBand != null) {
            Canvas(bitmap).drawRect(
                darkBand.first.toFloat(), 0f, (darkBand.last + 1).toFloat(), bitmap.height.toFloat(),
                Paint().apply { color = Color.BLACK }
            )
        }
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun register(bytes: ByteArray, name: String): Uri {
        val uri = Uri.parse("content://books/$name.jpg")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    @Test(timeout = 60_000)
    fun `rotation keeps both spreads with the dragged tilted line, flipped pages and one-page choice`() {
        val spreadUri = register(jpeg(390..410), "spread")
        val singleUri = register(jpeg(portrait = true), "single")

        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            scenario.onActivity { it.loadImages(listOf(spreadUri, singleUri)) }
            assertTrue("both photos decode", pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 2
            })

            scenario.onActivity { activity ->
                val spread = activity.previews[0]
                // Drag the fold to a slanted line: ratio 0.40, tilt +0.10.
                spread.setSpread(
                    spread.bitmap!!,
                    BookSplit.Gutter(
                        ratio = 0.40f, found = false, confidence = 0.2f, singlePage = false,
                        topRatio = 0.35f, bottomRatio = 0.45f
                    )
                )
                // "Flip pages" turns the photo 180 degrees and mirrors the line.
                val card = spread.parent as ViewGroup
                val flip = (0 until card.childCount).map { card.getChildAt(it) }
                    .first { it is TextView && it.text == activity.getString(R.string.book_rotate) }
                flip.performClick()

                // The close-up is a one-page photo; switch it back to a two-page spread.
                val toggle = activity.onePageSwitch(1)
                assertNotNull("the close-up offers Keep as one page", toggle)
                toggle!!.isChecked = false
            }

            scenario.recreate()
            idle()

            assertTrue("the picked spreads survive rotation", pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 2
            })
            scenario.onActivity { restored ->
                assertEquals(0.60f, restored.previews[0].ratio, 0.02f)
                assertEquals(0.10f, restored.previews[0].tilt, 0.02f)
                assertFalse("Keep as one page stays switched off", restored.keepsAsOnePage(1))
                assertTrue("the fold line is visible again", restored.previews[1].lineVisible)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `a fresh launch still loads a picked spread with its detected gutter`() {
        val uri = register(jpeg(390..410), "fresh")
        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            scenario.onActivity { it.loadImages(listOf(uri)) }
            assertTrue("the photo decodes on a fresh launch", pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 1
            })
            scenario.onActivity { activity ->
                assertTrue("the dark fold is detected", activity.previews[0].ratio in 0.45f..0.55f)
                assertFalse("a spread is not kept as one page", activity.keepsAsOnePage(0))
                assertNotNull("Create is shown", activity.createButton())
            }
        }
    }
}
