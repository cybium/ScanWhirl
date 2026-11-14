package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/**
 * ID card tool: puts one or two captures onto A4 pages with the real ID-1 card proportions
 * (85.6 × 54 mm), centred and never stretched.
 *
 * - [compose] with both sides: one A4 page, front on top, back at the bottom, a small gap.
 * - [compose] with no back: one A4 page with the front in the top slot (front-only cards).
 * - [composeSingle]: one A4 page per side, the card centred vertically.
 *
 * A4 at 150 dpi is 1240 × 1754 px. Each capture is decoded sampled (a 12 MP phone photo never
 * becomes a full-resolution bitmap here) and fitted inside the card rectangle, aspect preserved.
 */
object IdCard {

    const val WIDTH = 1240
    const val HEIGHT = 1754 // A4 at 150 dpi, height/width ≈ 1.414

    /** ISO/IEC 7810 ID-1: 85.6 mm × 54 mm. */
    const val ID1_RATIO = 85.6f / 54f

    /** The card box is 86% of the A4 width; its height follows the ID-1 ratio. */
    val CARD_WIDTH = (WIDTH * 0.86f).toInt()
    val CARD_HEIGHT = (CARD_WIDTH / ID1_RATIO).toInt()

    /** Small white gap between the two card boxes on the shared page. */
    const val GAP = 24

    private const val MAX_DECODE = 1600
    private const val QUALITY = 92

    /** One A4 page: front on top and, when [back] is given, the back below it. */
    fun compose(front: ByteArray, back: ByteArray? = null): ByteArray {
        require(front.isNotEmpty()) { "Both sides of the card are needed" }
        require(back == null || back.isNotEmpty()) { "The back side is empty" }
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawIntoCard(canvas, front, frontRect())
            back?.let { drawIntoCard(canvas, it, backRect()) }
            return ImageTools.encodeJpeg(page, QUALITY)
        } finally {
            page.recycle()
        }
    }

    /** One A4 page with a single card side centred vertically. */
    fun composeSingle(side: ByteArray): ByteArray {
        require(side.isNotEmpty()) { "The card side is empty" }
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawIntoCard(canvas, side, singleRect())
            return ImageTools.encodeJpeg(page, QUALITY)
        } finally {
            page.recycle()
        }
    }

    /** Front card box on the shared A4 page. */
    fun frontRect(): RectF {
        val left = (WIDTH - CARD_WIDTH) / 2f
        val total = CARD_HEIGHT * 2 + GAP
        val top = (HEIGHT - total) / 2f
        return RectF(left, top, left + CARD_WIDTH, top + CARD_HEIGHT)
    }

    /** Back card box on the shared A4 page. */
    fun backRect(): RectF {
        val front = frontRect()
        return RectF(front.left, front.bottom + GAP, front.right, front.bottom + GAP + CARD_HEIGHT)
    }

    /** The single card box on a one-side page. */
    fun singleRect(): RectF {
        val left = (WIDTH - CARD_WIDTH) / 2f
        val top = (HEIGHT - CARD_HEIGHT) / 2f
        return RectF(left, top, left + CARD_WIDTH, top + CARD_HEIGHT)
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
