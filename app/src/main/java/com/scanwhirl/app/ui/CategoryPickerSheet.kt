package com.scanwhirl.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle

/**
 * T4.2: the category picker sheet — five coloured rows, plus **Unsorted**
 * when that is the value being changed from. One entry point, [show];
 * T4.3 (Home chip / long-press Move / bulk Move), T4.4 (Quick Sort) and
 * T4.6 (document chip) all drive it.
 *
 * Rows are built in code because the Unsorted row is conditional; the row's
 * `tag` is the stored category value, which is also what [onPick] reports.
 */
object CategoryPickerSheet {

    fun show(activity: Activity, current: String?, onPick: (String) -> Unit) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_category_picker, null)
        sheet.setContentView(view)
        val rows = view.findViewById<LinearLayout>(R.id.pickerRows)

        // Unsorted first when present (same order as Home's filter rail).
        val entries = buildList {
            if (current == CategoryRules.UNSORTED) add(CategoryRules.UNSORTED)
            addAll(CategoryRules.ALL)
        }
        for (cat in entries) {
            rows.addView(row(activity, sheet, cat, current, onPick))
        }
        sheet.show()
    }

    private fun row(
        activity: Activity,
        sheet: BottomSheetDialog,
        cat: String,
        current: String?,
        onPick: (String) -> Unit
    ): View {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val color = CategoryStyle.color(cat)

        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(12), dp(12))
                setColor(color)
            }
        }

        val label = TextView(activity).apply {
            text = CategoryRules.label(cat)
            textSize = 15f
            setTextColor(activity.getColor(R.color.text_hologram_hi))
            letterSpacing = com.scanwhirl.app.ui.kit.SwStyle.tracking(activity, 0.02f)
        }

        val check = ImageView(activity).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(color)
            visibility = if (cat == current) View.VISIBLE else View.GONE
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = cat
            minimumHeight = dp(56)
            setPaddingRelative(dp(12), 0, dp(12), 0)
            val attrs = activity.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            )
            background = attrs.getDrawable(0)
            attrs.recycle()

            addView(
                dot,
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) }
            )
            addView(
                label,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(check, LinearLayout.LayoutParams(dp(20), dp(20)))

            setOnClickListener {
                onPick(cat)
                sheet.dismiss()
            }
        }
    }
}
