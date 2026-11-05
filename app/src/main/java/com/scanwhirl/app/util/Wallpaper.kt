package com.scanwhirl.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Gravity

/** Loads the optional user wallpaper as a screen-sized center-crop drawable. */
object Wallpaper {
    fun drawable(context: Context): Drawable? {
        if (!Prefs.showWallpaper(context)) return null
        val raw = Prefs.wallpaperUri(context)
        if (raw.isBlank()) return null
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(raw))
                ?.use { it.readBytes() } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                sample *= 2
            }
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            cropToScreen(context, bmp)
        } catch (_: Exception) {
            null
        }
    }

    /** Center-crops to the screen aspect so the background fills without distortion. */
    private fun cropToScreen(context: Context, bmp: Bitmap): Drawable {
        val metrics = context.resources.displayMetrics
        val targetRatio = metrics.widthPixels.toFloat() / metrics.heightPixels
        val bw = bmp.width
        val bh = bmp.height
        val cropW: Int
        val cropH: Int
        if (bw.toFloat() / bh > targetRatio) {
            cropH = bh
            cropW = (bh * targetRatio).toInt().coerceAtLeast(1)
        } else {
            cropW = bw
            cropH = (bw / targetRatio).toInt().coerceAtLeast(1)
        }
        val x = ((bw - cropW) / 2).coerceAtLeast(0)
        val y = ((bh - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bmp, x, y, cropW, cropH)
        if (cropped !== bmp) bmp.recycle()
        return BitmapDrawable(context.resources, cropped).apply {
            gravity = Gravity.FILL
        }
    }
}
