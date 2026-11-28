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
    fun `the indexer finishing a document re-reads its status on the live Library row`() {
        val dir = fixture("Lecture notes", 1)
        File(dir, "ocr_pending").writeText("")
        assertEquals(TextState.WAITING, HomeDocInfoReader.read(repo.docsDir, doc(dir)).text)

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { shell ->
                assertTrue("the live Library shows the document",
                    pollUntil { shell.findViewById<ViewGroup>(R.id.swDocList)?.childCount == 1 })

                // The real indexing worker the live Library enqueues: store text, clear the
                // pending marker, then run OcrIndexer.indexNow (no fake reader swap).
                File(dir, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\n")
                File(dir, "ocr_pending").delete()
                runBlocking { ScanWhirlApp.from(shell).indexer.indexNow(dir.name) }

                // The live Library row rebinds with page evidence from the indexed text.
                shell.findViewById<EditText>(R.id.swHomeSearch).setText("hello")
                assertTrue("the live row shows the page hit after indexing", pollUntil {
                    val card = shell.findViewById<ViewGroup>(R.id.swDocList)?.getChildAt(0)
                    card?.findViewById<TextView>(R.id.swSearchSnippet)
                        ?.text?.toString().orEmpty().contains("hello")
                })
                assertEquals("status refreshes to Search ready",
                    TextState.READY, HomeDocInfoReader.read(repo.docsDir, doc(dir)).text)
            }
        }
    }
}
