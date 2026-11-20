package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * H4-2/H4-5: To Excel built the workbook from stored *geometry* only and re-recognised every other
 * page with the default English model, so typed/corrected text never reached the sheet and a Hindi
 * page was exported as Latin noise. A page that could not be read at all was written as
 * "No table found on this page." â€” a factual claim about content the app never saw.
 *
 * What these tests pin:
 *  - a page with stored text and no geometry exports the stored text, never a re-read;
 *  - an unread page is recognised with the user's Text-language setting and the result is kept,
 *    so the Text screen shows it too;
 *  - an unreadable page says so in its sheet instead of claiming there is no table.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExcelStoredTextTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File
    private val scriptCalls = mutableListOf<OcrScript>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "f9text_excel_${System.nanoTime()}").apply { mkdirs() }
        scriptCalls.clear()
        OcrLanguageSettings.set(app, OcrLanguage.ENGLISH)
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        OcrLanguageSettings.set(app, OcrLanguage.ENGLISH)
        work.deleteRecursively()
    }

    private fun sheet(zip: File, number: Int): String = ZipFile(zip).use { z ->
        val entry = z.getEntry("xl/worksheets/sheet$number.xml")
            ?: error("no sheet $number in ${zip.name}: ${z.entries().toList().map { it.name }}")
        z.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun ocrLine(text: String, left: Int, top: Int, width: Int = 120, height: Int = 40) =
        OcrLine(text, left, top, left + width, top + height)

    @Test
    fun `to excel uses the stored text when a page has no geometry`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Typed page")
        val page = repo.getDocument(id)!!.pageFiles.single()
        // Exactly the documented manual-entry path: text typed for a page, no geometry stored.
        repo.ocrStore.putTexts(id, mapOf(page.name to "à¤•à¥à¤² à¤°à¤¾à¤¶à¤¿ 1250"))
        OcrEngine.rawReader = { _, _ -> throw AssertionError("H4-2: a page that already has text must not be re-read") }

        val out = File(work, "typed.xlsx")
        val report = runBlocking { repo.exportExcel(id, out, tablesOnly = false) }

        assertEquals("nothing was unread", emptyList<Int>(), report.unreadPages)
        assertTrue("the typed text must be in the sheet", sheet(out, 1).contains("à¤•à¥à¤² à¤°à¤¾à¤¶à¤¿ 1250"))
    }

    @Test
    fun `to excel reads unread pages in the selected language and keeps the text`() {
        OcrLanguageSettings.set(app, OcrLanguage.HINDI)
        OcrEngine.rawReader = { _, script ->
            synchronized(scriptCalls) { scriptCalls += script }
            OcrRawPage(
                600, 800,
                listOf(OcrRawLine("à¤•à¥à¤² à¤°à¤¾à¤¶à¤¿", OcrBox(20, 40, 560, 70), script))
            )
        }
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Hindi unread")
        val page = repo.getDocument(id)!!.pageFiles.single()

        val out = File(work, "hindi.xlsx")
        runBlocking { repo.exportExcel(id, out, tablesOnly = false) }

        assertEquals(
            "H4-2: an unread page must be recognised with the Text-language setting",
            listOf(OcrScript.DEVANAGARI),
            scriptCalls
        )
        assertTrue("the Hindi text must be in the sheet", sheet(out, 1).contains("à¤•à¥à¤² à¤°à¤¾à¤¶à¤¿"))
        assertTrue(
            "the recognised text is kept, so the Text screen shows it too",
            repo.ocrDoc(id)[page.name]?.contains("à¤•à¥à¤² à¤°à¤¾à¤¶à¤¿") == true
        )
    }

    @Test
    fun `to excel says a page could not be read instead of claiming no table`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Half readable")
        val pages = repo.getDocument(id)!!.pageFiles
        repo.ocrStore.putPage(
            id, pages[0].name, "Item Qty\nPen 2\nBook 1",
            OcrPage(
                220, 300, listOf(
                    ocrLine("Item", 10, 20), ocrLine("Qty", 120, 20),
                    ocrLine("Pen", 10, 80), ocrLine("2", 120, 80),
                    ocrLine("Book", 10, 140), ocrLine("1", 120, 140)
                )
            )
        )
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }

        val out = File(work, "half.xlsx")
        val report = runBlocking { repo.exportExcel(id, out, tablesOnly = false, noTableNote = "No table here") }

        assertEquals("the unreadable page is reported by index", listOf(1), report.unreadPages)
        val second = sheet(out, 2)
        assertTrue("H4-5: the sheet says the page could not be read: $second", second.contains("could not be read"))
        assertFalse("H4-5: it must not claim the page has no table", second.contains("No table here"))
        assertFalse(
            "a failed read is not stored as a page with text",
            repo.ocrDoc(id).containsKey(pages[1].name)
        )
    }
}
