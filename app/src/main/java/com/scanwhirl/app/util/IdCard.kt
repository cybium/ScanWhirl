package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ID card tool: puts the front and the back capture of one card onto a single A4 page —
 * front in the top half, back in the bottom half, both centred, nothing stretched.
 *
 * A4 at 150 dpi is 1240 × 1754 px. Each capture is scaled to fit a box of 86% width and 40%
 * height, preserving its aspect ratio, and is decoded sampled so a 12 MP phone photo never
 * becomes a full-resolution bitmap here.
 */
object IdCard {

    const val WIDTH = 1240
    const val HEIGHT = 1754 // A4 at 150 dpi, height/width ≈ 1.414

    private const val MAX_DECODE = 1600
    private const val BOX_WIDTH = 0.86f
    private const val BOX_HEIGHT = 0.40f
    private const val QUALITY = 92

    /** @return a JPEG of one A4 page with the two captures. */
    fun compose(front: ByteArray, back: ByteArray): ByteArray {
        require(front.isNotEmpty() && back.isNotEmpty()) { "Both sides of the card are needed" }
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawHalf(canvas, front, top = true)
            drawHalf(canvas, back, top = false)
            return ImageTools.encodeJpeg(page, QUALITY)
        } finally {
            page.recycle()
        }
    }

    private fun drawHalf(canvas: Canvas, bytes: ByteArray, top: Boolean) {
        val source = ImageTools.decodeSampled(bytes, MAX_DECODE)
        try {
            val boxW = (WIDTH * BOX_WIDTH).roundToInt()
            val boxH = (HEIGHT * BOX_HEIGHT).roundToInt()
            val scale = min(boxW.toFloat() / source.width, boxH.toFloat() / source.height)
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            val left = (WIDTH - w) / 2
            val centerY = if (top) HEIGHT * 0.27f else HEIGHT * 0.73f
            val topY = (centerY - h / 2f).roundToInt()
            canvas.drawBitmap(
                source,
                null,
                Rect(left, topY, left + w, topY + h),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            source.recycle()
        }
    }
}
