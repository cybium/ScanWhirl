package com.scanwhirl.app.store

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * T37-EXPORT-HONEST evidence from the real-content QA (EXPORT_QUALITY.md):
 *  - PowerPoint put pages that carry a PDF /Rotate 90 or 270 on the slide as sideways pictures;
 *  - the PowerPoint and Excel descriptions did not say what the tools really do;
 *  - Word answered "Read the text first" where a Hindi user needed the text-language setting.
 *
 * These are the red tests for that work. They use only synthetic pages and fictional text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportHonestT37Test {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "t37_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    // ---- PowerPoint: a sideways page is shown the way a reader sees it ----

    @Test
    fun `a page turned 90 degrees exports an upright slide picture`() {
        // The stored page is the upright page turned 90 degrees clockwise (what Android's
        // PdfRenderer leaves behind for a page with /Rotate 90). Its recognised lines are tall
        // and their tops all sit on the page's left margin.
        val stored = rotateJpeg(cornerPage(400, 560), 90)
        val id = saveTwoPages(stored, OcrPage(560, 400, tallLines(alignedTop = true)))

        val deck = File(work, "sideways-90.pptx")
        runBlocking { repo.exportPowerPoint(id, deck) }

        val picture = slidePicture(deck, 2)
        assertTrue(
            "the second slide must show a portrait, upright picture (was ${picture.width}x${picture.height})",
            picture.height > picture.width
        )
        assertCorner(picture, "top left", Color.RED, x = 34, y = 34)
        assertCorner(picture, "top right", Color.BLUE, x = picture.width - 34, y = 34)
        assertCorner(picture, "bottom left", Color.GREEN, x = 34, y = picture.height - 34)
        assertCorner(picture, "bottom right", Color.YELLOW, x = picture.width - 34, y = picture.height - 34)
    }

    @Test
    fun `a page turned the other way exports an upright slide picture`() {
        // Stored page turned 270 degrees clockwise: line bottoms sit on the left margin.
        val stored = rotateJpeg(cornerPage(400, 560), 270)
        val id = saveTwoPages(stored, OcrPage(560, 400, tallLines(alignedTop = false)))

        val deck = File(work, "sideways-270.pptx")
        runBlocking { repo.exportPowerPoint(id, deck) }

        val picture = slidePicture(deck, 2)
        assertTrue(
            "the second slide must show a portrait, upright picture (was ${picture.width}x${picture.height})",
            picture.height > picture.width
        )
        assertCorner(picture, "top left", Color.RED, x = 34, y = 34)
        assertCorner(picture, "top right", Color.BLUE, x = picture.width - 34, y = 34)
        assertCorner(picture, "bottom left", Color.GREEN, x = 34, y = picture.height - 34)
        assertCorner(picture, "bottom right", Color.YELLOW, x = picture.width - 34, y = picture.height - 34)
    }

    @Test
    fun `a page without sideways evidence is left alone`() {
        val id = saveTwoPages(
            plainPage(560, 400),
            OcrPage(560, 400, wideLines())
        )
        val deck = File(work, "landscape.pptx")
        runBlocking { repo.exportPowerPoint(id, deck) }

        val picture = slidePicture(deck, 2)
        assertEquals("a landscape page stays landscape", 560, picture.width)
        assertEquals(400, picture.height)
    }

    // ---- Word: no text says what to do next --------------------------------

    @Test
    fun `word without recognised text names the text language setting`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Hindi scan")
        val out = File(work, "Hindi scan.docx")

        val failure = runCatching { runBlocking { repo.exportWord(id, out) } }

        assertTrue("the export must still fail honestly", failure.isFailure)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue("must name the setting: $message", message.contains("Text language"))
        assertTrue("must name Hindi: $message", message.contains("Hindi"))
        assertTrue("must say how to try again: $message", message.contains("try again", ignoreCase = true))
        assertFalse("no half written file", out.exists())
    }

    // ---- Honest descriptions ---------------------------------------------

    @Test
    fun `powerpoint, excel and word descriptions are honest in english and hindi`() {
        val en = locale("en")
        val hi = locale("hi")

        val enPptx = en.getString(R.string.tools_setup_pptx_note)
        val hiPptx = hi.getString(R.string.tools_setup_pptx_note)
        assertFalse("slide text must not be called editable: $enPptx", enPptx.contains("editable", true))
        assertFalse("slide text must not be called editable: $hiPptx", hiPptx.contains("editable", true))
        assertTrue("the PowerPoint note must say the slide is a picture: $enPptx", enPptx.contains("picture", true))
        assertTrue("the PowerPoint note must say where the text goes: $enPptx", enPptx.contains("notes", true))
        assertTrue("the Hindi note must say picture: $hiPptx", hiPptx.contains("तस्वीर"))
        assertTrue("the Hindi note must say notes: $hiPptx", hiPptx.contains("टिप्पण"))

        val enExcel = en.getString(R.string.tools_setup_excel_note)
        val hiExcel = hi.getString(R.string.tools_setup_excel_note)
        assertTrue("Excel must say tables are found automatically: $enExcel", enExcel.contains("automatic", true))
        assertTrue("Excel must warn about columns: $enExcel", enExcel.contains("column", true))
        assertTrue("Excel must ask people to check: $enExcel", enExcel.contains("check", true))
        assertTrue("Hindi Excel note must warn about columns: $hiExcel", hiExcel.contains("स्तंभ"))
        assertTrue("Hindi Excel note must ask people to check: $hiExcel", hiExcel.contains("जाँच"))

        val enWord = en.getString(R.string.tools_word_no_text)
        val hiWord = hi.getString(R.string.tools_word_no_text)
        assertTrue("the Word message must name the text language: $enWord", enWord.contains("Text language"))
        assertTrue("the Hindi message must name the language setting: $hiWord", hiWord.contains("लिखे हुए की भाषा"))
        assertTrue("the Hindi message must name Hindi: $hiWord", hiWord.contains("हिंदी"))

        val enResult = en.getString(R.string.tools_result_pptx_note)
        val hiResult = hi.getString(R.string.tools_result_pptx_note)
        assertTrue("the result must say the slide is a picture: $enResult", enResult.contains("picture", true))
        assertTrue("the result must say the text is in the notes: $enResult", enResult.contains("notes", true))
        assertTrue("the Hindi result must say picture: $hiResult", hiResult.contains("तस्वीर"))
        assertTrue("the Hindi result must say notes: $hiResult", hiResult.contains("टिप्पण"))
    }

    // ---- fixtures ---------------------------------------------------------

    private fun locale(language: String): Context {
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale(language))
        return app.createConfigurationContext(config)
    }

    private fun saveTwoPages(second: ByteArray, secondGeometry: OcrPage): String {
        val first = File(work, "p0.jpg").apply { writeBytes(uprightPage(400, 560)) }
        val page2 = File(work, "p1.jpg").apply { writeBytes(second) }
        val id = runBlocking { repo.saveScan(listOf(Uri.fromFile(first), Uri.fromFile(page2)), null, "Sideways") }.id
        val name = repo.getDocument(id)!!.pageFiles[1].name
        repo.ocrStore.putPage(id, name, "line from the page", secondGeometry)
        return id
    }

    private fun slidePicture(deck: File, slide: Int): Bitmap {
        val bytes = ZipFile(deck).use { zip ->
            zip.getInputStream(zip.getEntry("ppt/media/image$slide.jpeg")).use { it.readBytes() }
        }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "slide $slide has no decodable picture"
        }
    }

    private fun assertCorner(image: Bitmap, corner: String, expected: Int, x: Int, y: Int) {
        val actual = image.getPixel(x, y)
        val close = listOf(Color.red(expected) - Color.red(actual), Color.green(expected) - Color.green(actual),
            Color.blue(expected) - Color.blue(actual)).all { kotlin.math.abs(it) <= 60 }
        assertTrue("$corner should be $expected but was $actual", close)
    }

    /** Six tall lines whose tops (or bottoms) share the page's left margin. */
    private fun tallLines(alignedTop: Boolean): List<OcrLine> = (0 until 6).map { i ->
        val left = 30 + i * 34
        val right = left + 28
        if (alignedTop) {
            OcrLine("line $i", left, 40, right, 300 + i * 12)
        } else {
            OcrLine("line $i", left, 240 + i * 12, right, 380)
        }
    }

    /** Upright lines: wide boxes, so nothing says the page is turned. */
    private fun wideLines(): List<OcrLine> = (0 until 6).map { i ->
        OcrLine("line $i", 40, 40 + i * 50, 500, 70 + i * 50)
    }

    private fun uprightPage(width: Int, height: Int): ByteArray = cornerPage(width, height)

    private fun plainPage(width: Int, height: Int): ByteArray = cornerPage(width, height)

    /** Four coloured corners make a wrong turn visible in the exported picture. */
    private fun cornerPage(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint()
            val block = (minOf(width, height) / 5).toFloat()
            val inset = block / 3f
            listOf(
                Color.RED to (inset to inset),
                Color.BLUE to (width - inset - block to inset),
                Color.GREEN to (inset to height - inset - block),
                Color.YELLOW to (width - inset - block to height - inset - block)
            ).forEach { (color, at) ->
                paint.color = color
                canvas.drawRect(at.first, at.second, at.first + block, at.second + block, paint)
            }
            return jpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun rotateJpeg(source: ByteArray, degrees: Int): ByteArray {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size))
        val turned = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
        try {
            return jpeg(turned)
        } finally {
            if (turned !== bitmap) turned.recycle()
            bitmap.recycle()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out))
        return out.toByteArray()
    }
}
