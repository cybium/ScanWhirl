package com.scanwhirl.app.ui.home

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * W8 port of the two HomeSlice2Test text-status integrity assertions. The legacy
 * scanList card is retired, but HomeDocInfoReader (the status source) and the real
 * OcrIndexer worker are still live; the second test drives indexNow on that worker
 * and asserts the live Library row's text evidence updates after completion.
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

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private var seq = 0

    /** A real JPEG page: the indexer decodes pages, so a fake byte array is rejected. */
    private fun jpeg(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            60, 85, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.LTGRAY)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun fixture(name: String, pages: Int): File {
        val dir = File(repo.docsDir, "status_${System.nanoTime()}_${seq++}")
        dir.mkdirs()
        val bytes = jpeg()
        for (i in 1..pages) File(dir, "page_%03d.jpg".format(i)).writeBytes(bytes)
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
    fun `the indexer finishing a document re-reads its status on the live Library row`() {
        // A genuinely pending document: the real save path writes ocr_pending and NO ocr.txt.
        val dir = fixture("Lecture notes", 1)
        File(dir, "ocr_pending").writeText("")
        assertFalse("no stored OCR text before indexing", File(dir, "ocr.txt").exists())
        assertEquals(TextState.WAITING, HomeDocInfoReader.read(repo.docsDir, doc(dir)).text)

        val application = ScanWhirlApp.from(app)
        val savedIndexer = application.indexer
        // Real OcrIndexer bookkeeping; only the OCR engine's text output is stubbed, through
        // the indexer's existing recognize seam (ML Kit cannot run on the JVM).
        application.indexer = com.scanwhirl.app.store.OcrIndexer(
            application.repository,
            application.appScope,
            recognize = {
                com.scanwhirl.app.util.OcrEngine.OcrResult(
                    "hello", com.scanwhirl.app.model.OcrPage(60, 85, emptyList())
                )
            },
            autoRun = false,
            afterIndexed = { id -> application.repository.refreshPdfAfterIndex(id) }
        )
        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { shell ->
                    assertTrue("the live Library shows the document",
                        pollUntil { shell.findViewById<ViewGroup>(R.id.swDocList)?.childCount == 1 })
                    val search = shell.findViewById<EditText>(R.id.swHomeSearch)
                    val snippet = {
                        shell.findViewById<ViewGroup>(R.id.swDocList)?.getChildAt(0)
                            ?.findViewById<TextView>(R.id.swSearchSnippet)
                            ?.text?.toString().orEmpty()
                    }

                    // NEGATIVE CONTROL: without indexNow the row stays "waiting to read text"
                    // and shows no page snippet, even though the search is already typed.
                    search.setText("hello")
                    assertTrue("pending row settles", pollUntil { snippet().isEmpty() })
                    assertEquals("no index call: still pending",
                        TextState.WAITING, HomeDocInfoReader.read(repo.docsDir, doc(dir)).text)
                    assertFalse("no index call: no page hit", snippet().contains("hello"))

                    // Now run the real indexing pass: it recognizes the page, stores the text
                    // through OcrStore and clears ocr_pending itself.
                    runBlocking { application.indexer.indexNow(dir.name) }
                    assertFalse("the worker cleared the pending marker",
                        File(dir, "ocr_pending").exists())

                    // Re-run the live search so the Library row rebinds from stored text.
                    search.setText("")
                    search.setText("hello")
                    assertTrue("the live row shows the page hit after indexing", pollUntil {
                        snippet().contains("hello")
                    })
                    assertEquals("status refreshes to Search ready",
                        TextState.READY, HomeDocInfoReader.read(repo.docsDir, doc(dir)).text)
                }
            }
        } finally {
            application.indexer = savedIndexer
        }
    }
}
