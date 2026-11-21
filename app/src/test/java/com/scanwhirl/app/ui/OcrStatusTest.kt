package com.scanwhirl.app.ui
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.common.MlKitException
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrEngine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * M06 P1: the Text editor tells the truth about recognition. "Not read"
 * (failed, unavailable) is never shown as an empty "0 words" page, "read, no
 * text" is distinguished, Retry only re-reads unread pages, and corrected
 * text is never overwritten.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrStatusTest {


    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var original: OcrIndexer
    private var recognizeCalls = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        original = ScanWhirlApp.from(app).indexer
    }

    @After
    fun tearDown() {
        ScanWhirlApp.from(app).indexer = original
    }

    private fun useRecognizer(fn: (File) -> String) {
        ScanWhirlApp.from(app).indexer = OcrIndexer(
            repo, MainScope(),
            recognize = { f ->
                recognizeCalls++
                OcrEngine.OcrResult(fn(f), OcrPage(width = 100, height = 100, lines = emptyList()))
            },
            autoRun = false
        )
    }

    private fun unavailable(): Nothing = throw MlKitException(
        "Waiting for the text recognition model to be downloaded. Please wait.",
        MlKitException.UNAVAILABLE
    )

    private fun seedDoc(pages: Int): String = runBlocking {
        val uris = (0 until pages).map {
            val bmp = android.graphics.Bitmap.createBitmap(80, 100, android.graphics.Bitmap.Config.ARGB_8888)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            val uri = android.net.Uri.parse("content://test/ocrstatus_${System.nanoTime()}_$it.jpg")
            shadowOf(app.contentResolver).registerInputStream(uri, out.toByteArray().inputStream())
            uri
        }
        repo.saveScan(uris, null, "Status doc").id
    }

    private fun editor(id: String, problem: String?): Intent =
        Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            .apply { problem?.let { putExtra(OcrEditorActivity.EXTRA_READ_PROBLEM, it) } }

    private fun idleUntil(cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun status(a: OcrEditorActivity): String? =
        a.findViewById<View>(R.id.ocrStatus).takeIf { it.visibility == View.VISIBLE }
            ?.let { a.findViewById<TextView>(R.id.ocrStatusText).text.toString() }

    private fun retryVisible(a: OcrEditorActivity) =
        a.findViewById<View>(R.id.btnOcrRetry).visibility == View.VISIBLE

    @Test(timeout = 60_000)
    fun `indexer reports unavailable, stops early and keeps the document pending`() = runBlocking {
        val id = seedDoc(3)
        useRecognizer { unavailable() }
        val report = ScanWhirlApp.from(app).indexer.index(id)
        assertTrue(report.unavailable)
        assertEquals(3, report.failedPages)
        assertEquals(0, report.pagesWithText)
        assertEquals("no point asking twice per page", 1, recognizeCalls)
        assertTrue("retried automatically later", repo.ocrStore.isPending(id))
        assertTrue("nothing stored as empty text", repo.ocrDoc(id).isEmpty())
    }

    @Test(timeout = 60_000)
    fun `unavailable recognition is explained, never a blank 0 words page, and Retry works`() {
        val id = seedDoc(2)
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, OcrEditorActivity.PROBLEM_UNAVAILABLE))
            .setup().use { c ->
                val a = c.get()
                assertTrue(status(a)!!.contains("not ready"))
                assertTrue(retryVisible(a))
                assertEquals("Not read", a.findViewById<TextView>(R.id.wordCount).text.toString())
                val chips = a.findViewById<ChipGroup>(R.id.pageTabs)
                assertEquals("every page has a tab", 2, chips.childCount)
                assertTrue((chips.getChildAt(1) as TextView).text.contains("not read"))

                useRecognizer { f -> "ZEPHYR on ${f.name}" }
                a.findViewById<View>(R.id.btnOcrRetry).performClick()
                assertTrue(idleUntil { status(a) == null })
                assertEquals("ZEPHYR on page_000.jpg", a.findViewById<EditText>(R.id.editText).text.toString())
                assertFalse(retryVisible(a))
                assertEquals(2, repo.ocrDoc(id).size)
            }
    }

    @Test(timeout = 60_000)
    fun `partial reading names the missing pages and Retry never overwrites corrected text`() {
        val id = seedDoc(3)
        repo.updateOcrPage(id, "page_000.jpg", "corrected by hand")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, OcrEditorActivity.PROBLEM_FAILED))
            .setup().use { c ->
                val a = c.get()
                assertEquals("Text read on 1 of 3 pages. Not read: page 2, 3.", status(a))
                useRecognizer { f -> if (f.name == "page_001.jpg") "second" else throw IllegalStateException("blurry") }
                a.findViewById<View>(R.id.btnOcrRetry).performClick()
                assertTrue(idleUntil { status(a)?.contains("2 of 3") == true })
                assertEquals("Text read on 2 of 3 pages. Not read: page 3.", status(a))
                assertEquals("corrected by hand", repo.ocrDoc(id)["page_000.jpg"])
                assertEquals("corrected by hand", a.findViewById<EditText>(R.id.editText).text.toString())
            }
    }

    @Test(timeout = 60_000)
    fun `read but empty pages say no text was found, without Retry`() {
        val id = seedDoc(2)
        repo.updateOcrPages(id, mapOf("page_000.jpg" to "", "page_001.jpg" to ""))
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, null)).setup().use { c ->
            val a = c.get()
            assertTrue(status(a)!!.startsWith("No text was found"))
            assertFalse(retryVisible(a))
            assertEquals("0 words", a.findViewById<TextView>(R.id.wordCount).text.toString())
        }
    }

    @Test(timeout = 60_000)
    fun `manual text for an unread page is saved as that page's text`() {
        val id = seedDoc(2)
        repo.updateOcrPage(id, "page_000.jpg", "first")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, OcrEditorActivity.PROBLEM_FAILED))
            .setup().use { c ->
                val a = c.get()
                val chips = a.findViewById<ChipGroup>(R.id.pageTabs)
                chips.getChildAt(1).performClick()
                a.findViewById<EditText>(R.id.editText).setText("typed by hand")
                a.findViewById<View>(R.id.btnSave).performClick()
                assertTrue(idleUntil { repo.ocrDoc(id)["page_001.jpg"] == "typed by hand" })
                assertTrue(idleUntil { status(a) == null })
                assertEquals("first", repo.ocrDoc(id)["page_000.jpg"])
            }
    }

    @Test(timeout = 60_000)
    fun `unread pages are never written back as empty text`() {
        val id = seedDoc(2)
        repo.updateOcrPage(id, "page_000.jpg", "first")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, OcrEditorActivity.PROBLEM_FAILED))
            .setup().use { c ->
                val a = c.get()
                a.findViewById<EditText>(R.id.editText).setText("first edited")
                a.findViewById<View>(R.id.btnSave).performClick()
                assertTrue(idleUntil { repo.ocrDoc(id)["page_000.jpg"] == "first edited" })
                assertFalse("page 2 stays 'not read'", "page_001.jpg" in repo.ocrDoc(id))
            }
    }

    @Test(timeout = 60_000)
    fun `recreation keeps every page and the temp text`() {
        val id = seedDoc(2)
        repo.updateOcrPages(id, mapOf("page_000.jpg" to "one", "page_001.jpg" to "two"))
        Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, null)).setup().use { c ->
            c.get().findViewById<EditText>(R.id.editText).setText("one changed")
            c.recreate()
            val a = c.get()
            assertEquals(2, a.findViewById<ChipGroup>(R.id.pageTabs).childCount)
            assertEquals("one changed", a.findViewById<EditText>(R.id.editText).text.toString())
            a.findViewById<ChipGroup>(R.id.pageTabs).getChildAt(1).performClick()
            assertEquals("two", a.findViewById<EditText>(R.id.editText).text.toString())
        }
        val f = File(app.cacheDir, "temp_${System.nanoTime()}.txt").apply { writeText("image words") }
        val temp = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, f.path)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Img")
        Robolectric.buildActivity(OcrEditorActivity::class.java, temp).setup().use { c ->
            c.recreate()
            assertEquals("image words", c.get().findViewById<EditText>(R.id.editText).text.toString())
        }
    }

    @Test(timeout = 60_000)
    fun `an image with no text says so`() {
        val f = File(app.cacheDir, "temp_${System.nanoTime()}.txt").apply { writeText("") }
        val temp = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, f.path)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Img")
        Robolectric.buildActivity(OcrEditorActivity::class.java, temp).setup().use { c ->
            assertEquals("No text was found. You can type it yourself.", status(c.get()))
            assertFalse(retryVisible(c.get()))
        }
    }

    /** Device finding (API 24): doc-mode export was "Document.txt" with "Page 0" headings. */
    @Test(timeout = 60_000)
    fun `document text export is named after the document with honest 1-based headings`() {
        val media = com.scanwhirl.app.util.FakeMediaStore().also { p ->
            p.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media"; exported = true })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", p)
        }
        try {
            val id = seedDoc(3)
            repo.updateOcrPage(id, "page_000.jpg", "first page words")
            repo.updateOcrPage(id, "page_002.jpg", "")
            Robolectric.buildActivity(OcrEditorActivity::class.java, editor(id, OcrEditorActivity.PROBLEM_FAILED))
                .setup().use { c ->
                    shadowOf(c.get()).clickMenuItem(R.id.ocr_save_txt)
                    assertTrue(idleUntil { media.scanwhirlPdfs().isNotEmpty() })
                }
            val row = media.scanwhirlPdfs().single()
            assertEquals("Status doc.txt", row.name)
            assertEquals(
                "Page 1\nfirst page words\n\nPage 2 · not read\n\n\nPage 3\n",
                String(row.bytes())
            )
        } finally {
            media.close()
        }
    }

    @Test(timeout = 60_000)
    fun `unavailable is recognised through wrapping exceptions`() {
        val wrapped = RuntimeException(
            "outer",
            MlKitException("x", MlKitException.UNAVAILABLE)
        )
        assertTrue(OcrEngine.isUnavailable(wrapped))
        assertFalse(OcrEngine.isUnavailable(IllegalStateException("Unreadable page")))
    }
}
