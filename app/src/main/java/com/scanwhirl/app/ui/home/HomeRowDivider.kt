package com.scanwhirl.app.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R

/**
 * Quiet hairline between Home rows, inset to the text column so the paper
 * thumbnails read as one column of documents. Drawn, not laid out: no extra
 * views per row and no change to row height or accessibility order.
 */
class HomeRowDivider(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private val inset = 96 * density
    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.rv_hairline)
        strokeWidth = maxOf(1f, density * 0.75f)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val right = parent.width - parent.paddingRight - 16 * density
        for (i in 0 until parent.childCount - 1) {
            val child = parent.getChildAt(i)
            val y = child.bottom + child.translationY
            c.drawLine(parent.paddingLeft + inset, y, right, y, paint)
        }
    }
}
