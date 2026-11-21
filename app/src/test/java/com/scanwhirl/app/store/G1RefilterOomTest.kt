package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G1 item 1: re-filtering a saved page used to decode the kept original at full resolution with
 * no bounds. A large page can raise OutOfMemoryError (an Error, so no `catch (Exception)` sees it)
 * and crash Review. The decode is now bounded to the capture cap and an OOM becomes an honest,
 * localized failure: the original page is left byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1RefilterOomTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `the refilter decode is bounded to the capture cap`() {
        val file = File(app.cacheDir, "g1_large_${System.nanoTime()}.jpg")
        file.writeBytes(ImportFixtures.image(width = 5000, height = 100))
        val decoded = ScanRepository.refilterDecode(file)
        try {
            assertTrue("the long side must be bounded to ${ScanRepository.REFILTER_MAX_LONG_SIDE}, was ${decoded.width}",
                decoded.width <= ScanRepository.REFILTER_MAX_LONG_SIDE)
            assertEquals("a 5000 px source is downsampled by inSampleSize=2", 2500, decoded.width)
        } finally {
            decoded.recycle()
            file.delete()
        }
    }

    @Test
    fun `an out of memory during a saved-page refilter fails honestly and leaves the page untouched`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val page = repo.getDocument(id)!!.pageFiles.single()
        val before = page.readBytes()
        val beforeSha = Sha256.of(page)

        ScanRepository.refilterDecode = { throw OutOfMemoryError("forced for the test") }
        val failed = runCatching { repo.setPageFilter(id, 0, ScanFilter.BLACKWHITE) }

        assertTrue("the OOM must surface as a failure, not a crash: ${failed.exceptionOrNull()}", failed.isFailure)
        assertTrue("an Error becomes an ordinary IOException", failed.exceptionOrNull() is java.io.IOException)
        assertEquals(
            "the message is the localized, honest one",
            app.getString(R.string.refilter_out_of_memory),
            failed.exceptionOrNull()?.message
        )
        assertArrayEquals("the page file is untouched", before, page.readBytes())
        assertEquals("the page digest is untouched", beforeSha, Sha256.of(page))
    }

    @Test
    fun `an out of memory during a draft refilter leaves the draft page untouched`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val page = repo.getDocument(id)!!.pageFiles.single()
        val before = Sha256.of(page)

        ScanRepository.refilterDecode = { throw OutOfMemoryError("forced for the test") }
        val failed = runCatching { repo.draftFilter(id, 0, ScanFilter.BLACKWHITE) }

        assertTrue("the OOM must surface as a failure: ${failed.exceptionOrNull()}", failed.isFailure)
        assertEquals(
            "the draft failure uses the same localized message",
            app.getString(R.string.refilter_out_of_memory),
            failed.exceptionOrNull()?.message
        )
        assertEquals("the saved page is untouched", before, Sha256.of(page))
        assertEquals("no draft replacement was written", null, runBlocking { repo.draftOf(id) }?.entries?.single()?.replacement)
    }
}
