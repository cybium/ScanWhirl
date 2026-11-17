package com.scanwhirl.app.eval

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanEnhance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

/**
 * Scan Enhance 2.0 owner evaluation — page-only, readable size.
 *
 * Runs only when `/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/owner/L4_EVAL_ON` exists. Uses the owner's
 * photos only (camera, downloads), crops each to the page/card box in `owner/enh_crops.txt`, and
 * writes one labelled side-by-side per photo plus a 100%-zoom text strip:
 *
 *   <out>/<name>_compare.jpg   original-crop | Auto | Colour boost | B&W   (each panel 900 px)
 *   <out>/<name>_zoom.jpg      the same four at 1:1 on a text region
 *
 * Output: `/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa/owner/out/L4enh/`. No photo is copied or committed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanEnhancePreviewEvalTest {

    private val qa = File("/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/qa")
    private val owner = File(qa, "owner")
    private val flag = File(owner, "L4_EVAL_ON")
    private val enabled: Boolean get() = flag.isFile

    private val panelWidth = 900
    private val panelGap = 8
    private val labelHeight = 48
    private val zoomWidth = 900
    private val zoomHeight = 280

    @Test
    fun `owner photos side by side`() {
        if (!enabled) {
            println("EVAL SKIPPED: no ${flag.path}")
            return
        }
        val out = File(owner, "out/L4enh").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val crops = readCrops()

        val photos = buildList {
            File(owner, "camera").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg") }
                ?.sortedBy { it.name }?.let { addAll(it) }
            listOf(
                "1(1).jpg", "1.jpg", "images.jpg",
                "Aadhaar-card-pdf-1-2048.jpg.webp", "signature-before.webp"
            ).forEach { name ->
                File(owner, "downloads/$name").takeIf { it.isFile }?.let { add(it) }
            }
        }
        assertTrue("the owner photo set must not be empty", photos.isNotEmpty())

        val notes = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        var previews = 0
        for (photo in photos) {
            val decoded = try {
                ImageTools.decodeSampledFile(photo, 2200)
            } catch (_: Exception) {
                notes.append("unreadable: ${photo.name}\n")
                continue
            }
            val box = crops[photo.name]
            val crop = cropToPage(decoded, box)
            if (crop !== decoded) decoded.recycle()

            lateinit var auto: Bitmap
            val tAuto = measureNanoTime { auto = ScanEnhance.auto(crop) }
            lateinit var colour: Bitmap
            val tColour = measureNanoTime { colour = ScanEnhance.colourBoost(crop) }
            lateinit var bw: Bitmap
            val tBw = measureNanoTime { bw = ScanEnhance.blackWhite(crop) }

            val base = photo.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80)
            val compare = File(out, "${base}_compare.jpg")
            writeCompare(compare, listOf(crop, auto, colour, bw))
            val zoom = File(out, "${base}_zoom.jpg")
            writeZoom(zoom, listOf(crop, auto, colour, bw))
            previews += 2

            notes.append("${photo.name} -> ${compare.name}, ${zoom.name}\n")
            notes.append("  crop ${crop.width}x${crop.height}  auto=${tAuto / 1_000_000}ms " +
                "colour=${tColour / 1_000_000}ms bw=${tBw / 1_000_000}ms\n")
            notes.append("  written ${stamp.format(Date(compare.lastModified()))} UTC\n")

            crop.recycle()
            auto.recycle()
            colour.recycle()
            bw.recycle()
        }
        File(out, "notes.txt").writeText(notes.toString())
        println("EVAL RAN: $previews images -> ${out.absolutePath}")
        assertTrue("previews must be written", previews > 0)
    }

    /** Host speed at the filter cap (long side 3000) on one real 12 MP photo. */
    @Test
    fun `owner photo speed at the sampled limit`() {
        if (!enabled) {
            println("EVAL SKIPPED: no ${flag.path} (speed)")
            return
        }
        val out = File(owner, "out/L4enh").apply { mkdirs() }
        val photo = File(owner, "camera").listFiles { f ->
            f.isFile && f.extension.lowercase() == "jpg"
        }?.sortedBy { it.name }?.firstOrNull() ?: return
        val src = ImageTools.decodeSampledFile(photo, ScanEnhance.MAX_DIM)
        lateinit var auto: Bitmap
        lateinit var colour: Bitmap
        lateinit var bw: Bitmap
        val tAuto = measureNanoTime { auto = ScanEnhance.auto(src) }
        val tColour = measureNanoTime { colour = ScanEnhance.colourBoost(src) }
        val tBw = measureNanoTime { bw = ScanEnhance.blackWhite(src) }
        val width = src.width
        val height = src.height
        auto.recycle(); colour.recycle(); bw.recycle(); src.recycle()
        val line = "speed ${width}x${height}: auto=${tAuto / 1_000_000}ms " +
            "colour=${tColour / 1_000_000}ms bw=${tBw / 1_000_000}ms"
        File(out, "speed.txt").writeText(line + "\n")
        println("EVAL RAN: $line -> ${File(out, "speed.txt").absolutePath}")
    }

    // ------------------------------------------------------------------

    private fun readCrops(): Map<String, FloatArray> {
        val file = File(owner, "enh_crops.txt")
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, FloatArray>()
        file.readLines().forEach { line ->
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) return@forEach
            val parts = clean.split(Regex("\\s+"))
            if (parts.size == 5) {
                val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
                if (values.size == 4) out[parts[0]] = values.toFloatArray()
            }
        }
        return out
    }

    private fun cropToPage(src: Bitmap, box: FloatArray?): Bitmap {
        if (box == null) return src
        val left = (box[0] * src.width).toInt().coerceIn(0, src.width - 1)
        val top = (box[1] * src.height).toInt().coerceIn(0, src.height - 1)
        val right = (box[2] * src.width).toInt().coerceIn(left + 1, src.width)
        val bottom = (box[3] * src.height).toInt().coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    private fun writeCompare(target: File, panels: List<Bitmap>) {
        val panelH = max(1, panels[0].height * panelWidth / panels[0].width)
        val sheetW = panelWidth * panels.size + panelGap * (panels.size + 1)
        val sheetH = labelHeight + panelH + panelGap
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(10, 15, 19))
        val text = labelPaint()
        val labels = listOf("Original", "Auto", "Colour boost", "B&W")
        panels.forEachIndexed { i, bmp ->
            val left = panelGap + i * (panelWidth + panelGap)
            canvas.drawBitmap(bmp, null, Rect(left, labelHeight, left + panelWidth, labelHeight + panelH), null)
            canvas.drawText(labels[i], left + panelWidth / 2f, 32f, text)
        }
        target.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        sheet.recycle()
    }

    private fun writeZoom(target: File, panels: List<Bitmap>) {
        val src = panels[0]
        val w = min(zoomWidth, src.width)
        val h = min(zoomHeight, src.height)
        val x0 = ((src.width - w) / 2).coerceAtLeast(0)
        val y0 = ((src.height * 0.40f).toInt() - h / 2).coerceIn(0, max(0, src.height - h))
        val sheetW = zoomWidth * panels.size + panelGap * (panels.size + 1)
        val sheetH = labelHeight + zoomHeight + panelGap
        val sheet = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(10, 15, 19))
        val text = labelPaint()
        val labels = listOf("Original 1:1", "Auto 1:1", "Colour boost 1:1", "B&W 1:1")
        panels.forEachIndexed { i, bmp ->
            val left = panelGap + i * (zoomWidth + panelGap)
            // 1:1 for photos larger than the strip; small source images are scaled up to 900 px.
            val sx = (x0.toFloat() * bmp.width / src.width).toInt().coerceIn(0, max(0, bmp.width - w))
            val sy = (y0.toFloat() * bmp.height / src.height).toInt().coerceIn(0, max(0, bmp.height - h))
            canvas.drawBitmap(
                bmp, Rect(sx, sy, sx + w, sy + h),
                Rect(left, labelHeight, left + zoomWidth, labelHeight + zoomHeight), null
            )
            canvas.drawText(labels[i], left + zoomWidth / 2f, 32f, text)
        }
        target.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        sheet.recycle()
    }

    private fun labelPaint(): Paint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
}
