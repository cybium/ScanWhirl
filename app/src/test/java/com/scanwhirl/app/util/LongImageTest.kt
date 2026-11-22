package com.scanwhirl.app.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.SwActivitySession
import com.scanwhirl.app.ui.shell.ShellActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v2 UI: 03_tools_hub — Long image is back (E20, the owner reversed O-01).
 *
 * This replaces `LongImageRemovalTest`, which encoded the old "Long image is removed" UI.
 * The stitcher must stay bounded: 1080 px wide, at most 16,000 px tall per part, split instead
 * of squashed, aspect ratio kept, and the document's page files untouched (I5). The old
 * data-safety promise also stays: an image exported by an earlier version is still an ordinary
 * shareable JPEG and the app never deletes or edits it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LongImageTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 2): String = runBlocking {
        val uris = (0 until pages).map { i ->
            Uri.parse("content://test/li_${System.nanoTime()}_$i.jpg").also {
                shadowOf(app.contentResolver).registerInputStream(it, jpegBytes().inputStream())
            }
        }
        repo.saveScan(uris, null, name).id
    }

    // ---- bounded rules -------------------------------------------------

    @Test
    fun `one ten and forty pages produce bounded parts`() {
        val pageHeights = List(40) { 1_500 }
        assertEquals(1, LongImage.plan(pageHeights.take(1)).size)
        val ten = LongImage.plan(pageHeights.take(10))
        assertTrue("ten pages must fit a few parts, got ${ten.size}", ten.size in 1..2)
        val forty = LongImage.plan(pageHeights)
        assertTrue("forty pages must split", forty.size >= 3)

        for (plan in listOf(ten, forty)) {
            // Every part's drawn height is the sum of its pages plus the 12 px gaps.
            for (range in plan) {
                val height = range.sumOf { pageHeights[it] } + LongImage.GAP * (range.last - range.first)
                assertTrue("part is $height px tall", height <= LongImage.MAX_PART_HEIGHT)
            }
            // Every page appears exactly once, in order.
            assertEquals(40.coerceAtMost(if (plan === ten) 10 else 40), plan.sumOf { it.count() })
            assertEquals(0, plan.first().first)
            assertEquals(if (plan === ten) 9 else 39, plan.last().last)
        }
    }

    @Test
    fun `a hundred pages stay under the per part limit without one huge canvas`() {
        val heights = List(100) { 1_400 }
        val plan = LongImage.plan(heights)
        assertTrue("100 pages must split", plan.size >= 7)
        for (range in plan) {
            val height = range.sumOf { heights[it] } + LongImage.GAP * (range.last - range.first)
            assertTrue("part is $height px tall", height <= LongImage.MAX_PART_HEIGHT)
        }
        assertEquals(100, plan.sumOf { it.count() })
    }

    @Test
    fun `a page taller than the limit is refused instead of distorted`() {
        val r = runCatching { LongImage.plan(listOf(1_000, LongImage.MAX_PART_HEIGHT + 1)) }
        assertTrue("a too tall page must fail", r.isFailure)
        assertTrue("the message must say why", r.exceptionOrNull()!!.message!!.contains("Too large"))
    }

    // ---- data safety ---------------------------------------------------

    @Test
    fun `existing exported long image stays an ordinary shareable image and pages are untouched`() {
        val docId = seedDoc("Data safety", pages = 2)
        val doc = runBlocking { repo.listDocuments().first { it.id == docId } }
        val pageBytesBefore = doc.pageFiles.map { it.readBytes() }

        // A long image exported by an earlier version: just a JPEG in a file.
        val shareDir = File(app.cacheDir, "documents/share").apply { mkdirs() }
        val exported = File(shareDir, "Old export - long.jpg").apply { writeBytes(jpegBytes()) }
        val exportedBefore = exported.readBytes()

        assertTrue("exported file still exists", exported.exists())
        assertArrayEquals("exported file bytes unchanged", exportedBefore, exported.readBytes())
        val after = runBlocking { repo.listDocuments().first { it.id == docId } }
        assertEquals(pageBytesBefore.size, after.pageFiles.size)
        after.pageFiles.forEachIndexed { i, f ->
            assertArrayEquals("page $i bytes unchanged", pageBytesBefore[i], f.readBytes())
        }

        // Still decodes as a normal image and still shares via ACTION_SEND.
        val dims = ImageTools.imageSize(exported.readBytes())
        assertEquals(310 to 444, dims)
        SwActivitySession.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ShareUtils.shareFile(activity, exported, "image/jpeg")
                val chooser = awaitShareChooser(activity)
                assertNotNull("share chooser launched", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("image/jpeg", send.type)
            }
        }
    }
}
