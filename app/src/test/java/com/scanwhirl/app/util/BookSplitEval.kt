package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.scanwhirl.app.util.BookSplit
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Book pages real-photo evaluation (test source; never committed data). Two passes per photo:
 * raw and a hand-made crop (`crops.txt`). The preview shows the upright image with the line and
 * the two resulting pages.
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
        val pass: Boolean,
        val rawRatio: Float,
        val rawConfidence: Float,
        val rawRotation: Int,
        val croppedRatio: Float,
        val croppedConfidence: Float,
        val croppedRotation: Int,
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
        "book_px762687" to Expectation(0.45f, 0.55f, false, "spine"),
        "book_px13752245" to Expectation(0.45f, 0.55f, false, "spine"),
        "book_px23021414" to Expectation(0.40f, 0.62f, false, "spine in the book region"),
        "book_px3303641" to Expectation(0.38f, 0.56f, false, "not clutter"),
        "book_px9490246" to Expectation(0.38f, 0.70f, false, "near the spine"),
        "book_px34042183" to Expectation(null, null, true, "tall crop: one page per the aspect rule"),
        "book_px18632404" to Expectation(null, null, true, "one page")
    )

    private val OWNER = mapOf(
        "IMG_20261115_005907_312" to Expectation(0.30f, 0.70f, false, "sideways textbook"),
        "IMG_20261115_005913_843" to Expectation(0.30f, 0.70f, false, "sideways textbook"),
        "IMG_20261115_005924_273" to Expectation(0.30f, 0.70f, false, "sideways textbook"),
        "IMG_20261115_010022_505" to Expectation(null, null, true, "single notebook page"),
        "IMG_20261115_010034_806" to Expectation(null, null, true, "single notebook page"),
        "IMG_20261115_010054_411" to Expectation(null, null, true, "single notebook page")
    )

    fun run(folder: Folder): List<Result> {
        val out = File(folder.dir, folder.outSubdir).apply { mkdirs() }
        val crops = readCrops(File(folder.dir, "crops.txt"))
        val photos = if (folder.photoSubdir.isBlank()) folder.dir else File(folder.dir, folder.photoSubdir)
        val lines = mutableListOf<String>()
        val results = mutableListOf<Result>()
        val diagnostics = mutableListOf<String>()

        for ((name, expectation) in folder.expectations) {
            val file = File(photos, "$name.jpg")
            if (!file.isFile) {
                lines += "$name: MISSING (${file.absolutePath})"
                continue
            }
            val source = BookSplit.decode(file.readBytes())
            try {
                val raw = BookSplit.analyse(source)
                val rawPreview = File(out, "${name}_raw_preview.jpg")
                writePreview(source, raw, rawPreview)

                val box = crops[name]
                val croppedSource = if (box != null) crop(source, box) else source
                val cropped = BookSplit.analyse(croppedSource)
                val p90raw = BookSplit.blankMarginPosition(source, 90)
                val p270raw = BookSplit.blankMarginPosition(source, 270)
                val p90crop = BookSplit.blankMarginPosition(croppedSource, 90)
                val p270crop = BookSplit.blankMarginPosition(croppedSource, 270)
                diagnostics += String.format(
                    java.util.Locale.ROOT,
                    "%-24s cue raw p90=%s p270=%s | crop p90=%s p270=%s",
                    name, p90raw, p270raw, p90crop, p270crop
                )
                val croppedPreview = File(out, "${name}_cropped_preview.jpg")
                writePreview(croppedSource, cropped, croppedPreview)
                if (croppedSource !== source) croppedSource.recycle()

                val pass = if (expectation.singlePage) {
                    cropped.gutter.singlePage
                } else {
                    val lo = expectation.minRatio
                    val hi = expectation.maxRatio
                    !cropped.gutter.singlePage && lo != null && hi != null &&
                        cropped.gutter.ratio in lo..hi
                }
                val cg = cropped.gutter
                val slant = if (cg.slanted) String.format(java.util.Locale.ROOT, " slant %.3f->%.3f", cg.topRatio, cg.bottomRatio) else " vertical"
                val line = String.format(
                    java.util.Locale.ROOT,
                    "%-24s RAW r=%.3f c=%.2f a=%.2f rot=%d | CROP r=%.3f c=%.2f a=%.2f rot=%d single=%s%s %s (%s)",
                    name, raw.gutter.ratio, raw.gutter.confidence, raw.aspect, raw.rotation,
                    cg.ratio, cg.confidence, cropped.aspect,
                    cropped.rotation, cg.singlePage.toString(), slant,
                    if (pass) "PASS" else "FAIL", expectation.note
                )
                lines += line
                results += Result(
                    name, pass,
                    raw.gutter.ratio, raw.gutter.confidence, raw.rotation,
                    cropped.gutter.ratio, cropped.gutter.confidence, cropped.rotation,
                    cropped.gutter.singlePage,
                    expectation.note, rawPreview.name, croppedPreview.name
                )
            } finally {
                source.recycle()
            }
        }

        val report = lines.joinToString("\n") + "\n\n" + diagnostics.joinToString("\n")
        val resultsFile = File(out, "RESULTS.txt").absoluteFile
        println("--- Book pages eval: ${folder.label} -> ${resultsFile.absolutePath} ---")
        println(report)
        resultsFile.writeText(report + "\n")
        println("WROTE ${resultsFile.absolutePath} at ${resultsFile.lastModified()}")
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

    private fun writePreview(source: Bitmap, analysis: BookSplit.Analysis, target: File) {
        val upright = BookSplit.rotate(source, analysis.rotation)
        try {
            val photoWidth = 1200
            val photoHeight = max(1, (upright.height * photoWidth / upright.width.toFloat()).roundToInt())
            val photo = Bitmap.createScaledBitmap(upright, photoWidth, photoHeight, true)
            val annotated = photo.copy(Bitmap.Config.ARGB_8888, true)
            if (photo !== annotated) photo.recycle()

            val g = analysis.gutter
            val topR = g.topRatio ?: g.ratio
            val bottomR = g.bottomRatio ?: g.ratio
            val inkRows = SpineLine.inkRowsFraction(SpineLine.grid(upright), topR, bottomR)
            Canvas(annotated).apply {
                // A thin line so the reviewer can see whether it touches letters.
                drawLine(
                    (topR * photoWidth).coerceIn(0f, photoWidth - 1f), 0f,
                    (bottomR * photoWidth).coerceIn(0f, photoWidth - 1f), photoHeight.toFloat(),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.RED
                        strokeWidth = 2f
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
                        "line %.3f->%.3f  ink %.1f%%  conf %.2f  aspect %.2f  text %s  rot %d  %s",
                        topR, bottomR, inkRows * 100f, analysis.gutter.confidence, analysis.aspect,
                        if (analysis.textVertical) "V" else "H",
                        analysis.rotation,
                        if (analysis.gutter.singlePage) "ONE PAGE" else "SPREAD"
                    ),
                    14f, 34f, label
                )
            }

            val split = BookSplit.split(upright, analysis.gutter)
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
        } finally {
            if (upright !== source) upright.recycle()
        }
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
