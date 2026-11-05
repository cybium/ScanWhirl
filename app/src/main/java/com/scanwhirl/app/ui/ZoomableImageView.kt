package com.scanwhirl.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * Full-screen page preview: pinch to zoom, drag to pan, double-tap to toggle
 * 1x / 2.5x. Keeps the image covering the viewport like fitCenter at rest.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private var fitted = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > minScale * 1.15f) zoomTo(minScale, e.x, e.y)
                else zoomTo(minScale * 2.5f, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scale > minScale + 0.01f) {
                    tx -= distanceX
                    ty -= distanceY
                    constrain()
                    apply()
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitted = false
        fitToView()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        fitted = false
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        minScale = min(
            width.toFloat() / d.intrinsicWidth,
            height.toFloat() / d.intrinsicHeight
        )
        scale = minScale
        tx = (width - d.intrinsicWidth * scale) / 2f
        ty = (height - d.intrinsicHeight * scale) / 2f
        fitted = true
        apply()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val d = drawable ?: return
        if (!fitted) fitToView()
        if (!fitted) return
        val newScale = target.coerceIn(minScale, minScale * 10f)
        if (newScale <= 0f || scale <= 0f) return
        val factor = newScale / scale
        tx = fx - (fx - tx) * factor
        ty = fy - (fy - ty) * factor
        scale = newScale
        constrain()
        apply()
    }

    /** Keeps scale in range and the viewport covered (axis-centered when smaller). */
    private fun constrain() {
        val d = drawable ?: return
        scale = scale.coerceIn(minScale, minScale * 10f)
        val w = d.intrinsicWidth * scale
        val h = d.intrinsicHeight * scale
        tx = if (w <= width) (width - w) / 2f else tx.coerceIn(width - w, 0f)
        ty = if (h <= height) (height - h) / 2f else ty.coerceIn(height - h, 0f)
    }

    private fun apply() {
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(tx, ty)
        imageMatrix = drawMatrix
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }
}
