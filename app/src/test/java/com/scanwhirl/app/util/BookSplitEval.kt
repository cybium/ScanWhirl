package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Evaluation of "Book pages" on real textbook photos (test data, never committed). It is not a
 * JUnit class: [BookSplitRealWorldTest] calls [run] only when the lane's QA folder exists (or
 * `SW_REALWORLD_DIR` is set), so the normal gate never touches photos.
 *
 * For every photo it writes one preview JPEG: the photo (1200 px wide) with the chosen gutter in
 * red on top, and the two resulting crops side by side below. [RESULTS] carries the README's
 * expectation for each file; the printed result is compared with it (PASS/FAIL).
 */
object BookSplitEval {

    data class Expectation(
        val minRatio: Float?,
        val maxRatio: Float?,
        val singlePage: Boolean,
        val note: String
    )

    data class Result(
        val name: String,
        val ratio: Float,
        val confidence: Float,
        val found: Boolean,
        val singlePage: Boolean,
        val pass: Boolean,
        val expectation: String,
        val preview: String
    )

    /** The realworld README, one line per photo. */
    private val EXPECTATIONS = linkedMapOf(
        "book_px762687" to Expectation(0.45f, 0.55f, false, "split at the spine"),
        "book_px13752245" to Expectation(0.45f, 0.55f, false, "spine, not a column gap"),
        "book_px9490246" to Expectation(0.33f, 0.66f, false, "middle third near the spine"),
        "book_px23021414" to Expectation(0.42f, 0.58f, false, "shadows must not pull the line"),
        "book_px3303641" to Expectation(0.42f, 0.60f, false, "line must not snap to clutter"),
        "book_px34042183" to Expectation(0.45f, 0.55f, false, "spine, not the text edge"),
        "book_px18632404" to Expectation(null, null, true, "one page: keep as one page")
    )

    fun run(photoDir: File, outDir: File): List<Result> {
        outDir.mkdirs()
        val lines = mutableListOf<String>()
        val results = mutableListOf<Result>()

        for ((name, expectation) in EXPECTATIONS) {
            val file = File(photoDir, "$name.jpg")
            if (!file.isFile) {
                lines += "$name: MISSING (${file.absolutePath})"
                continue
            }
            val bitmap = BookSplit.decode(file.readBytes())
            try {
                val gutter = BookSplit.scanGutter(bitmap)
                val pass = if (expectation.singlePage) {
                    gutter.singlePage
                } else {
                    val lo = expectation.minRatio
                    val hi = expectation.maxRatio
                    lo != null && hi != null && gutter.ratio in lo..hi
                }
                val preview = File(outDir, "${name}_preview.jpg")
                writePreview(bitmap, gutter, preview)

                val line = String.format(
                    java.util.Locale.ROOT,
                    "%-22s gutter=%.3f confidence=%.2f found=%-5s singlePage=%-5s %s (%s)  preview=%s",
                    name, gutter.ratio, gutter.confidence, gutter.found.toString(),
                    gutter.singlePage.toString(), if (pass) "PASS" else "FAIL",
                    expectation.note, preview.name
                )
                lines += line
                results += Result(
                    name = name,
                    ratio = gutter.ratio,
                    confidence = gutter.confidence,
                    found = gutter.found,
                    singlePage = gutter.singlePage,
                    pass = pass,
                    expectation = expectation.note,
                    preview = preview.name
                )
            } finally {
                bitmap.recycle()
            }
        }

        val report = lines.joinToString("\n")
        println("--- Book pages real-world eval ---")
        println(report)
        File(outDir, "RESULTS.txt").writeText(report + "\n")
        return results
    }

    /**
     * Top: the photo at 1200 px wide with the chosen line in red.
     * Bottom: the two crops side by side, each fitted into a 594 x 420 cell.
     */
    private fun writePreview(bitmap: Bitmap, gutter: BookSplit.Gutter, target: File) {
        val photoWidth = 1200
        val photoHeight = max(1, (bitmap.height * photoWidth / bitmap.width.toFloat()).roundToInt())
        val photo = Bitmap.createScaledBitmap(bitmap, photoWidth, photoHeight, true)
        val annotated = photo.copy(Bitmap.Config.ARGB_8888, true)
        if (photo !== annotated) photo.recycle()

        val lineX = (gutter.ratio * photoWidth).coerceIn(0f, (photoWidth - 1).toFloat())
        Canvas(annotated).apply {
            drawLine(
                lineX, 0f, lineX, photoHeight.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.RED
                    strokeWidth = max(3f, photoWidth / 200f)
                }
            )
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            drawText(
                String.format(
                    java.util.Locale.ROOT,
                    "line %.3f  confidence %.2f%s",
                    gutter.ratio,
                    gutter.confidence,
                    if (gutter.singlePage) "  SINGLE PAGE" else ""
                ),
                14f, 34f, label
            )
        }

        val cellWidth = 594
        val cellHeight = 420
        val split = BookSplit.split(bitmap, gutter)
        val left = fitInto(split.left, cellWidth, cellHeight)
        val right = fitInto(split.right, cellWidth, cellHeight)
        split.left.recycle()
        split.right.recycle()

        val sheet = Bitmap.createBitmap(
            photoWidth, photoHeight + 8 + cellHeight, Bitmap.Config.ARGB_8888
        )
        Canvas(sheet).apply {
            drawColor(Color.BLACK)
            drawBitmap(annotated, 0f, 0f, null)
            drawBitmap(left, 0f, (photoHeight + 8).toFloat(), null)
            drawBitmap(right, (cellWidth + 12).toFloat(), (photoHeight + 8).toFloat(), null)
        }
        annotated.recycle()
        left.recycle()
        right.recycle()

        target.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        sheet.recycle()
    }

    private fun fitInto(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = min(
            maxWidth.toFloat() / source.width,
            maxHeight.toFloat() / source.height
        )
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
