package com.scanwhirl.app.store

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** L5: imported PNG signatures live beside drawn ones and share the 5-signature limit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SignatureStoreBitmapTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun inkBitmap(): Bitmap =
        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (x in 8 until 32) for (y in 6 until 14) setPixel(x, y, Color.BLUE)
        }

    @Test
    fun `a bitmap signature round-trips and is counted with drawn ones`() {
        val store = SignatureStore(app)
        val bmp = inkBitmap()
        val saved = store.saveBitmap("Photo signature", bmp)
        bmp.recycle()

        assertNotNull(saved.pngFile)
        assertTrue(saved.pngFile!!.isFile)
        val reloaded = SignatureStore(app).load(saved.id)
        assertNotNull(reloaded)
        assertTrue(reloaded!!.isBitmap)
        assertTrue(reloaded.strokes.isEmpty())
        assertEquals("Photo signature", reloaded.name)

        store.delete(saved.id)
        assertNull(SignatureStore(app).load(saved.id))
        assertTrue(!saved.pngFile!!.exists())
    }

    @Test
    fun `the fifth signature is the limit across both kinds`() {
        val store = SignatureStore(app)
        val stroke = PageStamp.Stroke(listOf(PageStamp.Point(0f, 0f), PageStamp.Point(1f, 1f)))
        repeat(4) { store.save("Drawn ${it + 1}", listOf(stroke)) }
        val bmp = inkBitmap()
        store.saveBitmap("Photo", bmp)
        bmp.recycle()
        assertEquals(5, store.list().size)

        val extra = inkBitmap()
        val failed = runCatching { store.saveBitmap("Sixth", extra) }
        extra.recycle()
        assertTrue(failed.isFailure)
        assertEquals(5, store.list().size)
    }
}
