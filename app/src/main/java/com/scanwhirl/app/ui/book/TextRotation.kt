package com.scanwhirl.app.ui.book

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scanwhirl.app.util.BookSplit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Which way to turn the top page of a **horizontal** spread so both pages come out upright.
 *
 * On a phone with Google Play services, ML Kit text recognition runs on both 90° candidate
 * rotations of a downscaled top page and picks the one with more recognised words. Without it
 * (Robolectric, no model), the offline margin heuristic in [BookSplit.topRotatesClockwise] is
 * used, and the preview's "Rotate" button lets the owner flip the result.
 *
 * [chooser] is the test seam; a fake returns a fixed direction.
 */
internal object TextRotation {

    @Volatile
    internal var chooser: (suspend (Bitmap, BookSplit.Gutter) -> Boolean)? = null

    suspend fun choose(bitmap: Bitmap, gutter: BookSplit.Gutter): Boolean {
        chooser?.let { return it(bitmap, gutter) }
        if (!gutter.horizontal) return true
        return withContext(Dispatchers.IO) {
            recognise(bitmap, gutter) ?: BookSplit.topRotatesClockwise(bitmap, gutter)
        }
    }

    private fun recognise(bitmap: Bitmap, gutter: BookSplit.Gutter): Boolean? {
        val cut = (gutter.ratio * bitmap.height).roundToInt().coerceIn(1, bitmap.height - 1)
        val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cut)
        val scale = 700f / max(bitmap.width, cut)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                top, max(1, (bitmap.width * scale).toInt()), max(1, (cut * scale).toInt()), true
            )
        } else {
            top
        }
        if (small !== top) top.recycle()

        val clockwise = rotate(small, 90f)
        val counter = rotate(small, -90f)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val cw = words(recognizer, clockwise)
            val ccw = words(recognizer, counter)
            if (cw == ccw) null else cw > ccw
        } catch (_: Exception) {
            null
        } finally {
            recognizer.close()
            clockwise.recycle()
            counter.recycle()
            small.recycle()
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
