package com.scanwhirl.app.ui.pagegrid

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Gives the page-grid cell a fixed 3:4 (portrait) box, whatever the page's own
 * aspect ratio is. Without this, a tall cropped page makes its row taller than
 * its neighbours and leaves blank bands; here every cell is identical on any
 * screen size and the thumbnail is cropped to fill it (`centerCrop`).
 */
class AspectRatioLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Prefer the exact width from the grid span; measuredWidth may be a
        // child's intrinsic width when the parent measures with AT_MOST.
        val width = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(widthMeasureSpec)
            else -> measuredWidth
        }
        if (width <= 0) return
        val height = (width * 4f / 3f).toInt() // 3:4 cell
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
    }
}
