package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R

/**
 * v2 UI: 05_protect_pdf / 09_unlock_sheet — the one bottom sheet in the app.
 *
 * Grab handle, title, close button, free content, one full-width primary
 * button and an optional quiet secondary. Every `AlertDialog` in v2 becomes a
 * SwSheet, so the kit owns the layout and tests find it by view id.
 */
class SwSheet private constructor(
    context: Context,
    private val dialog: BottomSheetDialog
) {

    /** The sheet's content root (ids: swSheetTitle, swSheetMessage, …). */
    val view: View

    val titleView: TextView
    val messageView: TextView
    val closeButton: ImageButton
    val contentContainer: LinearLayout
    val primaryButton: TextView
    val secondaryButton: TextView

    init {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()

        val root = LinearLayout(context).apply {
            id = R.id.swSheetRoot
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (10 * density).toInt(), padH, (22 * density).toInt())
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface),
                radiusDp = 28f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair_strong)
            )
        }

        val grab = View(context).apply {
            id = R.id.swSheetGrab
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.sw_sheet_grab), radiusDp = 999f
            )
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (5 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * density).toInt()
            }
        }
        root.addView(grab)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = SwStyle.text(context, 21f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.swSheetTitle
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        closeButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swSheetClose
            contentDescription = context.getString(R.string.sheet_close)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeButton)
        root.addView(header)

        messageView = SwStyle.text(context, 14f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
            id = R.id.swSheetMessage
            setLineSpacing(0f, 1.25f)
            isVisible = false
        }
        root.addView(
            messageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )

        contentContainer = LinearLayout(context).apply {
            id = R.id.swSheetContent
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        root.addView(contentContainer)

        primaryButton = TextView(context).apply {
            id = R.id.swSheetPrimary
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(SwStyle.color(context, R.color.sw_disc_ink))
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            minHeight = (54 * density).toInt()
            isVisible = false
        }
        root.addView(
            primaryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (54 * density).toInt()
            ).apply { topMargin = (16 * density).toInt() }
        )

        secondaryButton = TextView(context).apply {
            id = R.id.swSheetSecondary
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(SwStyle.color(context, R.color.text_hologram_mid))
            minHeight = (44 * density).toInt()
            isVisible = false
        }
        root.addView(
            secondaryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )

        view = root
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.66f)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }

    fun setTitle(text: CharSequence): SwSheet = apply { titleView.text = text }
    fun setTitle(res: Int): SwSheet = setTitle(view.context.getString(res))

    fun setMessage(text: CharSequence): SwSheet = apply {
        messageView.text = text
        messageView.isVisible = true
    }

    fun setMessage(res: Int): SwSheet = setMessage(view.context.getString(res))

    fun setContent(content: View): SwSheet = apply {
        contentContainer.removeAllViews()
        contentContainer.addView(content)
        contentContainer.isVisible = true
    }

    fun setPrimary(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet = apply {
        primaryButton.text = text
        primaryButton.isVisible = true
        primaryButton.setOnClickListener {
            onClick?.invoke()
            dialog.dismiss()
        }
    }

    fun setPrimary(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setPrimary(view.context.getString(res), onClick)

    fun setSecondary(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet = apply {
        secondaryButton.text = text
        secondaryButton.isVisible = true
        secondaryButton.setOnClickListener {
            onClick?.invoke()
            dialog.dismiss()
        }
    }

    fun setSecondary(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setSecondary(view.context.getString(res), onClick)

    /** Closes on the primary button; disable for a form that validates itself. */
    fun keepOpenOnPrimary() = apply {
        primaryButton.setOnClickListener(null)
    }

    fun show(): SwSheet = apply {
        dialog.setOnShowListener {
            // The Material sheet frame paints its own light background; the kit's
            // ink sheet is the only surface (mockup 05/09).
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    val isShowing: Boolean get() = dialog.isShowing

    class Builder internal constructor(private val context: Context) {
        private val sheet = SwSheet(context, BottomSheetDialog(context))

        fun setTitle(text: CharSequence) = apply { sheet.setTitle(text) }
        fun setTitle(res: Int) = apply { sheet.setTitle(res) }
        fun setMessage(text: CharSequence) = apply { sheet.setMessage(text) }
        fun setMessage(res: Int) = apply { sheet.setMessage(res) }
        fun setContent(content: View) = apply { sheet.setContent(content) }
        fun setPrimary(text: CharSequence, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimary(text, onClick)
        }

        fun setPrimary(res: Int, onClick: (() -> Unit)? = null) = apply { sheet.setPrimary(res, onClick) }
        fun setSecondary(text: CharSequence, onClick: (() -> Unit)? = null) = apply {
            sheet.setSecondary(text, onClick)
        }

        fun setSecondary(res: Int, onClick: (() -> Unit)? = null) = apply { sheet.setSecondary(res, onClick) }
        fun build(): SwSheet = sheet
    }

    companion object {
        const val TAG = "SwSheet"

        fun builder(context: Context): Builder = Builder(context)

        /** The honest non-dead-end for a tool that is not built yet. */
        fun comingSoon(context: Context, tool: SwTool): SwSheet {
            val note = tool.soonNote?.let { context.getString(it) }
                ?: context.getString(R.string.tool_coming_soon_note)
            return builder(context)
                .setTitle(R.string.tool_coming_soon_title)
                .setMessage(note)
                .setPrimary(R.string.sheet_got_it)
                .build()
        }

        /** A simple message sheet ("Coming soon", "Nothing here yet"). */
        fun note(context: Context, title: CharSequence, message: CharSequence): SwSheet =
            builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPrimary(R.string.sheet_got_it)
                .build()
    }
}
