package com.scanwhirl.app.util

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * FX2: geometry for the form photo crop.
 *
 * Every crop used for a real output is expressed in the EXIF-normalised source space and has the
 * EXACT target aspect ratio (width / height) as integer pixels. The encoder then applies ONE
 * uniform scale, so a face or a signature stroke can never be stretched by independent x/y
 * scaling. These functions are pure; the CropView and FormReadyActivity share them so the frame a
 * person sees is the frame that is saved.
 */
object FormCrop {

    /**
     * The largest rect of [aspect] centred on [focus] and contained in the image. Used for the
     * default frame: no face/model dependency, just the whole upright image (or, for a signature,
     * the detected ink box).
     */
    fun largestCentered(imageW: Int, imageH: Int, aspect: Float, focus: RectF? = null): RectF {
        if (imageW <= 0 || imageH <= 0 || aspect <= 0f || !aspect.isFinite()) return RectF()
        val cx = if (focus == null || focus.isEmpty) imageW / 2f else focus.centerX()
        val cy = if (focus == null || focus.isEmpty) imageH / 2f else focus.centerY()
        var w = min(imageW.toFloat(), imageH.toFloat() * aspect)
        var h = w / aspect
        if (h > imageH) {
            h = imageH.toFloat()
            w = h * aspect
        }
        if (w > imageW) {
            w = imageW.toFloat()
            h = w / aspect
        }
        val left = (cx - w / 2f).coerceIn(0f, imageW - w)
        val top = (cy - h / 2f).coerceIn(0f, imageH - h)
        return RectF(left, top, left + w, top + h)
    }

    /**
     * Snaps any user frame to an exact-target-aspect integer rect in source pixels, centred on the
     * frame the person confirmed and clamped inside the image. This is the single place where a
     * float drag rectangle becomes the rectangle actually extracted from the bitmap.
     */
    fun snapToAspect(frame: RectF, aspect: Float, imageW: Int, imageH: Int): Rect {
        if (aspect <= 0f || !aspect.isFinite() || imageW <= 0 || imageH <= 0) return Rect()
        val focusX = if (frame.isEmpty) imageW / 2f else
            frame.centerX().coerceIn(0f, imageW.toFloat())
        val focusY = if (frame.isEmpty) imageH / 2f else
            frame.centerY().coerceIn(0f, imageH.toFloat())
        val maxW = min(imageW.toFloat(), imageH.toFloat() * aspect)
        var w = if (frame.isEmpty) maxW else min(maxW, max(1f, frame.width()))
        var h = w / aspect
        if (h > imageH) {
            h = imageH.toFloat()
            w = h * aspect
        }
        var iw = max(1, floor(w).toInt())
        var ih = max(1, round(iw / aspect).toInt())
        if (ih > imageH) {
            ih = imageH
            iw = max(1, floor(ih * aspect).toInt())
        }
        if (iw > imageW) {
            iw = imageW
            ih = max(1, round(iw / aspect).toInt())
        }
        val left = round(focusX - iw / 2f).toInt().coerceIn(0, imageW - iw)
        val top = round(focusY - ih / 2f).toInt().coerceIn(0, imageH - ih)
        return Rect(left, top, left + iw, top + ih)
    }

    /** One uniform scale; callers centre the scaled bitmap so any rounding is letterboxing only. */
    fun uniformScale(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Float {
        require(srcW > 0 && srcH > 0 && targetW > 0 && targetH > 0)
        return min(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
    }

    /** Aspect error of an integer rect against the target; used by the numeric gates. */
    fun aspectError(rect: Rect, aspect: Float): Float {
        if (rect.height() <= 0 || aspect <= 0f) return 1f
        return kotlin.math.abs(rect.width().toFloat() / rect.height() / aspect - 1f)
    }
}
