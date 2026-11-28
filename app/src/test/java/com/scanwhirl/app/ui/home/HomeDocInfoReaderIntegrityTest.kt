package com.scanwhirl.app.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * W8 port of the two HomeSlice2Test text-status integrity assertions. The legacy
 * scanList card is retired, but HomeDocInfoReader (the live status source) still
 * exists; these assertions keep the honesty contract at the reader layer:
 * the pending marker and stored page text are the only evidence, and finishing
 * indexing re-reads the status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeDocInfoReaderIntegrityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setIndexBackfillDone(app, true)
    }

    private var seq = 0

    private fun fixture(name: String, pages: Int): File {
        val dir = File(repo.docsDir, "status_${System.nanoTime()}_${seq++}")
        dir.mkdirs()
        for (i in 1..pages) File(dir, "page_%03d.jpg".format(i)).writeBytes(ByteArray(16))
        File(dir, "scan.pdf").writeBytes(ByteArray(64))
        File(dir, "meta.txt").writeText("$name\n${1_700_000_000_000L + seq * 60_000L}\n${CategoryRules.FORMS}\n")
        return dir
    }

    private fun doc(dir: File): ScanDocument = repo.getDocument(dir.name)!!

    @Test
    fun `text status comes from the pending marker and stored page text only`() {
        val waiting = fixture("Waiting", 2)
        File(waiting, "ocr_pending").writeText("")
        val ready = fixture("Ready", 2)
        File(ready, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\npage_002.jpg\td29ybGQ=\n")
        val partial = fixture("Partial", 2)
        File(partial, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\n")
        val flagOnly = fixture("Flag only", 1)
        File(flagOnly, "text_layer.flag").writeText("")
        val malformed = fixture("Malformed", 1)
        File(malformed, "ocr.txt").writeText("#scanwhirl-ocr-v2\nbroken line without tab\n")

        val dir = repo.docsDir
        assertEquals(TextState.WAITING, HomeDocInfoReader.read(dir, doc(waiting)).text)
        assertEquals(TextState.READY, HomeDocInfoReader.read(dir, doc(ready)).text)
        HomeDocInfoReader.read(dir, doc(partial)).let {
            assertEquals(TextState.PARTIAL, it.text)
            assertEquals(1, it.textPages)
        }
        assertEquals("text_layer.flag alone claims nothing",
            TextState.NONE, HomeDocInfoReader.read(dir, doc(flagOnly)).text)
        assertEquals("unreadable ocr.txt is not search-ready",
            TextState.NONE, HomeDocInfoReader.read(dir, doc(malformed)).text)
    }

    @Test
    fun `the indexer finishing a document re-reads its status`() {
        val waiting = fixture("Lecture notes", 1)
        File(waiting, "ocr_pending").writeText("")
        val dir = repo.docsDir
        assertEquals(TextState.WAITING, HomeDocInfoReader.read(dir, doc(waiting)).text)
        // The indexer stores text and clears the marker, then goes idle.
        File(waiting, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\n")
        File(waiting, "ocr_pending").delete()
        assertEquals("status refreshes to Search ready",
            TextState.READY, HomeDocInfoReader.read(dir, doc(waiting)).text)
    }
}
