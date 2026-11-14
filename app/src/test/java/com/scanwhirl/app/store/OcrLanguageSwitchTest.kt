package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import java.io.File

/**
 * v2 UI: 08_text_ocr — the language setting and the re-recognition it triggers (the Text
 * screen's language chip calls exactly [OcrReindex]). The recognizers are faked through
 * [OcrEngine.rawReader]: ML Kit cannot run in a Robolectric JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrLanguageSwitchTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        OcrEngine.rawReader = { _, script ->
            when (script) {
                OcrScript.LATIN -> OcrRawPage(
                    600, 800,
                    listOf(
                        OcrRawLine("English headline", OcrBox(20, 40, 560, 70), OcrScript.LATIN),
                        // The Latin model's view of the Hindi line.
                        OcrRawLine("dqDrh iqDrh", OcrBox(20, 120, 560, 150), OcrScript.LATIN)
                    )
                )
                OcrScript.DEVANAGARI -> OcrRawPage(
                    600, 800,
                    listOf(
                        OcrRawLine("कुल राशि \uFFFD1250", OcrBox(20, 120, 560, 150), OcrScript.DEVANAGARI),
                        OcrRawLine("अंग्रेज़ी पंक्ति", OcrBox(20, 200, 560, 230), OcrScript.DEVANAGARI)
                    )
                )
            }
        }
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @Test
    fun `the language setting survives and defaults to English`() {
        assertEquals(OcrLanguage.ENGLISH, OcrLanguageSettings.get(app))
        OcrLanguageSettings.set(app, OcrLanguage.ENGLISH_HINDI)
        assertEquals(OcrLanguage.ENGLISH_HINDI, OcrLanguageSettings.get(app))
        OcrLanguageSettings.set(app, OcrLanguage.HINDI)
        assertEquals(OcrLanguage.HINDI, OcrLanguageSettings.get(app))
    }

    @Test
    fun `switching the language re-reads the document in the new script`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Bilingual receipt")

        runBlocking {
            // English: Latin lines only.
            OcrLanguageSettings.set(app, OcrLanguage.ENGLISH)
            assertEquals(2, OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH))
        }
        val english = repo.ocrDoc(id).values.joinToString("\n")
        assertTrue(english.contains("English headline"))
        assertFalse(english.contains("कुल"))

        runBlocking {
            // The chip switches to Hindi: the document is read again, the old text is gone.
            OcrLanguageSettings.set(app, OcrLanguage.HINDI)
            assertEquals(2, OcrReindex(repo).reRecognise(id, OcrLanguage.HINDI))
        }
        val hindi = repo.ocrDoc(id).values.joinToString("\n")
        assertTrue("Devanagari is stored: $hindi", hindi.contains("कुल राशि 1250"))
        assertTrue(hindi.contains("अंग्रेज़ी पंक्ति"))
        assertFalse("the English pass is replaced", hindi.contains("English headline"))
        assertFalse("stored text never keeps U+FFFD", hindi.contains('\uFFFD'))
        assertFalse("the pending marker is cleared", repo.ocrStore.isPending(id))
    }

    @Test
    fun `English plus Hindi keeps both scripts in one pass`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Mixed page")
        runBlocking {
            OcrLanguageSettings.set(app, OcrLanguage.ENGLISH_HINDI)
            assertEquals(1, OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH_HINDI))
        }

        val text = repo.ocrDoc(id).values.joinToString("\n")
        assertTrue("the Latin line stays: $text", text.contains("English headline"))
        assertTrue("the Hindi line is Devanagari: $text", text.contains("कुल राशि 1250"))
        assertTrue("the Devanagari-only line is kept: $text", text.contains("अंग्रेज़ी पंक्ति"))
        assertFalse(text.contains("dqDrh"))
        assertFalse(text.contains('\uFFFD'))
    }

    @Test
    fun `re-recognition also refreshes the geometry`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Geometry page")
        runBlocking { OcrReindex(repo).reRecognise(id, OcrLanguage.HINDI) }

        val geo = repo.ocrGeometry(id)
        assertTrue(geo.isNotEmpty())
        val lines = geo.values.first().lines
        assertEquals(listOf(70, 150, 230), lines.map { it.bottom })
    }

    @Test
    fun `a document that no longer exists is not an error`() {
        val stored = runBlocking { OcrReindex(repo).reRecognise("missing-doc", OcrLanguage.ENGLISH) }
        assertEquals(-1, stored)
    }

    @Test
    fun `the recognizer seam feeds the real engine`() = runBlocking {
        val file = File(app.cacheDir, "ocr_seam_page.jpg")
        file.writeBytes(ByteArray(4) { 1 })
        val result = OcrEngine.recognizeFile(file, OcrLanguage.ENGLISH_HINDI)
        assertTrue(result.text.contains("English headline"))
        assertTrue(result.text.contains("कुल राशि 1250"))
        assertEquals(3, result.page.lines.size)
        assertEquals(1400, result.page.width + result.page.height)
        file.delete()
    }
}
