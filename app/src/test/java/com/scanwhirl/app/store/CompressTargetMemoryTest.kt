package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * H3-2 (bug hunt 2026-11-20): "Aim for a file size" in the document-screen Compress sheet held
 * every page of the document in memory (`SizeFit.compressPages` -> `List<ByteArray>`), on top of
 * the repository's streaming path that exists for exactly this reason
 * (`ScanRepository.compressScanToTarget`). A long scan therefore died with OutOfMemoryError.
 *
 * These tests drive the streaming paths through the seam that reports how many page byte arrays
 * are alive at once. It is not a heap measurement: it proves the loop never retains a second
 * page, which is what the old list-based helper did for the whole document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompressTargetMemoryTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setMakeSearchable(app, true)
    }

    @After
    fun tearDown() {
        repo.onLivePageBytes = null
    }

    /** Records the highest number of page byte arrays the path held, and how many pages started. */
    private class Peak {
        var maxLive = 0
        var started = 0
    }

    private fun watch(): Peak {
        val peak = Peak()
        repo.onLivePageBytes = { live ->
            peak.maxLive = maxOf(peak.maxLive, live)
            if (live > 0) peak.started++
        }
        return peak
    }

    @Test
    fun `a long saved scan is compressed one page at a time`() {
        val pages = 24
        val id = DocFixtures.saved(repo, app, pages = pages, name = "Long book")
        val source = requireNotNull(repo.getDocument(id))
        val total = source.pageFiles.sumOf { it.length() }
        val peak = watch()

        val copy = runBlocking { repo.compressScanToTarget(id, (total * 6) / 10) }

        assertEquals("every page went through the loop", pages, peak.started)
        assertEquals("never more than one page's bytes alive", 1, peak.maxLive)
        assertEquals("no page may be lost", pages, copy.pageCount)
        assertTrue("the copy is published with a PDF", copy.pdfFile.isFile)
        assertTrue(
            "the target path actually re-encoded pages",
            copy.pageFiles.sumOf { it.length() } < total
        )
    }

    @Test
    fun `the device path re-fits staged pages one at a time`() {
        // The second half of the device-PDF target path: pages are rendered into staging (already
        // one at a time, see PdfImportRenderTest) and then fitted from disk here.
        val pages = 20
        val staging = File(app.cacheDir, "fit_staging").apply {
            deleteRecursively()
            mkdirs()
        }
        var total = 0L
        for (i in 0 until pages) {
            // A busy page so the fit has to decode and re-encode it, not just keep the bytes.
            val bytes = ImportFixtures.image(width = 300, height = 400)
            File(staging, String.format(Locale.ROOT, "page_%03d.jpg", i)).writeBytes(bytes)
            total += bytes.size
        }
        val target = (total * 55) / 100
        val peak = watch()

        runBlocking { repo.fitStagedPagesToTarget(staging, target) }

        assertEquals("every staged page was fitted", pages, peak.started)
        assertEquals("never more than one page's bytes alive", 1, peak.maxLive)
        val after = staging.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
        assertEquals("no page may be dropped", pages, after.size)
        val fitted = after.sumOf { it.length() }
        assertTrue("the fitted pages must fit the target: $fitted > $target", fitted <= target)
    }
}
