package com.scanwhirl.app.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.OcrEditorActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrEditorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private var uriCounter = 0

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/fake_${uriCounter++}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private val fakeJpeg get() = ImportFixtures.image()
    private val fakePdf = ByteArray(4096) { (it % 199).toByte() }

    @Before
    fun setUp() {
        val docsDir = File(context.filesDir, "docs")
        if (docsDir.exists()) docsDir.deleteRecursively()
    }

    /** Save a doc and seed OCR text for its single page. Returns the doc id. */
    private fun seedDocWithOcr(repo: ScanRepository, name: String, ocrText: String): String =
        runBlocking {
            val saved = repo.saveScan(
                listOf(contentUri(fakeJpeg)),
                contentUri(fakePdf),
                name
            )
            repo.updateOcrPages(saved.id, mapOf("page_000.jpg" to ocrText))
            saved.id
        }

    @Test
    fun `edit then Save makes corrected word searchable`() {
        // 1. Seed doc with OCR (top-level runBlocking, not nested with ActivityScenario).
        val docId = runBlocking {
            val repo = ScanRepository(context)
            seedDocWithOcr(repo, "Receipt", "TOTAL 450.00 GST Invoice No 12")
        }

        // 2. Launch editor, edit text, click Save.
        val intent = android.content.Intent(context, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
        }
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity: OcrEditorActivity ->
                val edit = activity.findViewById<android.widget.EditText>(R.id.editText)
                edit.setText("TOTAL 450.00 GST Receipt No 12")
                edit.setSelection(edit.text.length)
                activity.findViewById<android.view.View>(R.id.btnSave).performClick()
            }
        }

        // 3. Wait for the async Save coroutine to write to disk.
        runBlocking { delay(400) }

        // 4. SearchMatcher should find the corrected word via OCR lookup.
        val repo = ScanRepository(context)
        val docs = repo.listDocuments()
        val hits = SearchMatcher.search(
            "Receipt",
            docs,
            includeVaulted = false,
            normalizedText = { id -> repo.ocrDoc(id).values.joinToString("\n") },
            originalText = { id -> repo.ocrDoc(id).values.joinToString("\n") }
        )
        assertTrue("corrected word should be findable via OCR search", hits.any { it.doc.id == docId })
    }

    @Test
    fun `Back while dirty shows unsaved-confirm dialog`() {
        val docId = runBlocking {
            val repo = ScanRepository(context)
            seedDocWithOcr(repo, "Doc", "original text")
        }

        val intent = android.content.Intent(context, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
        }
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity: OcrEditorActivity ->
                val edit = activity.findViewById<android.widget.EditText>(R.id.editText)
                edit.setText("modified text")
                edit.setSelection(edit.text.length)

                // Press back while dirty → confirm dialog should appear.
                activity.onBackPressed()
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("dialog should be shown when dirty", dialog)
            }
        }
    }

    @Test
    fun `empty OCR state shows empty editor`() {
        val docId = runBlocking {
            val repo = ScanRepository(context)
            seedDocWithOcr(repo, "Empty", "")
        }

        val intent = android.content.Intent(context, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
        }
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity: OcrEditorActivity ->
                val edit = activity.findViewById<android.widget.EditText>(R.id.editText)
                assertTrue("empty OCR should show empty editor", edit.text != null && edit.text.isBlank())
                val count = activity.findViewById<android.widget.TextView>(R.id.wordCount)
                assertEquals("0 words", count.text.toString())
            }
        }
    }

    @Test
    fun `temp mode reads cache file and cleans it up`() {
        val dir = context.cacheDir
        val file = File(dir, "ocr_test_input.txt")
        file.writeText("hello world from temp file")

        val intent = android.content.Intent(context, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, file.absolutePath)
        }
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity: OcrEditorActivity ->
                val edit = activity.findViewById<android.widget.EditText>(R.id.editText)
                assertEquals("hello world from temp file", edit.text.toString())
            }
        }

        // Cache input file should be deleted after onDestroy.
        assertFalse("temp cache file should be cleaned up", file.exists())
    }
}
