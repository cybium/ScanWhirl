package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

/**
 * Scan Enhance 2.0 quality checks on synthetic pages: shadow flattening, text contrast, stamp hue,
 * B&W shadow cleanliness, idempotence, Grey/Colour behaviour and host speed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanEnhanceQualityTest {

    private fun shadowPaper(
        w: Int = 600,
        h: Int = 800,
        baseR: Int = 250,
        baseG: Int = 236,
        baseB: Int = 236
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Strong left-to-right shadow: 0.38 → 1.0, plus a slight vertical falloff.
                val sx = 0.38f + 0.62f * x / (w - 1)
                val sy = 0.94f + 0.06f * (1f - y.toFloat() / h)
                val sh = sx * sy
                val r = (baseR * sh).toInt().coerceIn(0, 255)
                val g = (baseG * sh).toInt().coerceIn(0, 255)
                val b = (baseB * sh).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun withText(base: Bitmap): Bitmap {
        val canvas = Canvas(base)
        val ink = Paint().apply { color = Color.rgb(20, 20, 20) }
        for (row in 0 until 12) {
            val y = 80f + row * 50f
            var x = 50f
            val widths = listOf(180f, 70f, 210f, 40f, 150f, 90f)
            widths.forEach { wd ->
                canvas.drawRect(x, y, x + wd, y + 14f, ink)
                x += wd + 26f
            }
        }
        return base
    }

    private fun withStamp(base: Bitmap): Pair<Bitmap, android.graphics.Rect> {
        val canvas = Canvas(base)
        val paint = Paint().apply { color = Color.rgb(214, 28, 44) }
        val cx = base.width * 0.72f
        val cy = base.height * 0.78f
        val r = base.width * 0.10f
        canvas.drawCircle(cx, cy, r, paint)
        return base to android.graphics.Rect(
            (cx - r).toInt(), (cy - r).toInt(), (cx + r).toInt(), (cy + r).toInt()
        )
    }

    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    private fun pixels(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also {
            bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        }

    private fun std(values: List<Int>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / max(1, values.size))
    }

    private fun mean(values: List<Int>): Double = values.average()

    private fun gradientSamples(bmp: Bitmap): List<Int> {
        val px = pixels(bmp)
        val out = ArrayList<Int>(140)
        // Rows 20..60 are above the text in the test page, so they are pure paper.
        for (y in 20 until 60 step 4) {
            for (x in 10 until bmp.width - 10 step bmp.width / 24) {
                out += luma(px[y * bmp.width + x])
            }
        }
        return out
    }

    @Test
    fun `auto flattens a shadow gradient by more than 70 percent`() {
        val src = shadowPaper()
        val before = std(gradientSamples(src))
        val out = ScanEnhance.auto(src)
        val after = std(gradientSamples(out))
        assertTrue("shadow std must drop >70%: before=$before after=$after", after < before * 0.30)
        assertTrue("paper must be near white: mean=${mean(gradientSamples(out))}", mean(gradientSamples(out)) > 235)
        src.recycle()
        out.recycle()
    }

    @Test
    fun `auto increases text contrast`() {
        val src = withText(shadowPaper())
        val px = pixels(src)
        val paper = (0 until 30).map { y -> luma(px[y * src.width + src.width / 2]) }
        val ink = (0 until 12).flatMap { row ->
            val y = (80 + row * 50 + 7)
            (0 until 6).map { col -> luma(px[y * src.width + 60 + col * 4]) }
        }
        val before = mean(paper) - mean(ink)
        val out = ScanEnhance.auto(src)
        val outPx = pixels(out)
        val paper2 = (0 until 30).map { y -> luma(outPx[y * out.width + out.width / 2]) }
        val ink2 = (0 until 12).flatMap { row ->
            val y = (80 + row * 50 + 7)
            (0 until 6).map { col -> luma(outPx[y * out.width + 60 + col * 4]) }
        }
        val after = mean(paper2) - mean(ink2)
        assertTrue("contrast must increase: before=$before after=$after", after > before * 1.10)
        src.recycle()
        out.recycle()
    }

    @Test
    fun `auto keeps a red stamp red`() {
        val (src, rect) = withStamp(shadowPaper())
        val px = pixels(src)
        val (hueBefore, satBefore) = stampHue(px, src.width, rect)
        val out = ScanEnhance.auto(src)
        val (hueAfter, satAfter) = stampHue(pixels(out), out.width, rect)
        val dh = abs(hueBefore - hueAfter).let { if (it > 180) 360 - it else it }
        assertTrue("stamp hue must survive Auto: before=$hueBefore after=$hueAfter", dh < 12)
        assertTrue("stamp colour must stay saturated: before=$satBefore after=$satAfter", satAfter > 0.35)
        src.recycle()
        out.recycle()
    }

    private fun stampHue(px: IntArray, w: Int, rect: android.graphics.Rect): Pair<Float, Float> {
        var hx = 0.0
        var hy = 0.0
        var satSum = 0.0
        var count = 0
        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                val c = px[y * w + x]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = if (mx == 0f) 0f else (mx - mn) / mx
                if (sat < 0.45f) continue
                val hue = when {
                    mx == r -> 60f * (((g - b) / (mx - mn)) % 6f)
                    mx == g -> 60f * (((b - r) / (mx - mn)) + 2f)
                    else -> 60f * (((r - g) / (mx - mn)) + 4f)
                }
                val rad = Math.toRadians(hue.toDouble())
                hx += kotlin.math.cos(rad)
                hy += kotlin.math.sin(rad)
                satSum += sat
                count++
            }
        }
        if (count == 0) return 0f to 0f
        var deg = Math.toDegrees(kotlin.math.atan2(hy, hx)).toFloat()
        if (deg < 0) deg += 360f
        return deg to (satSum / count).toFloat()
    }

    @Test
    fun `black and white has no black blobs in the shadow`() {
        val src = shadowPaper()
        val out = ScanEnhance.blackWhite(src)
        val px = pixels(out)
        val shadowWidth = out.width / 4
        var black = 0
        var total = 0
        for (y in 0 until out.height) {
            for (x in 0 until shadowWidth) {
                total++
                if (luma(px[y * out.width + x]) < 64) black++
            }
        }
        val fraction = black.toDouble() / total
        assertTrue("shadow must stay paper-white: black fraction=$fraction", fraction < 0.005)
        src.recycle()
        out.recycle()
    }

    @Test
    fun `black and white keeps text black`() {
        val src = withText(shadowPaper())
        val out = ScanEnhance.blackWhite(src)
        val px = pixels(out)
        var black = 0
        var total = 0
        for (row in 0 until 12) {
            val y = 80 + row * 50 + 7
            for (x in 50 until 250) {
                total++
                if (luma(px[y * out.width + x]) < 64) black++
            }
        }
        assertTrue("text must binarize to black: fraction=${black.toDouble() / total}", black > total / 2)
        src.recycle()
        out.recycle()
    }

    @Test
    fun `auto is idempotent enough`() {
        val src = withText(shadowPaper())
        val once = ScanEnhance.auto(src)
        val twice = ScanEnhance.auto(once)
        val a = pixels(once)
        val b = pixels(twice)
        var diff = 0L
        for (i in a.indices) diff += abs(luma(a[i]) - luma(b[i]))
        val avg = diff.toDouble() / a.size
        assertTrue("applying Auto twice must change little: avg=$avg", avg < 8.0)
        src.recycle()
        once.recycle()
        twice.recycle()
    }

    @Test
    fun `grey is neutral and colour boost raises saturation`() {
        val (src, rect) = withStamp(shadowPaper())
        val grey = ScanEnhance.grey(src)
        val gpx = pixels(grey)
        for (c in gpx) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertEquals("Grey must be neutral", r, g)
            assertEquals("Grey must be neutral", g, b)
        }
        val auto = ScanEnhance.auto(src)
        val boost = ScanEnhance.colourBoost(src)
        val satAuto = stampHue(pixels(auto), auto.width, rect).second
        val satBoost = stampHue(pixels(boost), boost.width, rect).second
        assertTrue(
            "colour boost must raise stamp saturation: auto=$satAuto boost=$satBoost",
            satBoost >= satAuto
        )
        src.recycle()
        grey.recycle()
        auto.recycle()
        boost.recycle()
    }

    @Test
    fun `black and white removes a thin slanted ruling line`() {
        val w = 700
        val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.rgb(70, 76, 82); strokeWidth = 2f }
        canvas.drawLine(40f, 300f, 660f, 330f, paint)
        canvas.drawLine(40f, 500f, 660f, 530f, paint)
        val out = ScanEnhance.blackWhite(bmp)
        val px = pixels(out)
        var black = 0
        for (y in 280 until 350) for (x in 40 until 660) if (luma(px[y * w + x]) < 64) black++
        assertTrue("slanted ruling must be whitened, black=$black", black < 50)
        bmp.recycle()
        out.recycle()
    }

    @Test
    fun `a twelve megapixel page processes in under one and a half seconds`() {
        // The app samples a 12 MP photo to MAX_DIM first; process that sampled page.
        val src = shadowPaper(w = 3000, h = 2250)
        // Warm the JIT once on a small copy.
        ScanEnhance.auto(shadowPaper(w = 300, h = 220)).recycle()
        val ns = measureNanoTime { ScanEnhance.auto(src).recycle() }
        val ms = ns / 1_000_000
        println("PERF scan-enhance auto 3000x2250: ${ms}ms")
        assertTrue("Auto must process a sampled 12 MP page in <1500 ms, was $ms ms", ms < 1500)
        src.recycle()
    }
}
