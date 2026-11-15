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
 * Evaluation of "Book pages" on real photos (test data, never committed). Not a JUnit class:
 * [BookSplitRealWorldTest] calls it only when the QA folder exists (or `SW_REALWORLD_DIR` is set).
 *
 * Two passes per photo:
 *  - **raw**: the photo as taken (the page-region guard and honest confidence are judged here);
 *  - **cropped**: a hand-made crop from `crops.txt` (the scanner's job), judged against the spine.
 *
 * Each pass writes a preview JPEG: the annotated spread with the chosen line (red) and the two
 * resulting pages side by side (already turned upright for a horizontal spine).
 */
object BookSplitEval {

    data class Expectation(
        val minRatio: Float?,
        val maxRatio: Float?,
        val singlePage: Boolean,
        val horizontal: Boolean?,
        val note: String
    )

    data class Result(
        val name: String,
        val pass: Boolean,
        val rawRatio: Float,
        val rawConfidence: Float,
        val rawHorizontal: Boolean,
        val croppedRatio: Float,
        val croppedConfidence: Float,
        val croppedHorizontal: Boolean,
        val croppedSinglePage: Boolean,
        val expectation: String,
        val rawPreview: String,
        val croppedPreview: String
    )

    data class Folder(
        val label: String,
        val dir: File,
        val expectations: Map<String, Expectation>,
        val outSubdir: String,
        val photoSubdir: String = ""
    )

    private val REALWORLD = mapOf(
        "book_px762687" to Expectation(0.45f, 0.55f, false, false, "split at the spine"),
        "book_px13752245" to Expectation(0.45f, 0.55f, false, false, "spine, not a column gap"),
        "book_px9490246" to Expectation(0.45f, 0.50f, false, false, "middle third near the spine"),
        "book_px23021414" to Expectation(0.53f, 0.59f, false, false, "shadows must not pull the line"),
        "book_px3303641" to Expectation(0.42f, 0.48f, false, false, "line must not snap to clutter"),
        "book_px34042183" to Expectation(0.51f, 0.57f, false, false, "spine, not the text edge"),
        "book_px18632404" to Expectation(null, null, true, null, "one page: keep as one page")
    )

    /** Owner's sideways phone photos: the spine is horizontal and the pages must come out upright. */
    private val OWNER = mapOf(
        "IMG_20261115_005907_312" to Expectation(0.45f, 0.55f, false, true, "horizontal spine"),
        "IMG_20261115_005913_843" to Expectation(0.45f, 0.55f, false, true, "horizontal spine"),
        "IMG_20261115_005924_273" to Expectation(0.45f, 0.55f, false, true, "horizontal spine"),
        "IMG_20261115_010022_505" to Expectation(0.78f, 0.90f, false, true, "notebook fold"),
        "IMG_20261115_010034_806" to Expectation(0.78f, 0.90f, false, true, "notebook fold"),
        "IMG_20261115_010054_411" to Expectation(0.70f, 0.92f, false, null, "notebook page")
    )

    fun run(folder: Folder): List<Result> {
        val out = File(folder.dir, folder.outSubdir).apply { mkdirs() }
        val crops = readCrops(File(folder.dir, "crops.txt"))
        val lines = mutableListOf<String>()
        val results = mutableListOf<Result>()

        for ((name, expectation) in folder.expectations) {
            val photos = if (folder.photoSubdir.isBlank()) folder.dir else File(folder.dir, folder.photoSubdir)
            val file = File(photos, "$name.jpg")
            if (!file.isFile) {
                lines += "$name: MISSING (${file.absolutePath})"
                continue
            }
            val source = BookSplit.decode(file.readBytes())
            try {
                val raw = BookSplit.scanGutter(source)
                val rawRotate = if (raw.horizontal) BookSplit.topRotatesClockwise(source, raw) else true
                val rawPreview = File(out, "${name}_raw_preview.jpg")
                writePreview(source, raw, rawRotate, rawPreview)

                val box = crops[name]
                val croppedSource = if (box != null) crop(source, box) else source
                val cropped = BookSplit.scanGutter(croppedSource)
                val croppedRotate = if (cropped.horizontal) {
                    BookSplit.topRotatesClockwise(croppedSource, cropped)
                } else {
                    true
                }
                val croppedPreview = File(out, "${name}_cropped_preview.jpg")
                writePreview(croppedSource, cropped, croppedRotate, croppedPreview)
                if (croppedSource !== source) croppedSource.recycle()

                val pass = if (expectation.singlePage) {
                    cropped.singlePage
                } else {
                    val lo = expectation.minRatio
                    val hi = expectation.maxRatio
                    val orientationOk = expectation.horizontal == null ||
                        cropped.horizontal == expectation.horizontal
                    lo != null && hi != null && cropped.ratio in lo..hi && orientationOk
                }
                val line = String.format(
                    java.util.Locale.ROOT,
                    "%-24s RAW r=%.3f c=%.2f %s | CROP r=%.3f c=%.2f %s exp %.2f-%.2f %s (%s) previews=%s,%s",
                    name, raw.ratio, raw.confidence,
                    if (raw.horizontal) "H" else "V",
                    cropped.ratio, cropped.confidence,
                    if (cropped.horizontal) "H" else "V",
                    expectation.minRatio ?: -1f, expectation.maxRatio ?: -1f,
                    if (pass) "PASS" else "FAIL", expectation.note,
                    rawPreview.name, croppedPreview.name
                )
                lines += line
                results += Result(
                    name, pass,
                    raw.ratio, raw.confidence, raw.horizontal,
                    cropped.ratio, cropped.confidence, cropped.horizontal,
                    cropped.singlePage, expectation.note, rawPreview.name, croppedPreview.name
                )
            } finally {
                source.recycle()
            }
        }

        val report = lines.joinToString("\n")
        println("--- Book pages eval: ${folder.label} ---")
        println(report)
        File(out, "RESULTS.txt").writeText(report + "\n")
        return results
    }

    private fun readCrops(file: File): Map<String, FloatArray> {
        if (!file.isFile) return emptyMap()
        val map = mutableMapOf<String, FloatArray>()
        file.readLines().forEach { line ->
            val clean = line.trim()
            if (clean.isEmpty() || clean.startsWith("#")) return@forEach
            val parts = clean.split(Regex("\\s+"))
            if (parts.size == 5) {
                val box = parts.drop(1).mapNotNull { it.toFloatOrNull() }.toFloatArray()
                if (box.size == 4) map[parts[0]] = box
            }
        }
        return map
    }

    private fun crop(source: Bitmap, box: FloatArray): Bitmap {
        val left = (source.width * box[0]).roundToInt().coerceIn(0, source.width - 2)
        val top = (source.height * box[1]).roundToInt().coerceIn(0, source.height - 2)
        val right = (source.width * box[2]).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * box[3]).roundToInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    /**
     * Top: the annotated spread (the line in red). Bottom: the two resulting pages side by side
     * (upright and in reading order for a horizontal spine).
     */
    private fun writePreview(
        source: Bitmap,
        gutter: BookSplit.Gutter,
        rotateTop: Boolean,
        target: File
    ) {
        val photoWidth = 1200
        val photoHeight = max(1, (source.height * photoWidth / source.width.toFloat()).roundToInt())
        val photo = Bitmap.createScaledBitmap(source, photoWidth, photoHeight, true)
        val annotated = photo.copy(Bitmap.Config.ARGB_8888, true)
        if (photo !== annotated) photo.recycle()

        Canvas(annotated).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                strokeWidth = max(3f, photoWidth / 200f)
            }
            if (gutter.horizontal) {
                val y = (gutter.ratio * photoHeight).coerceIn(0f, photoHeight - 1f)
                drawLine(0f, y, photoWidth.toFloat(), y, paint)
            } else {
                val x = (gutter.ratio * photoWidth).coerceIn(0f, photoWidth - 1f)
                drawLine(x, 0f, x, photoHeight.toFloat(), paint)
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            drawText(
                String.format(
                    java.util.Locale.ROOT,
                    "line %.3f  conf %.2f%s%s",
                    gutter.ratio,
                    gutter.confidence,
                    if (gutter.horizontal) "  HORIZONTAL" else "",
                    if (gutter.singlePage) "  ONE PAGE" else ""
                ),
                14f, 34f, label
            )
        }

        val split = BookSplit.split(source, gutter, rotateTop)
        val cellWidth = 594
        val cellHeight = 420
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
        val scale = min(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        return Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()),
            true
        )
    }

    fun realWorld(dir: File): Folder = Folder("realworld", dir, REALWORLD, "out")
    fun owner(dir: File): Folder = Folder("owner", dir, OWNER, "out/L3", "camera")
}
