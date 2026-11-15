package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R

/**
 * v2 UI: 74_motion / 05_protect_pdf — the kit progress sheet.
 *
 * Replaces every spinner AlertDialog: a turning whirl mark, a determinate bar
 * when the caller knows the page count ("Page k of N"), an optional cancel and
 * the same ink sheet look as [SwSheet]. Animations honour the animator scale.
 */
class SwProgress private constructor(
    private val context: Context,
    private val dialog: BottomSheetDialog,
    private val cancel: (() -> Unit)?
) {

    val view: View
    private val bar: ProgressBar
    private val label: TextView
    private val whirl: ImageView

    init {
        val d = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            id = R.id.swProgressRoot
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * d).toInt(), (22 * d).toInt(), (24 * d).toInt(), (22 * d).toInt())
            background = SwStyle.rounded(
                context,
                0xFF161D21.toInt(),
                radiusDp = 28f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair_strong)
            )
        }
        whirl = ImageView(context).apply {
            id = R.id.swProgressWhirl
            setImageResource(R.drawable.ic_brand_mark)
        }
        root.addView(whirl, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        if (SwMotion.enabled(context)) {
            android.animation.ObjectAnimator.ofFloat(whirl, View.ROTATION, 0f, 360f).apply {
                duration = 1400L
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }.start()
        }

        label = SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.swProgressLabel
            gravity = Gravity.CENTER
        }
        root.addView(
            label,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (14 * d).toInt() }
        )

        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.swProgressBar
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.cyan_primary)
            )
            progressTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.cyan_primary)
            )
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.glass_surface_raised)
            )
        }
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * d).toInt())
                .apply { topMargin = (14 * d).toInt() }
        )

        val cancelButton = SwStyle.text(
            context, 14f, SwStyle.color(context, R.color.cyan_primary), bold = true
        ).apply {
            id = R.id.swProgressCancel
            text = context.getString(R.string.sheet_cancel)
            gravity = Gravity.CENTER
            minimumHeight = (44 * d).toInt()
            isVisible = false
            setOnClickListener {
                dismiss()
                cancel?.invoke()
            }
        }
        root.addView(
            cancelButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (10 * d).toInt() }
        )
        if (cancel != null) cancelButton.visibility = View.VISIBLE

        view = root
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setDimAmount(0.55f)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.34f).toInt()
    }

    /** "Page k of N" + a determinate bar; call again for every page. */
    fun setProgress(done: Int, total: Int, labelText: CharSequence? = null): SwProgress = apply {
        bar.isIndeterminate = false
        bar.max = total.coerceAtLeast(1)
        bar.progress = done.coerceIn(0, total)
        label.text = labelText ?: context.getString(R.string.progress_page_of, done, total)
    }

    fun setMessage(text: CharSequence): SwProgress = apply { label.text = text }

    fun show(): SwProgress = apply {
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    val isShowing: Boolean get() = dialog.isShowing

    companion object {
        /**
         * @param total known page count -> determinate bar and "Page k of N";
         *              null keeps the whirl indeterminate.
         * @param onCancel shows Cancel and runs it after closing the sheet.
         */
        fun show(
            context: Context,
            title: CharSequence,
            total: Int? = null,
            onCancel: (() -> Unit)? = null
        ): SwProgress {
            val progress = SwProgress(context, BottomSheetDialog(context), onCancel)
            progress.label.text = title
            if (total != null) progress.setProgress(0, total, title)
            return progress.show()
        }
    }
}
