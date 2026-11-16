package com.scanwhirl.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar

/**
 * Shared accessibility repairs for the two toolbars L5 owns (Text screen, Scan review).
 * Material's overflow button is 40dp wide and long titles are single-line ellipsized, both of
 * which fail the 48dp / no-clipping audit at font scale 2.0.
 */
internal object A11yTargets {

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    /**
     * Gives the toolbar's overflow button a 48dp touch target without enlarging its icon.
     * Call after the menu is inflated; the post runs once the ActionMenuView exists.
     */
    fun widenOverflow(toolbar: Toolbar) {
        toolbar.post {
            fun visit(view: View) {
                if (view is ActionMenuView) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        if (child is android.widget.ImageView) {
                            val pad = dp(toolbar, 12)
                            child.minimumWidth = dp(toolbar, 48)
                            child.setPadding(pad, child.paddingTop, pad, child.paddingBottom)
                            child.layoutParams = child.layoutParams.apply { width = dp(toolbar, 48) }
                        }
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(toolbar)
        }
    }

    /**
     * Lets a long title/subtitle wrap to two lines instead of being ellipsized at 2.0.
     * [leaveId] names a custom child that owns its own line policy (the review title field).
     */
    fun stackToolbarText(toolbar: Toolbar, leaveId: Int = View.NO_ID) {
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(i) as? TextView ?: continue
                if (child.id == leaveId) continue
                child.maxLines = 3
                child.ellipsize = null
                child.isSingleLine = false
            }
        }
    }

    /** The review title field: 48dp high and able to wrap instead of clipping its hint. */
    fun fixTitleField(field: TextView) {
        field.minHeight = dp(field, 48)
        field.maxLines = 2
        field.isSingleLine = false
        field.ellipsize = null
        field.setPadding(
            field.paddingLeft,
            dp(field, 10),
            field.paddingRight,
            dp(field, 10)
        )
    }

    /**
     * A kit chip with a 48dp target and a label that wraps instead of clipping at
     * 2.0. Order matters: `isSingleLine = false` *resets* maxLines to unlimited, so
     * it must come first — otherwise a Hindi label used three lines in a row that
     * only has room for two (2026-11-16 layout audit, item 1).
     */
    fun fixChip(chip: com.scanwhirl.app.ui.kit.SwChip) {
        chip.minimumHeight = dp(chip, 48)
        chip.labelView.isSingleLine = false
        chip.labelView.maxLines = 2
        chip.labelView.ellipsize = android.text.TextUtils.TruncateAt.END
        chip.labelView.maxWidth = dp(chip, 150)
    }
}
