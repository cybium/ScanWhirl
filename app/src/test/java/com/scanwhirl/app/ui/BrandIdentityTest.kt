package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.BrandWhirl
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

/**
 * Brand slice 3 — one ScanWhirl mark: the adaptive launcher (with its own flat
 * monochrome layer) and the API 24/25 PNG fallbacks carry the same whirl rings +
 * paper page inside the safe zone. The legacy Home header assertion was deleted
 * with MainActivity (W8); the shell header mark is covered by ShellLookTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BrandIdentityTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** tag -> drawable resource id inside an `<adaptive-icon>` file. */
    private fun adaptiveLayers(res: Int): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        val p = app.resources.getXml(res)
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && p.depth == 2) {
                out[p.name] = p.getAttributeResourceValue(androidNs, "drawable", 0)
            }
        }
        return out
    }

    private fun render(res: Int, px: Int): Bitmap {
        val d = ContextCompat.getDrawable(app, res)!!
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun near(a: Int, b: Int, tol: Int = 6) =
        Math.abs(Color.red(a) - Color.red(b)) <= tol &&
            Math.abs(Color.green(a) - Color.green(b)) <= tol &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= tol

    @Test
    fun `launcher and round icons use the brand layers and a separate monochrome`() {
        for (res in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val layers = adaptiveLayers(res)
            assertEquals(R.color.sw_brand_ground, layers["background"])
            assertEquals(R.drawable.ic_launcher_foreground, layers["foreground"])
            assertEquals("themed icons get their own flat layer, not the coloured foreground",
                R.drawable.ic_launcher_monochrome, layers["monochrome"])
        }
    }

    @Test
    fun `foreground stays inside the adaptive safe zone and uses only the brand colours`() {
        val px = 432 // 4 px per dp of the 108 dp layer
        val bmp = render(R.drawable.ic_launcher_foreground, px)
        val scan = ContextCompat.getColor(app, R.color.sw_scan)
        val paper = ContextCompat.getColor(app, R.color.sw_paper)
        val safe = 33.0 * px / 108
        var ring = 0; var page = 0
        for (y in 0 until px) for (x in 0 until px) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) == 0) continue
            val r = hypot(x + 0.5 - px / 2.0, y + 0.5 - px / 2.0)
            assertTrue("painted pixel at r=$r outside safe zone $safe", r <= safe + 1.0)
            if (Color.alpha(c) == 255) {
                when {
                    near(c, scan) -> ring++
                    near(c, paper) -> page++
                    else -> throw AssertionError("unexpected opaque colour ${Integer.toHexString(c)}")
                }
            }
        }
        assertTrue("both rings and page are drawn ($ring / $page)", ring > 2000 && page > 2000)
    }

    @Test
    fun `monochrome layer is one flat colour with the same silhouette`() {
        val px = 216
        val mono = render(R.drawable.ic_launcher_monochrome, px)
        val fg = render(R.drawable.ic_launcher_foreground, px)
        var painted = 0
        for (y in 0 until px) for (x in 0 until px) {
            val m = mono.getPixel(x, y)
            assertEquals("silhouette matches at $x,$y",
                Color.alpha(fg.getPixel(x, y)) > 127, Color.alpha(m) > 127)
            if (Color.alpha(m) > 0) {
                painted++
                assertEquals(0xFFFFFF, m and 0xFFFFFF)
            }
        }
        assertTrue(painted > 1000)
    }

    @Test
    fun `legacy fallbacks exist at every density, opaque centre and clear corners`() {
        val sizes = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        for ((dens, size) in sizes) for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val f = File("src/main/res/mipmap-$dens/$name.png")
            val bmp = BitmapFactory.decodeFile(f.path)
            assertEquals("$f width", size, bmp.width)
            assertEquals("$f height", size, bmp.height)
            assertEquals("$f corner is transparent", 0, Color.alpha(bmp.getPixel(0, 0)))
            assertEquals("$f centre is opaque", 255, Color.alpha(bmp.getPixel(size / 2, size / 2)))
        }
    }

    @Test
    fun `whirl fires only when the same library gains a document`() {
        val w = BrandWhirl()
        assertFalse("first load is not news", w.onLibraryLoaded(setOf("a", "b"), vault = false))
        assertFalse("reload, nothing new", w.onLibraryLoaded(setOf("a", "b"), vault = false))
        assertFalse("deletion is not a gain", w.onLibraryLoaded(setOf("a"), vault = false))
        assertTrue("a new document landed", w.onLibraryLoaded(setOf("a", "c"), vault = false))
        assertFalse("switching to Private is not a gain", w.onLibraryLoaded(setOf("p"), vault = true))
        assertTrue("a document moved into Private", w.onLibraryLoaded(setOf("p", "q"), vault = true))
        assertFalse("switching back resets", w.onLibraryLoaded(setOf("a", "c", "d"), vault = false))
    }
}
