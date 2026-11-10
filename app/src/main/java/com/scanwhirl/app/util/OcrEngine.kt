package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR (Pro): Google's on-device Text Recognition runs completely on the
 * phone through Play services — the model downloads on first use and no image
 * ever leaves the app. Honest label used in the UI: works best on printed
 * text; handwriting is unreliable.
 *
 * Text is assembled from every block in top-to-bottom, left-to-right reading
 * order. The previous version could lose everything after a block's first
 * line when the text was persisted (fixed in ScanRepository's OCR store).
 */
object OcrEngine {

    /** One recognised text block, reduced to what reading order needs. */
    data class OcrBlock(val top: Int, val left: Int, val lines: List<String>)

    /** A full OCR result: assembled plain text plus per-line geometry. */
    data class OcrResult(val text: String, val page: OcrPage)

    suspend fun recognize(page: File): String = recognizeFile(page).text

    /**
     * Recognises a single page image file and returns both the assembled text
     * and the per-line geometry ([OcrPage]). Decodes the bitmap once and runs
     * the recognizer once; lines are built from the same blocks in the same
     * reading order as [assemble]. Blocks are sorted by top then left; a line
     * without a bounding box is skipped.
     */
    suspend fun recognizeFile(page: File): OcrResult = withContext(Dispatchers.IO) {
        require(page.exists() && page.length() > 0L) { "Page is empty" }
        val bitmap = BitmapFactory.decodeFile(page.path)
            ?: throw IllegalStateException("Unreadable page")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = suspendCancellableCoroutine<OcrResult> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        // Blocks for assemble: top then left reading order.
                        val blocks = text.textBlocks.map { block ->
                            OcrBlock(
                                top = block.boundingBox?.top ?: 0,
                                left = block.boundingBox?.left ?: 0,
                                lines = block.lines.map { it.text }
                            )
                        }
                        val assembled = assemble(blocks)

                        // Per-line geometry from the same blocks in reading order.
                        val lines = text.textBlocks
                            .filter { block -> block.lines.any { it.text.isNotBlank() } }
                            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                            .flatMap { block ->
                                block.lines.dropWhile { it.text.isBlank() }
                                    .dropLastWhile { it.text.isBlank() }
                                    .mapIndexed { i, line ->
                                        val lineBox = line.boundingBox
                                        val blockBox = block.boundingBox
                                        OcrLine(
                                            text = line.text,
                                            left = lineBox?.left ?: blockBox?.left ?: 0,
                                            top = lineBox?.top ?: 0,
                                            right = lineBox?.right ?: blockBox?.right ?: 0,
                                            bottom = lineBox?.bottom ?: blockBox?.bottom ?: 0
                                        )
                                    }
                            }
                        val ocrPage = OcrPage(width = width, height = height, lines = lines)
                        cont.resume(OcrResult(assembled, ocrPage))
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resumeWithException(e)
                    }
            }
            recognizer.close()
            result
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** OCR directly from encoded image bytes (device PDFs and gallery images). */
    suspend fun recognizeBytes(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Image is empty" }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Unreadable image")
        try {
            recognize(InputImage.fromBitmap(bitmap, 0))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** OCR a bitmap the caller owns (it is not recycled here). */
    suspend fun recognizeBitmap(bitmap: Bitmap): String = recognize(InputImage.fromBitmap(bitmap, 0))

    suspend fun recognize(image: InputImage): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val blocks = text.textBlocks.map { block ->
                            OcrBlock(
                                top = block.boundingBox?.top ?: 0,
                                left = block.boundingBox?.left ?: 0,
                                lines = block.lines.map { it.text }
                            )
                        }
                        if (cont.isActive) cont.resume(assemble(blocks))
                    }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resumeWithException(e)
                    }
            }
        } finally {
            recognizer.close()
        }
    }

    /**
     * Pure reading-order assembly. Blocks are sorted by their top edge, then
     * left edge; all lines inside a block are preserved. A plain blank line
     * separates paragraphs so copied text stays readable.
     */
    fun assemble(blocks: List<OcrBlock>): String =
        blocks
            .filter { block -> block.lines.any { it.isNotBlank() } }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .joinToString("\n\n") { block ->
                block.lines.dropWhile { it.isBlank() }
                    .dropLastWhile { it.isBlank() }
                    .joinToString("\n")
            }
            .trim()
}
