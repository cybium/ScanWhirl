package com.scanwhirl.app.ui.pagegrid

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageThumbLoaderTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Writes a solid-color JPEG so decodeThumb returns a real bitmap. */
    private fun tempJpeg(name: String, color: Int): File {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val file = File(app.cacheDir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return file
    }

    private fun drain() {
        // Background executor + main-thread post: idle the looper, then give the
        // daemon decode threads a moment to finish and re-idle.
        repeat(3) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(150)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `cache key changes with file content, filter and target size`() {
        val file = tempJpeg("key_page.jpg", Color.WHITE)
        val base = PageThumbLoader.keyFor(file, 540, ScanFilter.ORIGINAL)

        // Same inputs -> same key (cache hit).
        assertEquals(base, PageThumbLoader.keyFor(file, 540, ScanFilter.ORIGINAL))

        // Different filter -> different key.
        assertNotEquals(base, PageThumbLoader.keyFor(file, 540, ScanFilter.ENHANCE))

        // Different target size -> different key (never serve a too-small thumb).
        assertNotEquals(base, PageThumbLoader.keyFor(file, 1080, ScanFilter.ORIGINAL))

        // Replacing the file changes mtime+length -> different key (stale dropped).
        Thread.sleep(20)
        val replacement = tempJpeg("key_page_replacement.jpg", Color.BLACK)
        replacement.copyTo(file, overwrite = true)
        replacement.delete()
        assertNotEquals(base, PageThumbLoader.keyFor(file, 540, ScanFilter.ORIGINAL))
    }

    @Test
    fun `late decode for a recycled view never overwrites the new page`() {
        val redPage = tempJpeg("stale_red.jpg", Color.RED)
        val bluePage = tempJpeg("stale_blue.jpg", Color.BLUE)
        val view = ImageView(app)

        // Bind the view for the red page…
        PageThumbLoader.load(redPage, 160, ScanFilter.ORIGINAL, view)
        val redKey = view.tag

        // …then recycle it for the blue page before red finishes decoding.
        PageThumbLoader.load(bluePage, 160, ScanFilter.ORIGINAL, view)
        val blueKey = view.tag
        assertNotEquals(redKey, blueKey)

        drain()

        assertEquals("view must still carry the latest key", blueKey, view.tag)
        val shown = view.drawable
        assertNotNull("blue page thumb should have been applied", shown)
        // The visible bitmap is the blue page, not the stale red one.
        val bmp = shown as? android.graphics.drawable.BitmapDrawable
        assertNotNull(bmp)
        val pixel = bmp!!.bitmap.getPixel(bmp.bitmap.width / 2, bmp.bitmap.height / 2)
        val blue = (pixel and 0xFF)
        val red = (pixel shr 16) and 0xFF
        assertTrue("expected blue page, got #${Integer.toHexString(pixel)}", blue > red)
    }

    @Test
    fun `cached thumb is served without re-decoding`() {
        val page = tempJpeg("cached_page.jpg", Color.GREEN)
        val view1 = ImageView(app)
        PageThumbLoader.load(page, 160, ScanFilter.ORIGINAL, view1)
        drain()
        val first = (view1.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        assertNotNull(first)

        // New view, same page: must hit the cache and show the same bitmap instance.
        val view2 = ImageView(app)
        PageThumbLoader.load(page, 160, ScanFilter.ORIGINAL, view2)
        val second = (view2.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        assertNotNull("cache hit should apply synchronously", second)
        assertTrue("expected cached instance reuse", first === second)
    }
}
