package com.scanwhirl.app.v2c

import android.app.Application
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.CropView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V2C C1: fine/coarse crop movement, spoken coordinates and focus traversal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class C1CropPrecisionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun cropView(w: Int = 400, h: Int = 600) = CropView(app).apply {
        setImage(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1920)
    }

    private val fineRight = 0x01000002
    private val coarseRight = 0x01000012
    private val left = 0x01000001
    private val rightEdge = 6 // virtual id for edge index 1

    @Test
    fun `fine move is a small step and coarse move is a larger step`() {
        val fine = cropView()
        val beforeFine = fine.getCropRect().left
        assertTrue(fine.performVirtualActionForTest(1, fineRight))
        val fineDelta = fine.getCropRect().left - beforeFine

        val coarse = cropView()
        val beforeCoarse = coarse.getCropRect().left
        assertTrue(coarse.performVirtualActionForTest(1, coarseRight))
        val coarseDelta = coarse.getCropRect().left - beforeCoarse

        assertTrue("fine step $fineDelta must be small", fineDelta in 0.5f..4f)
        assertTrue("coarse step $coarseDelta must be larger", coarseDelta in 6f..12f)
        assertTrue("coarse is at least 3x fine", coarseDelta >= fineDelta * 3f)
    }

    @Test
    fun `a move announces the corner and its position in percentages`() {
        val view = cropView()
        assertTrue(view.performVirtualActionForTest(1, fineRight))
        val spoken = view.lastAnnouncement
        assertTrue("spoken position: $spoken", spoken.contains("Top-left corner"))
        assertTrue("spoken left percent: $spoken", Regex("from left, \\d+% from top").containsMatchIn(spoken))
    }

    @Test
    fun `a move blocked at the edge announces the limit`() {
        val view = cropView()
        repeat(60) { view.performVirtualActionForTest(1, left) }
        assertTrue("limit announced: ${view.lastAnnouncement}", view.lastAnnouncement.contains("at the edge"))
    }

    @Test
    fun `coarse edge movement keeps a preset aspect ratio`() {
        val view = cropView()
        val ratio = 85.6f / 54f
        view.aspectRatio = ratio
        val before = view.getCropRect()
        assertTrue(view.performVirtualActionForTest(rightEdge, 0x01000011)) // Move left more
        val after = view.getCropRect()
        assertTrue("edge moved", after.right < before.right - 1f)
        assertEquals(ratio, after.width() / after.height(), 0.05f)
    }

    @Test
    fun `small signature-sized crop stays inside the image`() {
        val view = cropView(200, 120)
        view.aspectRatio = 85.6f / 54f
        repeat(10) { view.performVirtualActionForTest(1, fineRight) }
        repeat(10) { view.performVirtualActionForTest(1, 0x01000004) } // move down fine
        val crop = view.getCropRect()
        assertTrue("positive rectangle", crop.width() > 0f && crop.height() > 0f)
        assertTrue("inside image", crop.right <= 200f && crop.bottom <= 120f)
    }

    @Test
    fun `Shift+Tab moves backward and leaves the view from the first handle`() {
        val view = cropView()
        view.selectHandleForTest(3)
        val shiftTab = KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0,
            KeyEvent.META_SHIFT_ON
        )
        assertTrue("Shift+Tab moves to previous handle", view.onKeyDown(KeyEvent.KEYCODE_TAB, shiftTab))
        assertEquals(2, view.getTag(R.id.ux130_l2_crop_handle))

        view.selectHandleForTest(0)
        assertFalse(
            "Shift+Tab from first handle must leave the view",
            view.onKeyDown(KeyEvent.KEYCODE_TAB, shiftTab)
        )
    }

    @Test
    fun `Tab from the last handle still leaves the view`() {
        val view = cropView()
        view.selectHandleForTest(7)
        assertFalse("Tab must leave the view", view.onKeyDown(KeyEvent.KEYCODE_TAB, null))
    }
}
