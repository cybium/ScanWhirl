package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.scanwhirl.app.ui.stamp.StampEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** L5: imported bitmap signatures/stamps render and behave like the vector signature mark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageStampPictureTest {

    private fun redPng(): File {
        val bmp = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        for (x in 5 until 55) for (y in 5 until 25) bmp.setPixel(x, y, Color.RED)
        val file = File.createTempFile("stamp_", ".png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    @Test
    fun `a picture mark renders inside its box and names the copy signed`() {
        val file = redPng()
        try {
            val page = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            page.eraseColor(Color.WHITE)
            val mark = PageStamp.Mark.Picture(file, PageStamp.Box(0.25f, 0.35f, 0.75f, 0.65f))
            PageStamp.render(page, listOf(mark), 1, 1)
            assertEquals(Color.RED, page.getPixel(100, 100))
            assertEquals(Color.WHITE, page.getPixel(10, 10))
            assertTrue(PageStamp.outputName("Doc", listOf(mark)).contains("signed"))
            page.recycle()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a picture mark moves resizes and rotates like a signature`() {
        val state = StampEditorState(pageCount = 1)
        val file = redPng()
        try {
            val index = state.add(PageStamp.Mark.Picture(file, PageStamp.Box(0.2f, 0.2f, 0.6f, 0.4f)))
            state.moveBy(index, 0.1f, 0.05f)
            state.resizeTo(index, PageStamp.Box(0.3f, 0.3f, 0.8f, 0.5f))
            state.rotateBy(index, 20f)
            val mark = state.marks().single() as PageStamp.Mark.Picture
            assertEquals(0.3f, mark.box.left, 0.0001f)
            // P placement keeps imported signature ink's aspect instead of stretching to 0.8.
            assertEquals(0.7f, mark.box.right, 0.0001f)
            assertEquals(2f, (mark.box.right-mark.box.left)/(mark.box.bottom-mark.box.top), 0.0001f)
            assertEquals(20f, mark.rotation, 0.0001f)
        } finally {
            file.delete()
        }
    }
}
