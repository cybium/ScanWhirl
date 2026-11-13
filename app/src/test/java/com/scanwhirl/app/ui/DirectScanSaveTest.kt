package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * M08: Google's scanner already showed its own preview (crop, rotate, filters, retake),
 * so Done saves straight to Home through the same verified save transaction — no
 * second edit screen. A failure keeps every page that arrived and opens Review on them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DirectScanSaveTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private var n = 0

    private fun pages(count: Int): List<Pair<Uri, ByteArray>> = (0 until count).map { i ->
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.JPEG, width = 120 + i)
        val uri = Uri.parse("content://scanner/page_${n++}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        uri to bytes
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun `Done saves the scan, leaves nothing staged and opens the saved document`() {
        Prefs.setAutoCopyToDocuments(app, false)
        val scanned = pages(3)
        var started: Intent? = null
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> ScanLauncher(a, repo, a) {}.saveScanned(scanned.map { it.first }) }
            await("the saved document") { repo.listDocuments().isNotEmpty() && PendingScans.all(app).isEmpty() }
            scenario.onActivity { a -> started = shadowOf(a).nextStartedActivity }
        }
        // M08 lands on the saved document's page overview. OQ-12 says the eventual
        // landing is the Reader; PM-2.2 changes both the app and this assertion.
        assertNotNull("the scanner opens the saved document", started)
        assertEquals(
            "the landing is the document screen today",
            DocumentViewerActivity::class.java.name, started!!.component?.className
        )
        val doc = repo.listDocuments().single()
        assertEquals(3, doc.pageCount)
        // Same order and the same picture as the scanner returned (normalised once, by the save).
        doc.pageFiles.forEachIndexed { i, f ->
            val bmp = ImportFixtures.assertJpeg(f.readBytes())
            assertEquals(120 + i, bmp.width)
            bmp.recycle()
        }
        assertTrue("/Count 3" in doc.pdfFile.readText(Charsets.ISO_8859_1))
        assertTrue("queued for free on-device text recognition", repo.ocrStore.isPending(doc.id) ||
            repo.ocrStore.readGeo(doc.id).isNotEmpty())
    }

    @Test
    fun `staging copies the scanner's bytes unchanged`() {
        val scanned = pages(2)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { a ->
                val dir = ScanLauncher(a, repo, a) {}.copyScannedPages(scanned.map { it.first })
                val staged = PendingScans.pages(dir)
                assertEquals(listOf("page_001.jpg", "page_002.jpg"), staged.map { it.name })
                staged.forEachIndexed { i, f -> assertArrayEquals(scanned[i].second, f.readBytes()) }
                assertTrue(dir.list()!!.none { it.endsWith(".part") })
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `a page that can't be read keeps the earlier pages and opens Review on them`() {
        val good = pages(1)
        val missing = Uri.parse("content://scanner/gone.jpg") // never registered: unreadable
        var review: Intent? = null
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> ScanLauncher(a, repo, a) {}.saveScanned(listOf(good[0].first, missing)) }
            await("Review on the kept pages") {
                scenario.onActivity { a -> review = review ?: shadowOf(a).nextStartedActivity }
                review != null
            }
        }
        assertEquals(ScanReviewActivity::class.java.name, review!!.component!!.className)
        val dir = File(review!!.getStringExtra("staging_dir")!!)
        assertEquals(1, PendingScans.pageCount(dir))
        assertArrayEquals(good[0].second, PendingScans.pages(dir).single().readBytes())
        assertTrue("nothing half-saved", repo.listDocuments().isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast().toString().startsWith("Couldn’t save the scan"))
    }

    @Test
    fun `appended pages are numbered after pages waiting on Undo`() {
        val dir = File(app.filesDir, "pending_scans/pending_undo").apply { mkdirs() }
        File(dir, "page_000.jpg").writeBytes(byteArrayOf(1))
        File(dir, "page_007.jpg.del").writeBytes(byteArrayOf(2))
        assertEquals(7, PendingScans.lastPageNumber(dir))
        assertEquals(0, PendingScans.lastPageNumber(File(app.filesDir, "pending_scans/none")))
    }

    @Test
    fun `Google's FULL mode is the default, an explicit choice is kept`() {
        assertTrue(Prefs.autoEnhanceScan(app))
        Prefs.setAutoEnhanceScan(app, false)
        assertTrue(!Prefs.autoEnhanceScan(app))
        assertEquals(app.getString(R.string.scan_saved_named, "X"), "Saved “X”")
    }
}
