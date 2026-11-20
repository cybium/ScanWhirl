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
import com.scanwhirl.app.util.SecureView

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
                // Opaque ink: the reader dock must never show through a sheet.
                0xFF161D21.toInt(),
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
            typeface = SwType.bold(context)
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
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
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
            typeface = SwType.bold(context)
            setTextColor(SwStyle.color(context, R.color.sw_disc_ink))
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.cyan_primary), radiusDp = 16f
            )
            minHeight = (54 * density).toInt()
            // SAFE-UNLOCK-CONSENT a11y: a 54dp fixed height cut a two-line primary at 200% font
            // ("Remove password and continue" needed 163px, had 108px). minHeight keeps the
            // 54dp look at 100%; the button now grows with its label.
            val padV = (8 * density).toInt()
            setPadding(0, padV, 0, padV)
            isVisible = false
        }
        root.addView(
            primaryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * density).toInt() }
        )

        secondaryButton = TextView(context).apply {
            id = R.id.swSheetSecondary
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = SwType.bold(context)
            setTextColor(SwStyle.color(context, R.color.text_hologram_mid))
            minHeight = (48 * density).toInt()
            isVisible = false
        }
        root.addView(
            secondaryButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
        )

        view = root
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.66f)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
    }

    /** The sheet's text field, created by [setInput]; tests find it as swSheetInput. */
    var inputView: android.widget.EditText? = null
        private set

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

    /**
     * Opt in to one viewport for the whole form. Even in a short keyboard-resized window the
     * title and actions can scroll away, rather than leaving too little room for a field.
     */
    private fun setScrollableContent(content: androidx.core.widget.NestedScrollView): SwSheet = apply {
        val root = view as LinearLayout
        val column = LinearLayout(view.context).apply { orientation = LinearLayout.VERTICAL }
        // Reuse the existing viewport (and its scrollbar) rather than nesting scroll views.
        val form = checkNotNull(content.getChildAt(0))
        content.removeView(form)
        setContent(form)
        while (root.childCount > 0) {
            val child = root.getChildAt(0)
            root.removeViewAt(0)
            column.addView(child)
        }
        content.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    /** A quiet text field for rename / merge-name sheets. */
    fun setInput(
        initial: String? = null,
        hint: CharSequence? = null,
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT
    ): SwSheet = apply {
        val context = view.context
        val field = android.widget.EditText(context).apply {
            id = R.id.swSheetInput
            this.hint = hint
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            typeface = SwType.regular(context)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            this.inputType = inputType
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface_raised),
                14f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair)
            )
            val padH = SwStyle.dp(context, 14f)
            val padV = SwStyle.dp(context, 12f)
            setPadding(padH, padV, padH, padV)
            minHeight = SwStyle.dp(context, 48f)
            includeFontPadding = false
            if (!initial.isNullOrEmpty()) {
                setText(initial)
                setSelection(initial.length)
            }
        }
        inputView = field
        setContent(field)
    }

    /**
     * v2 UI: 05_protect_pdf / 09_unlock_sheet — the one list in the app.
     *
     * Rows live in `R.id.swSheetItems` in order (tests click a row by index).
     * A single-choice pick calls [onPick] and closes; multi-choice rows toggle
     * and leave the sheet open for the primary button.
     */
    fun setOptions(
        labels: List<CharSequence>,
        checked: Int = -1,
        multiChecked: BooleanArray? = null,
        onPick: (Int, Boolean) -> Unit
    ): SwSheet = apply {
        val context = view.context
        val multi = multiChecked != null
        val selected = multiChecked?.copyOf() ?: BooleanArray(labels.size) { it == checked }
        val ripple = android.util.TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        val list = LinearLayout(context).apply {
            id = R.id.swSheetItems
            orientation = LinearLayout.VERTICAL
        }
        fun render() {
            list.removeAllViews()
            labels.forEachIndexed { index, label ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    minimumHeight = SwStyle.dp(context, 48f)
                    tag = index
                    contentDescription = label
                    setBackgroundResource(ripple.resourceId)
                    setPadding(
                        SwStyle.dp(context, 6f), SwStyle.dp(context, 6f),
                        SwStyle.dp(context, 6f), SwStyle.dp(context, 6f)
                    )
                }
                row.addView(
                    SwStyle.text(context, 15f, SwStyle.color(context, R.color.text_hologram_hi)).apply {
                        text = label
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                if (selected[index]) {
                    row.addView(android.widget.ImageView(context).apply {
                        setImageResource(R.drawable.ic_sym_check)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            SwStyle.color(context, R.color.cyan_primary)
                        )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = LinearLayout.LayoutParams(
                            SwStyle.dp(context, 20f), SwStyle.dp(context, 20f)
                        )
                    })
                }
                row.setOnClickListener {
                    SwHaptics.tick(row)
                    if (multi) {
                        selected[index] = !selected[index]
                        render()
                        onPick(index, selected[index])
                    } else {
                        onPick(index, true)
                        dialog.dismiss()
                    }
                }
                SwMotion.press(row)
                list.addView(row)
            }
        }
        render()
        val scroll = androidx.core.widget.NestedScrollView(context).apply {            isFillViewport = false
            addView(list)
        }
        val tall = labels.size > 8
        setContent(scroll)
        SwMotion.stagger(list)
        if (tall) {
            scroll.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
            )
        }
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

    /** Runs when the sheet closes for any reason (button, close icon, back, outside tap). */
    fun setOnDismissListener(action: () -> Unit) = apply {
        dialog.setOnDismissListener { action() }
    }

    /**
     * A primary whose work decides when to close: the sheet stays open (a
     * failed save keeps the retry in place) and the caller dismisses it.
     */
    fun setPrimaryStayOpen(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet = apply {
        primaryButton.text = text
        primaryButton.isVisible = true
        primaryButton.setOnClickListener { onClick?.invoke() }
    }

    fun setPrimaryStayOpen(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setPrimaryStayOpen(view.context.getString(res), onClick)

    fun show(): SwSheet = apply {
        dialog.setOnShowListener {
            // The Material sheet frame paints its own light background; the kit's
            // ink sheet is the only surface (mockup 05/09).
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            // TalkBack starts on the sheet's title, not the first field.
            dialog.window?.decorView?.let { decor ->
                androidx.core.view.ViewCompat.setAccessibilityPaneTitle(
                    decor, titleView.text
                )
            }
        }
        dialog.show()
    }

    /**
     * Owner F14: a sheet whose content is a drawing surface must not move under a stroke.
     * Dragging, hiding, Back and an outside tap are all disabled; it closes only through its
     * own X ([closeButton]) or its primary (Done) button. Call before [show].
     */
    fun lockDrag(): SwSheet = apply {
        dialog.behavior.isDraggable = false
        dialog.behavior.isHideable = false
        dialog.setCancelable(false)
        // A grab bar that cannot drag is a lie: hide it.
        view.findViewById<View>(R.id.swSheetGrab)?.isVisible = false
    }

    fun dismiss() = dialog.dismiss()

    /** Secret fields: hide this window from screenshots, recents and casting. */
    fun secure(): SwSheet = apply { SecureView.apply(dialog.window) }

    val isShowing: Boolean get() = dialog.isShowing

    /** The sheet's own window (used to set FLAG_SECURE while a password is on screen). */
    val window: android.view.Window? get() = dialog.window

    class Builder internal constructor(private val context: Context) {
        private val sheet = SwSheet(context, BottomSheetDialog(context))

        fun setTitle(text: CharSequence) = apply { sheet.setTitle(text) }
        fun setTitle(res: Int) = apply { sheet.setTitle(res) }
        fun setMessage(text: CharSequence) = apply { sheet.setMessage(text) }
        fun setMessage(res: Int) = apply { sheet.setMessage(res) }
        fun setContent(content: View) = apply { sheet.setContent(content) }
        fun setScrollableContent(content: androidx.core.widget.NestedScrollView) = apply {
            sheet.setScrollableContent(content)
        }
        fun setInput(
            initial: String? = null,
            hint: CharSequence? = null,
            inputType: Int = android.text.InputType.TYPE_CLASS_TEXT
        ) = apply { sheet.setInput(initial, hint, inputType) }

        fun setOptions(
            labels: List<CharSequence>,
            checked: Int = -1,
            multiChecked: BooleanArray? = null,
            onPick: (Int, Boolean) -> Unit
        ) = apply { sheet.setOptions(labels, checked, multiChecked, onPick) }
        fun setPrimary(text: CharSequence, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimary(text, onClick)
        }

        fun setPrimary(res: Int, onClick: (() -> Unit)? = null) = apply { sheet.setPrimary(res, onClick) }
        fun setSecondary(text: CharSequence, onClick: (() -> Unit)? = null) = apply {
            sheet.setSecondary(text, onClick)
        }

        fun setSecondary(res: Int, onClick: (() -> Unit)? = null) = apply { sheet.setSecondary(res, onClick) }
        fun setPrimaryStayOpen(res: Int, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimaryStayOpen(res, onClick)
        }
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
