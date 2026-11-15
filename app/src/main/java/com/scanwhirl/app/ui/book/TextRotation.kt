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
 * Which 90° turn makes a sideways page's text upright: ML Kit text recognition on both candidate
 * rotations of a downscaled page, more recognised words wins. The engine's margin heuristic is
 * the offline fallback; the preview's "Rotate pages" button flips the other candidate.
 *
 * [chooser] is the test seam; a fake returns a fixed rotation.
 */
internal object TextRotation {

    @Volatile
    internal var chooser: (suspend (Bitmap) -> Int?)? = null

    suspend fun chooseRotation(bitmap: Bitmap): Int? {
        chooser?.let { return it(bitmap) }
        return withContext(Dispatchers.IO) { recognise(bitmap) }
    }

    private fun recognise(bitmap: Bitmap): Int? {
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
        val clockwise = rotate(small, 90f)
        val counter = rotate(small, -90f)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val cw = words(recognizer, clockwise)
            val ccw = words(recognizer, counter)
            when {
                cw == ccw -> null
                cw > ccw -> 90
                else -> 270
            }
        } catch (_: Exception) {
            null
        } finally {
            recognizer.close()
            clockwise.recycle()
            counter.recycle()
            if (small !== bitmap) small.recycle()
        }
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
