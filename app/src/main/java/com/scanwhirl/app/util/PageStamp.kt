package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * L5 Phase A — the stamp engine.
 *
 * It bakes vector marks (signature ink, text, freehand, highlight, watermark, page numbers,
 * badges and redaction boxes) into page pixels and writes the result with [PageReplace].
 * Marks are drawn in **page-normalised coordinates** (0..1), so a mark placed on a screen
 * preview lands in the same place at any working resolution.
 *
 * Memory rule: a page is decoded ONCE, sampled so its longest side is ≤ [MAX_DIM] (3000 px)
 * and its area ≤ [MAX_PIXELS]. Drawing happens on that single mutable bitmap; there is no
 * full-resolution second copy. The output page is the working size (the same bound
 * [ImageTools] uses for rotate and crop), and it is verified by [PageReplace.stageJpeg].
 *
 * Redaction is not a cover-up: boxes are painted last, opaque, into the pixels, and
 * [com.scanwhirl.app.store.ScanRepository.stampDocument] removes that page's OCR text and
 * kept `.orig` from the new copy. The source document is never written.
 */
object PageStamp {

    /** Longest side of a working page. */
    const val MAX_DIM = 3000

    /** Area cap keeps a 3000 × 3000 page under 24 MB of ARGB pixels. */
    const val MAX_PIXELS = 6_000_000L

    /** A point inside a [Box] or the page, 0..1. */
    data class Point(val x: Float, val y: Float)

    /** One pen stroke, as normalised points. */
    data class Stroke(val points: List<Point>)

    /** A rectangle in page-normalised coordinates. Corners are ordered and clamped when drawn. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    enum class Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    enum class TextAlign { LEFT, CENTER, RIGHT }

    /** Ready-made badges; the label is the text burned into the page. */
    enum class BadgeKind(val label: String, val ink: Int) {
        PAID("PAID", 0xFF1B7F4B.toInt()),
        APPROVED("APPROVED", 0xFF1B7F4B.toInt()),
        DRAFT("DRAFT", 0xFF8D979E.toInt()),
        RECEIVED("RECEIVED", 0xFF1565C0.toInt()),
        SELF_ATTESTED("SELF-ATTESTED", 0xFF6A1B9A.toInt())
    }

    /** Every mark the editor can place on a page. */
    sealed class Mark {
        /** Signature ink: points are normalised inside [box], and the whole mark may rotate. */
        data class Signature(
            val strokes: List<Stroke>,
            val box: Box,
            val color: Int = Color.BLACK,
            /** Stroke width as a fraction of the page's shorter side. */
            val width: Float = 0.0035f,
            val rotation: Float = 0f
        ) : Mark()

        /**
         * An imported bitmap signature or company stamp (a transparent PNG owned by the app),
         * placed and rotated like [Signature].
         */
        data class Picture(
            val file: File,
            val box: Box,
            val rotation: Float = 0f
        ) : Mark()

        /** One or more lines of text fitted inside [box]. */
        data class Text(
            val text: String,
            val box: Box,
            val color: Int = Color.BLACK,
            /** Font size as a fraction of the page's shorter side (fitted down to the box). */
            val size: Float = 0.05f,
            val bold: Boolean = false,
            val rotation: Float = 0f,
            val align: TextAlign = TextAlign.LEFT
        ) : Mark()

        /** Free drawing: points are normalised to the whole page. */
        data class Freehand(
            val strokes: List<Stroke>,
            val color: Int = Color.BLACK,
            val width: Float = 0.005f
        ) : Mark()

        /** Highlighter: multiply blend so the text underneath stays readable. */
        data class Highlight(
            val box: Box,
            val color: Int = 0xFFFFF176.toInt(),
            val opacity: Float = 0.45f
        ) : Mark()

        /** Diagonal repeating-purpose mark across the middle of the page. */
        data class Watermark(
            val text: String,
            val color: Int = Color.BLACK,
            val opacity: Float = 0.20f,
            val angle: Float = -40f,
            /** Font size as a fraction of the page's shorter side. */
            val size: Float = 0.13f
        ) : Mark()

        /** Page number in [format] (`%d` = number, `%d` again = page count). */
        data class PageNumber(
            val position: Position = Position.BOTTOM_CENTER,
            val format: String = "%d / %d",
            val startAt: Int = 1,
            val color: Int = Color.BLACK,
            val size: Float = 0.03f
        ) : Mark()

        /** A preset badge with a date. */
        data class Badge(
            val kind: BadgeKind,
            val box: Box,
            /** Null means today, formatted `dd MMM yyyy`. */
            val date: String? = null,
            val color: Int? = null
        ) : Mark()

        /** Solid, opaque box burned into the pixels; also triggers OCR and `.orig` removal. */
        data class Redact(
            val box: Box,
            val color: Int = Color.BLACK
        ) : Mark()
    }

    /** Marks for one page. */
    data class PageEdits(val pageIndex: Int, val marks: List<Mark>)

    /** Kinds of sensitive line the Redact sheet can suggest. */
    enum class SensitiveKind { AADHAAR, PAN, PHONE, LONG_NUMBER }

    /** A proposed redaction box (normalised) over one OCR line. */
    data class Suggestion(val box: Box, val kind: SensitiveKind, val text: String)

    private val AADHAAR = Regex("""(?<!\d)\d{4}[ -]?\d{4}[ -]?\d{4}(?!\d)""")
    private val PAN = Regex("""\b[A-Za-z]{5}\d{4}[A-Za-z]\b""")
    private val PHONE = Regex("""(?<!\d)[6-9](?:[ -]?\d){9}(?!\d)""")
    private val LONG_NUMBER = Regex("""(?<!\d)\d{9,}(?!\d)""")

    /**
     * "Suggest" for the Redact tool: one box per OCR line that contains an Aadhaar number, a PAN,
     * an Indian mobile number or a long digit run (account numbers). The whole line is boxed on
     * purpose — a box over only the characters the OCR placed would risk leaving digits outside —
     * and every box is clamped to the page. Nothing is redacted until the user accepts them.
     */
    fun suggestRedactions(page: com.scanwhirl.app.model.OcrPage): List<Suggestion> {
        if (page.width <= 0 || page.height <= 0) return emptyList()
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        val out = ArrayList<Suggestion>()
        for (line in page.lines) {
            val kind = when {
                AADHAAR.containsMatchIn(line.text) -> SensitiveKind.AADHAAR
                PAN.containsMatchIn(line.text) -> SensitiveKind.PAN
                PHONE.containsMatchIn(line.text) -> SensitiveKind.PHONE
                LONG_NUMBER.containsMatchIn(line.text) -> SensitiveKind.LONG_NUMBER
                else -> null
            } ?: continue
            val pad = max(1f, (line.bottom - line.top) * 0.10f)
            out += Suggestion(
                box = Box(
                    ((line.left - pad) / w).coerceIn(0f, 1f),
                    ((line.top - pad) / h).coerceIn(0f, 1f),
                    ((line.right + pad) / w).coerceIn(0f, 1f),
                    ((line.bottom + pad) / h).coerceIn(0f, 1f)
                ),
                kind = kind,
                text = line.text
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Draws [marks] onto [bitmap] (ARGB_8888, mutable). Redaction boxes are always drawn
     * last, so no other mark can be painted over them.
     */
    fun render(bitmap: Bitmap, marks: List<Mark>, pageNumber: Int, pageCount: Int) {
        require(!bitmap.isRecycled) { "The page is no longer in memory. Try again." }
        if (marks.isEmpty()) return
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val minDim = min(w, h)
        val redactions = ArrayList<Mark.Redact>()
        for (mark in marks) {
            when (mark) {
                is Mark.Redact -> redactions += mark
                is Mark.Signature -> drawInk(canvas, mark.strokes, rectOf(mark.box, w, h), mark.color, mark.width, minDim, mark.rotation)
                is Mark.Picture -> drawPicture(canvas, mark, w, h)
                is Mark.Freehand -> drawInk(canvas, mark.strokes, RectF(0f, 0f, w, h), mark.color, mark.width, minDim, 0f)
                is Mark.Text -> drawText(canvas, mark, w, h, minDim)
                is Mark.Highlight -> drawHighlight(canvas, mark, w, h)
                is Mark.Watermark -> drawWatermark(canvas, mark, w, h, minDim)
                is Mark.PageNumber -> drawPageNumber(canvas, mark, w, h, minDim, pageNumber, pageCount)
                is Mark.Badge -> drawBadge(canvas, mark, w, h, minDim)
            }
        }
        for (r in redactions) drawRedact(canvas, r, w, h)
    }

    private fun rectOf(box: Box, w: Float, h: Float): RectF {
        val l = min(box.left, box.right).coerceIn(0f, 1f) * w
        val r = max(box.left, box.right).coerceIn(0f, 1f) * w
        val t = min(box.top, box.bottom).coerceIn(0f, 1f) * h
        val b = max(box.top, box.bottom).coerceIn(0f, 1f) * h
        return RectF(l, t, max(r, l + 1f), max(b, t + 1f))
    }

    private fun rotate(canvas: Canvas, degrees: Float, rect: RectF) {
        if (degrees == 0f) return
        canvas.rotate(degrees, rect.centerX(), rect.centerY())
    }

    private fun drawInk(
        canvas: Canvas,
        strokes: List<Stroke>,
        rect: RectF,
        color: Int,
        widthFraction: Float,
        minDim: Float,
        rotation: Float
    ) {
        if (strokes.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = max(1f, minDim * widthFraction.coerceAtLeast(0.0002f))
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            fun at(p: Point) = PointF(
                rect.left + p.x.coerceIn(0f, 1f) * rect.width(),
                rect.top + p.y.coerceIn(0f, 1f) * rect.height()
            )
            val first = at(points.first())
            path.moveTo(first.x, first.y)
            if (points.size == 1) {
                path.lineTo(first.x + 0.01f, first.y)
                continue
            }
            for (i in 1 until points.size) {
                val p = at(points[i])
                if (i == points.size - 1) {
                    path.lineTo(p.x, p.y)
                } else {
                    val next = at(points[i + 1])
                    path.quadTo(p.x, p.y, (p.x + next.x) / 2f, (p.y + next.y) / 2f)
                }
            }
        }
        canvas.save()
        rotate(canvas, rotation, rect)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawPicture(canvas: Canvas, mark: Mark.Picture, w: Float, h: Float) {
        val bmp = try {
            BitmapFactory.decodeFile(mark.file.path)
        } catch (_: Throwable) {
            null
        } ?: return
        val rect = rectOf(mark.box, w, h)
        canvas.save()
        rotate(canvas, mark.rotation, rect)
        canvas.drawBitmap(bmp, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        bmp.recycle()
    }

    private fun textPaint(color: Int, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun drawText(canvas: Canvas, mark: Mark.Text, w: Float, h: Float, minDim: Float) {
        val lines = mark.text.split('\n')
        if (lines.all { it.isBlank() }) return
        val rect = rectOf(mark.box, w, h)
        val paint = textPaint(mark.color, mark.bold)
        var size = min(minDim * mark.size.coerceAtLeast(0.005f), rect.height() / lines.size * 0.85f)
        size = max(1f, size)
        paint.textSize = size
        val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        if (widest > rect.width() && widest > 0f) {
            paint.textSize = max(1f, size * rect.width() / widest)
        }
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.05f
        val blockTop = rect.top + max(0f, (rect.height() - lineHeight * lines.size) / 2f)
        canvas.save()
        rotate(canvas, mark.rotation, rect)
        lines.forEachIndexed { i, line ->
            val tw = paint.measureText(line)
            val x = when (mark.align) {
                TextAlign.LEFT -> rect.left
                TextAlign.CENTER -> rect.centerX() - tw / 2f
                TextAlign.RIGHT -> rect.right - tw
            }
            canvas.drawText(line, x, blockTop + i * lineHeight - fm.ascent, paint)
        }
        canvas.restore()
    }

    private fun drawHighlight(canvas: Canvas, mark: Mark.Highlight, w: Float, h: Float) {
        val paint = Paint().apply {
            color = mark.color
            alpha = (mark.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
        canvas.drawRect(rectOf(mark.box, w, h), paint)
    }

    private fun drawWatermark(canvas: Canvas, mark: Mark.Watermark, w: Float, h: Float, minDim: Float) {
        if (mark.text.isBlank()) return
        val paint = textPaint(mark.color, bold = true).apply {
            textSize = max(8f, minDim * mark.size.coerceIn(0.02f, 0.5f))
            textAlign = Paint.Align.CENTER
            alpha = (mark.opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        // Fit long text inside the page.
        val widest = paint.measureText(mark.text)
        val diagonal = sqrt(w * w + h * h)
        if (widest > diagonal * 0.92f && widest > 0f) {
            paint.textSize = max(8f, paint.textSize * diagonal * 0.92f / widest)
        }
        val fm = paint.fontMetrics
        canvas.save()
        canvas.rotate(mark.angle, w / 2f, h / 2f)
        canvas.drawText(mark.text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, paint)
        canvas.restore()
    }

    private fun drawPageNumber(
        canvas: Canvas,
        mark: Mark.PageNumber,
        w: Float,
        h: Float,
        minDim: Float,
        pageNumber: Int,
        pageCount: Int
    ) {
        val number = (mark.startAt + pageNumber - 1).coerceAtLeast(0)
        val label = try {
            String.format(Locale.ROOT, mark.format, number, pageCount)
        } catch (_: Exception) {
            "$number / $pageCount"
        }
        val paint = textPaint(mark.color, bold = false).apply {
            textSize = max(8f, minDim * mark.size.coerceAtLeast(0.008f))
        }
        val fm = paint.fontMetrics
        val margin = max(6f, minDim * 0.035f)
        val top = mark.position in setOf(Position.TOP_LEFT, Position.TOP_CENTER, Position.TOP_RIGHT)
        val x = when (mark.position) {
            Position.TOP_LEFT, Position.BOTTOM_LEFT -> { paint.textAlign = Paint.Align.LEFT; margin }
            Position.TOP_CENTER, Position.BOTTOM_CENTER -> { paint.textAlign = Paint.Align.CENTER; w / 2f }
            Position.TOP_RIGHT, Position.BOTTOM_RIGHT -> { paint.textAlign = Paint.Align.RIGHT; w - margin }
        }
        val baseline = if (top) margin - fm.ascent else h - margin - fm.descent
        canvas.drawText(label, x, baseline, paint)
    }

    private fun drawBadge(canvas: Canvas, mark: Mark.Badge, w: Float, h: Float, minDim: Float) {
        val rect = rectOf(mark.box, w, h)
        val ink = mark.color ?: mark.kind.ink
        val radius = min(rect.height() * 0.18f, minDim * 0.03f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            alpha = 36
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, minDim * 0.006f)
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)

        val labelPaint = textPaint(ink, bold = true).apply { textAlign = Paint.Align.CENTER }
        var labelSize = min(rect.height() * 0.40f, minDim * 0.08f)
        labelPaint.textSize = max(6f, labelSize)
        val widest = labelPaint.measureText(mark.kind.label)
        if (widest > rect.width() * 0.92f && widest > 0f) {
            labelPaint.textSize = max(6f, labelPaint.textSize * rect.width() * 0.92f / widest)
        }
        val date = mark.date ?: SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val datePaint = textPaint(ink, bold = false).apply {
            textAlign = Paint.Align.CENTER
            textSize = max(6f, labelPaint.textSize * 0.62f)
        }
        val centreY = rect.centerY()
        canvas.drawText(mark.kind.label, rect.centerX(), centreY - labelPaint.textSize * 0.10f, labelPaint)
        canvas.drawText(date, rect.centerX(), centreY + labelPaint.textSize * 0.95f, datePaint)
    }

    private fun drawRedact(canvas: Canvas, mark: Mark.Redact, w: Float, h: Float) {
        val rect = rectOf(mark.box, w, h)
        val paint = Paint().apply {
            color = mark.color or 0xFF000000.toInt() // redaction is always opaque
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        // Round outward so a box never leaves a one-pixel fringe of the original.
        canvas.drawRect(
            kotlin.math.floor(rect.left.toDouble()).toFloat(),
            kotlin.math.floor(rect.top.toDouble()).toFloat(),
            ceil(rect.right.toDouble()).toFloat(),
            ceil(rect.bottom.toDouble()).toFloat(),
            paint
        )
    }

    // ------------------------------------------------------------------
    // Page files
    // ------------------------------------------------------------------

    /**
     * Decodes [target] once (sampled ≤ [MAX_DIM], ≤ [MAX_PIXELS]), draws the marks and swaps
     * the new JPEG in through [PageReplace]. A redaction box is verified to be solid before the
     * swap and after decoding the encoded page; a failure leaves the target untouched.
     */
    fun applyPage(target: File, marks: List<Mark>, pageNumber: Int, pageCount: Int) {
        require(marks.isNotEmpty()) { "Add a mark first" }
        val work = decodeWorking(target)
        try {
            render(work, marks, pageNumber, pageCount)
            val redactions = marks.filterIsInstance<Mark.Redact>()
            if (redactions.isNotEmpty()) verifySolid(work, redactions)
            PageReplace.commit(PageReplace.stageJpeg(target, work, 95))
            if (redactions.isNotEmpty()) verifyEncoded(target, redactions)
        } finally {
            work.recycle()
        }
    }

    private fun decodeWorking(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "This page can't be read, so nothing was changed."
        }
        var sample = 1
        var w = bounds.outWidth.toLong()
        var h = bounds.outHeight.toLong()
        while (w > MAX_DIM || h > MAX_DIM || w * h > MAX_PIXELS) {
            if (w <= 1 && h <= 1) break
            sample *= 2
            w = max(1, w / 2)
            h = max(1, h / 2)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(file.path, options)
            ?: throw IllegalStateException("This page can't be read, so nothing was changed.")
        if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val mutable = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        return mutable ?: throw IllegalStateException("This page can't be edited in memory. Try again.")
    }

    /** Every pixel inside every box must already be the fill colour on the working bitmap. */
    private fun verifySolid(bitmap: Bitmap, redactions: List<Mark.Redact>) {
        for (r in redactions) {
            val rect = rectOf(r.box, bitmap.width.toFloat(), bitmap.height.toFloat())
            val fill = r.color or 0xFF000000.toInt()
            val left = ceil(rect.left.toDouble()).toInt().coerceIn(0, bitmap.width - 1)
            val top = ceil(rect.top.toDouble()).toInt().coerceIn(0, bitmap.height - 1)
            val right = ceil(rect.right.toDouble()).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = ceil(rect.bottom.toDouble()).toInt().coerceIn(top + 1, bitmap.height)
            for (y in top until bottom) {
                for (x in left until right) {
                    if (bitmap.getPixel(x, y) != fill) {
                        throw IllegalStateException(
                            "A redaction box was not painted solid. The copy was removed."
                        )
                    }
                }
            }
        }
    }

    /**
     * A bounded re-read of the encoded page proves the boxes survived JPEG encoding. Samples a
     * grid inside each box (inset so border ringing is not measured); the output keeps ±24 per
     * channel, well inside the test tolerance.
     */
    private fun verifyEncoded(target: File, redactions: List<Mark.Redact>) {
        val bmp = decodeWorking(target)
        try {
            for (r in redactions) {
                val rect = rectOf(r.box, bmp.width.toFloat(), bmp.height.toFloat())
                val fill = r.color or 0xFF000000.toInt()
                val left = ceil(rect.left.toDouble()).toInt() + 2
                val top = ceil(rect.top.toDouble()).toInt() + 2
                val right = rect.right.toInt() - 2
                val bottom = rect.bottom.toInt() - 2
                if (right <= left || bottom <= top) continue
                val area = (right - left).toLong() * (bottom - top)
                val step = max(1, sqrt(area / 20_000.0).toInt())
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        val c = bmp.getPixel(x, y)
                        val ok = kotlin.math.abs(Color.red(c) - Color.red(fill)) <= 24 &&
                            kotlin.math.abs(Color.green(c) - Color.green(fill)) <= 24 &&
                            kotlin.math.abs(Color.blue(c) - Color.blue(fill)) <= 24
                        if (!ok) {
                            throw IllegalStateException(
                                "A redaction box did not read back as solid ink. The copy was removed."
                            )
                        }
                        x += step
                    }
                    y += step
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** `Doc (redacted)`, `Doc (watermarked)`, `Doc (signed)` or `Doc (edited)`. */
    fun outputName(sourceName: String, marks: List<Mark>): String {
        val suffix = when {
            marks.any { it is Mark.Redact } -> "redacted"
            marks.any { it is Mark.Watermark } -> "watermarked"
            marks.any { it is Mark.Signature || it is Mark.Picture } -> "signed"
            else -> "edited"
        }
        return "$sourceName ($suffix)"
    }
}
