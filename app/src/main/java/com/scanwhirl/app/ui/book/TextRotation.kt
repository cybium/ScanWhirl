package com.scanwhirl.app.ui.book

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Which turn makes a crop's text upright: ML Kit text recognition on every candidate rotation
 * (90/270 for vertical text, 0/180 for an upright spread) of a downscaled page; the most words
 * wins. The engine's ascender-asymmetry cue is the offline fallback; the preview's "Flip pages"
 * button flips the answer 180°.
 *
 * [chooser] is the UI test seam; [wordCounter] is the recognizer seam for scoring tests.
 */
internal object TextRotation {

    @Volatile
    internal var chooser: (suspend (Bitmap, List<Int>) -> Int?)? = null

    /** Fake recognizer: returns the number of "words" it reads; null uses ML Kit. */
    @Volatile
    internal var wordCounter: ((Bitmap) -> Int)? = null

    suspend fun chooseRotation(bitmap: Bitmap, textVertical: Boolean): Int? {
        val candidates = if (textVertical) listOf(90, 270) else listOf(0, 180)
        chooser?.let { return it(bitmap, candidates) }
        // ML Kit is an optional confirmation: on a device without the model (or in a unit
        // test) the engine's own rotation is still a complete answer.
        return withContext(Dispatchers.IO) {
            runCatching { recognise(bitmap, candidates) }.getOrNull()
        }
    }

    private fun recognise(bitmap: Bitmap, candidates: List<Int>): Int? {
        val scale = 700f / max(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true
            )
        } else {
            bitmap
        }
        val recognizer = if (wordCounter == null) {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } else {
            null
        }
        var best: Int? = null
        var bestWords = -1
        try {
            for (rotation in candidates) {
                val page = rotate(small, rotation.toFloat())
                val words = try {
                    wordCounter?.invoke(page) ?: words(recognizer!!, page)
                } catch (_: Exception) {
                    -1
                }
                if (words > bestWords) {
                    bestWords = words
                    best = rotation
                }
                page.recycle()
            }
        } finally {
            recognizer?.close()
            if (small !== bitmap) small.recycle()
        }
        return if (bestWords > 0) best else null
    }

    private fun words(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): Int {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        return result.textBlocks.sumOf { block ->
            block.lines.sumOf { line -> line.text.split(' ').count { it.isNotBlank() } }
        }
    }

    private fun rotate(source: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(degrees) }, true
        )
}
