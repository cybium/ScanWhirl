package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min

/**
 * Printable page sizes for tools that compose content onto a sheet (ID card now; anything that
 * later places a photo on paper can reuse this). 150 dpi output.
 *
 * The default follows the device locale: US Letter for US, CA, MX, PH, CL, CO, VE; A4 otherwise.
 */
enum class PaperSize(val id: String, val width: Int, val height: Int) {
    A4("a4", 1240, 1754),        // 210 × 297 mm at 150 dpi
    LETTER("letter", 1275, 1650); // 8.5 × 11 in at 150 dpi

    companion object {
        private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE")

        fun fromId(id: String?): PaperSize = entries.firstOrNull { it.id == id } ?: A4

        /** The default for a device country (ISO 3166-1 alpha-2, any case). */
        fun defaultForCountry(country: String?): PaperSize {
            val code = country?.uppercase(Locale.ROOT).orEmpty()
            return if (code in LETTER_COUNTRIES) LETTER else A4
        }

        fun deviceDefault(): PaperSize =
            defaultForCountry(Locale.getDefault().country)
    }
}

/**
 * ID card tool: puts one or two captures onto a page with the real ID-1 card proportions
 * (85.6 × 54 mm), centred and never stretched. The page is A4 by default or US Letter.
 *
 * - [compose] with both sides: one page, front on top, back at the bottom, a small gap.
 * - [compose] with no back: one page with the front in the top slot (front-only cards).
 * - [composeSingle]: one page per side, the card centred vertically.
 *
 * Each capture is decoded sampled (a 12 MP phone photo never becomes a full-resolution bitmap
 * here) and fitted inside the card rectangle, aspect preserved. The card box is 86% of the page
 * width, i.e. a print-safe margin on every side.
 */
object IdCard {

    /** A4 at 150 dpi, kept for existing callers/tests. */
    const val WIDTH = 1240
    const val HEIGHT = 1754

    /** ISO/IEC 7810 ID-1: 85.6 mm × 54 mm. */
    const val ID1_RATIO = 85.6f / 54f

    /** A4 card box, kept for existing callers/tests. */
    val CARD_WIDTH = (WIDTH * 0.86f).toInt()
    val CARD_HEIGHT = (CARD_WIDTH / ID1_RATIO).toInt()

    /** Small white gap between the two card boxes on the shared page. */
    const val GAP = 24

    private const val MAX_DECODE = 1600
    private const val QUALITY = 92
    private const val CARD_WIDTH_FRACTION = 0.86f
    /** Print density of the composed page, matching the PDF generator's 150 dpi pages. */
    private const val PAGE_DPI = 150

    /** One page: front on top and, when [back] is given, the back below it. */
    fun compose(
        front: ByteArray,
        back: ByteArray? = null,
        paper: PaperSize = PaperSize.A4
    ): ByteArray {
        require(front.isNotEmpty()) { "Both sides of the card are needed" }
        require(back == null || back.isNotEmpty()) { "The back side is empty" }
        val page = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawIntoCard(canvas, front, frontRect(paper))
            back?.let { drawIntoCard(canvas, it, backRect(paper)) }
            return ImageTools.patchJpegDpi(ImageTools.encodeJpeg(page, QUALITY), PAGE_DPI)
        } finally {
            page.recycle()
        }
    }

    /** One page with a single card side centred vertically. */
    fun composeSingle(side: ByteArray, paper: PaperSize = PaperSize.A4): ByteArray {
        require(side.isNotEmpty()) { "The card side is empty" }
        val page = Bitmap.createBitmap(paper.width, paper.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawIntoCard(canvas, side, singleRect(paper))
            return ImageTools.patchJpegDpi(ImageTools.encodeJpeg(page, QUALITY), PAGE_DPI)
        } finally {
            page.recycle()
        }
    }

    fun cardWidth(paper: PaperSize): Int = (paper.width * CARD_WIDTH_FRACTION).toInt()

    fun cardHeight(paper: PaperSize): Int = (cardWidth(paper) / ID1_RATIO).toInt()

    /** Front card box on the shared page. */
    fun frontRect(paper: PaperSize = PaperSize.A4): RectF {
        val cardW = cardWidth(paper)
        val cardH = cardHeight(paper)
        val left = (paper.width - cardW) / 2f
        val total = cardH * 2 + GAP
        val top = (paper.height - total) / 2f
        return RectF(left, top, left + cardW, top + cardH)
    }

    /** Back card box on the shared page. */
    fun backRect(paper: PaperSize = PaperSize.A4): RectF {
        val front = frontRect(paper)
        return RectF(front.left, front.bottom + GAP, front.right, front.bottom + GAP + front.height())
    }

    /** The single card box on a one-side page. */
    fun singleRect(paper: PaperSize = PaperSize.A4): RectF {
        val cardW = cardWidth(paper)
        val cardH = cardHeight(paper)
        val left = (paper.width - cardW) / 2f
        val top = (paper.height - cardH) / 2f
        return RectF(left, top, left + cardW, top + cardH)
    }

    private fun drawIntoCard(canvas: Canvas, bytes: ByteArray, card: RectF) {
        val source = ImageTools.decodeSampled(bytes, MAX_DECODE)
        try {
            val scale = min(card.width() / source.width, card.height() / source.height)
            val w = (source.width * scale).coerceAtLeast(1f)
            val h = (source.height * scale).coerceAtLeast(1f)
            val left = card.centerX() - w / 2f
            val top = card.centerY() - h / 2f
            canvas.drawBitmap(
                source,
                null,
                RectF(left, top, left + w, top + h),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            source.recycle()
        }
    }
}
