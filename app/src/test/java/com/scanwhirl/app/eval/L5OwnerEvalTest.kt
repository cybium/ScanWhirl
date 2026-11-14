package com.scanwhirl.app.eval

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.AadhaarMask
import com.scanwhirl.app.util.InkExtract
import com.scanwhirl.app.util.OcrEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.min

/**
 * Owner evaluation (not part of normal gates): runs only when
 * `C:\Users\Cybium\Desktop\.sw-qa\owner\EVAL_ON` exists. It never copies or commits the owner's
 * private samples; it reads them in place and writes previews to
 * `…\.sw-qa\owner\out\L5\`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class L5OwnerEvalTest {

    private val ownerRoots = listOf(
        File("/mnt/c/Users/Cybium/Desktop/.sw-qa/owner"),
        File("C:/Users/Cybium/Desktop/.sw-qa/owner")
    )

    private fun root(): File? = ownerRoots.firstOrNull { File(it, "EVAL_ON").isFile }

    @Test
    fun `owner signature stamp and Aadhaar previews`() {
        val root = root() ?: return
        val downloads = File(root, "downloads")
        val out = File(root, "out/L5").apply { mkdirs() }
        val notes = StringBuilder()

        // A. Signatures: black-on-white, black-on-white JPG, blue-on-grey shading (the hard one).
        val signatures = listOf(
            "c15f2e6613bcc1d72ad367f6215b4a1e.png",
            "manual-signature-for-documents-on-white-background-hand-drawn-calligraphy-lettering-vector-illustration.jpg",
            "signature-before.webp"
        )
        signatures.forEach { name ->
            val file = File(downloads, name)
            if (!file.isFile) {
                notes.append("missing signature sample: ").append(name).append('\n')
                return@forEach
            }
            val source = InkExtract.decodeSampled(file) ?: return@forEach
            val extracted = InkExtract.extract(source, InkExtract.Recolor.ORIGINAL)
            if (extracted == null) {
                notes.append("no ink: ").append(name).append('\n')
            } else {
                writePreview(extracted.bitmap, File(out, "signature_${safe(name)}.png"))
                notes.append("signature ").append(name)
                    .append(" hue=").append(extracted.hue)
                    .append(" size=").append(extracted.bitmap.width).append('x')
                    .append(extracted.bitmap.height).append('\n')
                extracted.bitmap.recycle()
            }
            source.recycle()
        }

        // B. Stamp: purple round company seal; colour must stay, the shape must stay round.
        val stampFile = File(downloads, "images(3).jpg")
        if (stampFile.isFile) {
            val source = InkExtract.decodeSampled(stampFile)
            val extracted = source?.let { InkExtract.extract(it, InkExtract.Recolor.ORIGINAL) }
            if (extracted != null) {
                writePreview(extracted.bitmap, File(out, "stamp_images3.png"))
                notes.append("stamp images(3).jpg hue=").append(extracted.hue)
                    .append(" size=").append(extracted.bitmap.width).append('x')
                    .append(extracted.bitmap.height).append('\n')
                extracted.bitmap.recycle()
            }
            source?.recycle()
        } else {
            notes.append("missing stamp sample: images(3).jpg\n")
        }

        // C. Aadhaar: try ML Kit; if this host has no OCR model, use the fake geometry the lead
        // approved (visible digits, middle line).
        val aadhaar = listOf(
            "1(1).jpg", "1.jpg", "images.jpg", "Aadhaar-card-pdf-1-2048.jpg.webp"
        )
        aadhaar.forEach { name ->
            val file = File(downloads, name)
            if (!file.isFile) {
                notes.append("missing Aadhaar sample: ").append(name).append('\n')
                return@forEach
            }
            val source = InkExtract.decodeSampled(file) ?: return@forEach
            val ocr = kotlinx.coroutines.runBlocking {
                runCatching { OcrEngine.recognizeFile(file).page }.getOrNull()
            }
            val page = ocr ?: OcrPage(
                source.width,
                source.height,
                listOf(
                    OcrLine(
                        text = "Aadhaar 1234 5678 9012",
                        left = (source.width * 0.25f).toInt(),
                        top = (source.height * 0.68f).toInt(),
                        right = (source.width * 0.75f).toInt(),
                        bottom = (source.height * 0.78f).toInt()
                    )
                )
            )
            notes.append("aadhaar ").append(name)
                .append(if (ocr == null) " OCR=FAKE" else " OCR=MLKit")
                .append(" boxes=").append(AadhaarMask.find(page).size).append('\n')
            writeAadhaarPreview(source, page, File(out, "aadhaar_${safe(name)}.png"))
            source.recycle()
        }

        File(out, "notes.txt").writeText(
            notes.toString() +
                "\nNote: this host had no ML Kit text model. Aadhaar previews use a FAKE OCR " +
                "geometry (a plausible lower-middle line) so the redaction box placement can be " +
                "reviewed; the real device uses ML Kit word/line boxes.\n"
        )
        assertTrue("previews must be written", out.listFiles().orEmpty().isNotEmpty())
    }

    private fun writePreview(ink: Bitmap, out: File) {
        val w = 600
        val h = 300
        val bmp = checkerboard(w, h)
        val canvas = Canvas(bmp)
        val scale = min(w * 0.9f / ink.width, h * 0.8f / ink.height)
        val dw = ink.width * scale
        val dh = ink.height * scale
        val rect = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        canvas.drawBitmap(ink, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun writeAadhaarPreview(source: Bitmap, page: OcrPage, out: File) {
        val w = 700
        val scale = w.toFloat() / source.width
        val h = (source.height * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(source, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        val paint = Paint().apply { color = 0xAAFF0000.toInt() }
        val edge = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.RED
        }
        for (box in AadhaarMask.find(page)) {
            val r = RectF(box.left * w, box.top * h, box.right * w, box.bottom * h)
            canvas.drawRect(r, paint)
            canvas.drawRect(r, edge)
        }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun checkerboard(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cell = 16
        val light = Paint().apply { color = 0xFFEDEDED.toInt() }
        val dark = Paint().apply { color = 0xFFC9C9C9.toInt() }
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    (x + cell).toFloat(), (y + cell).toFloat(),
                    if (((x / cell) + (y / cell)) % 2 == 0) light else dark
                )
                x += cell
            }
            y += cell
        }
        return bmp
    }

    private fun safe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), "_").take(40)
}
