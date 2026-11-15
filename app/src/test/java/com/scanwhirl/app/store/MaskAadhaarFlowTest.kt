package com.scanwhirl.app.store

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.AadhaarMask
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PageStamp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** L5 Mask Aadhaar: burn the first 8 digits, drop the page's OCR text, keep the original bytes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MaskAadhaarFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After fun tearDown() = Faults.resetAll()

    @Test
    fun `masking burns the boxes removes OCR text and keeps the original`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Aadhaar card")
        val page = repo.getDocument(id)!!.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        val text = "Aadhaar 1234 5678 9012"
        repo.ocrStore.putPage(
            id,
            page.name,
            text,
            OcrPage(w, h, listOf(OcrLine(text, 10, 10, w - 10, 60)))
        )
        val boxes = AadhaarMask.find(repo.ocrStore.readGeo(id)[page.name]!!)
        assertEquals("one Aadhaar number", 1, boxes.size)
        val digest = PdfInvariants.dirDigest(File(repo.docsDir, id))

        val masked = repo.stampDocument(
            id,
            listOf(PageStamp.PageEdits(0, boxes.map { PageStamp.Mark.Redact(it) })),
            "Aadhaar card - Masked"
        )

        assertEquals("Aadhaar card - Masked", masked.name)
        assertEquals("the original must stay byte identical", digest, PdfInvariants.dirDigest(File(repo.docsDir, id)))
        assertTrue("the original keeps its OCR text", repo.ocrDoc(id).isNotEmpty())
        assertFalse(
            "the masked page's OCR text must be gone",
            repo.ocrDoc(masked.id).containsKey(page.name)
        )
        assertTrue(
            "the redacted page is queued for recognition",
            File(File(repo.docsDir, masked.id), "ocr_pending").exists()
        )

        val bmp = BitmapFactory.decodeFile(masked.pageFiles.single().path)
        try {
            val box = boxes.single()
            val px = (((box.left + box.right) / 2f) * bmp.width).toInt()
            val py = (((box.top + box.bottom) / 2f) * bmp.height).toInt()
            val pixel = bmp.getPixel(px, py)
            assertTrue("the mask must be solid ink: ${Integer.toHexString(pixel)}", Color.red(pixel) < 20)
            assertTrue(Color.green(pixel) < 20 && Color.blue(pixel) < 20)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `element geometry masks exactly the first two groups`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Aadhaar words")
        val page = repo.getDocument(id)!!.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        val y0 = (h * 0.70f).toInt()
        val y1 = (h * 0.78f).toInt()
        val words = listOf(
            AadhaarMask.Word("9233", (w * 0.30f).toInt(), y0, (w * 0.42f).toInt(), y1),
            AadhaarMask.Word("6926", (w * 0.44f).toInt(), y0, (w * 0.56f).toInt(), y1),
            AadhaarMask.Word("4947", (w * 0.58f).toInt(), y0, (w * 0.70f).toInt(), y1)
        )
        val boxes = AadhaarMask.findWords(words, w, h)
        assertEquals("first two groups only", 2, boxes.size)

        val masked = repo.stampDocument(
            id,
            listOf(PageStamp.PageEdits(0, boxes.map { PageStamp.Mark.Redact(it) })),
            "Aadhaar words - Masked"
        )

        val bmp = BitmapFactory.decodeFile(masked.pageFiles.single().path)
        try {
            for (box in boxes) {
                val x = (((box.left + box.right) / 2f) * bmp.width).toInt()
                val y = (((box.top + box.bottom) / 2f) * bmp.height).toInt()
                assertTrue("masked group is ink", Color.red(bmp.getPixel(x, y)) < 20)
            }
            val lastX = (((words[2].left + words[2].right) / 2f) / w * bmp.width).toInt()
            val lastY = ((y0 + y1) / 2f / h * bmp.height).toInt()
            assertTrue(
                "the last 4 digits must stay visible",
                Color.red(bmp.getPixel(lastX, lastY)) > 200
            )
        } finally {
            bmp.recycle()
        }
    }
}
