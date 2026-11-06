package com.scanwhirl.app.util

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    suspend fun recognize(page: File): String = withContext(Dispatchers.IO) {
        require(page.exists() && page.length() > 0L) { "Page is empty" }
        val bitmap = BitmapFactory.decodeFile(page.path)
            ?: throw IllegalStateException("Unreadable page")
        try {
            recognize(InputImage.fromBitmap(bitmap, 0))
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
