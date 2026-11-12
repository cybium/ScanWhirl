package com.scanwhirl.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * Retrieval slice 5: a ScrollView that never grows past [maxFraction] of the
 * screen height, so a docked panel at 200% font scrolls instead of pushing
 * the content above it (the PDF pages) off screen.
 */
class CappedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var maxFraction = 0.45f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * maxFraction).toInt().coerceAtLeast(1)
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = when (mode) {
            View.MeasureSpec.UNSPECIFIED -> View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(minOf(size, cap), mode)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}
