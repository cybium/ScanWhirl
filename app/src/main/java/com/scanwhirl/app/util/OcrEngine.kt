package com.scanwhirl.app.util

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
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
 * OCR for a scanned page (Pro): Google's on-device Text Recognition runs
 * completely on the phone through Play services — the model downloads on
 * first use and no image ever leaves the app. Honest label used in the UI:
 * works best on printed text; handwriting is unreliable.
 */
object OcrEngine {

    suspend fun recognize(page: File): String = withContext(Dispatchers.IO) {
        require(page.exists() && page.length() > 0L) { "Page is empty" }
        val bitmap = BitmapFactory.decodeFile(page.path)
            ?: throw IllegalStateException("Unreadable page")
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognize(image)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun recognize(image: InputImage): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val clean = text.textBlocks.joinToString("\n") { block -> block.text }
                    if (cont.isActive) cont.resume(clean)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
    }
}
