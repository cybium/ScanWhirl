package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.tools.PdfAExport
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v2 UI: 03_tools_hub — "Save as PDF/A" produces a verified archive from a real document,
 * image-only (a PDF/A archive may not carry the non-embedded OCR font), and refuses a protected
 * document with a clear message (PDF/A never carries encryption).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfAExportTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        File(app.cacheDir, "pdfa").deleteRecursively()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        File(app.cacheDir, "pdfa").deleteRecursively()
    }

    @Test
    fun `a document becomes a verified PDF-A file named after it`() = runBlocking {
        Prefs.setMakeSearchable(app, true)
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Annual Report")
        DocFixtures.withOcr(repo, id)

        val file = PdfAExport(app, repo).export(id)

        assertTrue(file.isFile)
        assertTrue("named after the document: ${file.name}", file.name.startsWith("annual-report"))
        val text = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.7"))
        assertTrue(text.contains("/S /GTS_PDFA1"))
        assertTrue(text.contains("<pdfaid:conformance>B</pdfaid:conformance>"))
        assertFalse("PDF/A-2b needs embedded fonts: the archive is image-only", text.contains("/ActualText"))
        assertFalse("no non-embedded font may ship in the archive", text.contains("/BaseFont"))
        assertTrue(text.contains("/Title (Annual Report)"))
        assertFalse(text.contains("/Encrypt"))
        assertTrue("the source document is untouched", repo.getDocument(id) != null)
    }

    @Test
    fun `searchable PDFs off means no text layer in the archive`() = runBlocking {
        Prefs.setMakeSearchable(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Plain")
        DocFixtures.withOcr(repo, id)

        val text = String(PdfAExport(app, repo).export(id).readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("/ActualText"))
        assertTrue(text.contains("/S /GTS_PDFA1"))
    }

    @Test
    fun `a protected document is refused with a clear line`() = runBlocking {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 1)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAExport(app, repo).export(id) }
        }
        assertTrue(
            "clear wording: ${failure.message}",
            failure.message == app.getString(R.string.pdfa_locked)
        )
        assertFalse(File(app.cacheDir, "pdfa").listFiles().orEmpty().any { it.extension == "pdf" })
    }

    @Test
    fun `a missing document is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { PdfAExport(app, repo).export("no-such-document") }
        }
    }
}
