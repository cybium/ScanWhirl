package com.scanwhirl.app.ui.kit

import android.app.Dialog
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
    private val bodyColumn: LinearLayout
    private var bodyScroll: androidx.core.widget.NestedScrollView
    private val dismissActions = DismissActions(dialog)
    private var primaryFired = false
    private var secondaryFired = false

    init {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()

        val root = LinearLayout(context).apply {
            id = R.id.swSheetRoot
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (10 * density).toInt(), padH, (22 * density).toInt())
            background = SwStyle.rounded(
                context,
                // Opaque ink: the reader dock must never show through a sheet. A semantic
                // token now, so a future Paper palette can repaint it without a code edit.
                SwStyle.color(context, R.color.rv_surface),
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
        bodyColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll = object : androidx.core.widget.NestedScrollView(context) {
            override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean =
                if (!dialog.behavior.isDraggable) false else super.onInterceptTouchEvent(event)
        }.apply {
            isFillViewport = false
            addView(bodyColumn)
        }
        // Only this viewport can shrink. The wrapping actions remain siblings below it,
        // including when ADJUST_RESIZE gives us a shorter window for the keyboard.
        root.addView(bodyScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        bodyColumn.addView(grab)

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
            setOnClickListener { dismissActions.dismiss() }
        }
        header.addView(closeButton)
        bodyColumn.addView(header)

        messageView = SwStyle.text(
            context, 14f, SwStyle.color(context, R.color.text_hologram_mid), metadata = true
        ).apply {
            id = R.id.swSheetMessage
            setLineSpacing(0f, 1.25f)
            isVisible = false
        }
        bodyColumn.addView(
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
        bodyColumn.addView(contentContainer)

        primaryButton = actionText(context).apply {
            id = R.id.swSheetPrimary
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = SwType.bold(context)
            setTextColor(SwStyle.color(context, R.color.sw_disc_ink))
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.sw_scan), radiusDp = 16f
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

        secondaryButton = actionText(context).apply {
            id = R.id.swSheetSecondary
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = SwType.bold(context)
            setTextColor(SwStyle.color(context, R.color.text_hologram_mid))
            minHeight = (48 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(0, padV, 0, padV)
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
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setDimAmount(0.66f)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        // KT1: a sheet must not outlive its Activity (WindowLeaked + a callback into a dead screen).
        context.attachDialogLifecycle(dialog, dismissActions)
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
        if (content is androidx.core.widget.NestedScrollView) {
            setScrollableContent(content)
            return@apply
        }
        contentContainer.removeAllViews()
        contentContainer.addView(content)
        contentContainer.isVisible = true
    }

    /**
     * Reuse the caller's viewport and scrollbar for title and form, never for the footer.
     * No nested scroll view: fields remain reachable above the pinned actions with the IME.
     */
    private fun setScrollableContent(content: androidx.core.widget.NestedScrollView): SwSheet = apply {
        val root = view as LinearLayout
        // Reuse the existing viewport (and its scrollbar) rather than nesting scroll views.
        val form = checkNotNull(content.getChildAt(0))
        content.removeView(form)
        setContent(form)
        bodyScroll.removeView(bodyColumn)
        root.removeView(bodyScroll)
        content.addView(bodyColumn, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bodyScroll = content
        root.addView(content, 0, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
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
        // A single choice closes the sheet: a double tap must not reach the caller twice.
        // Multi-choice rows keep toggling (that is their purpose) and are not one-shot.
        var optionPicked = false
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
                    // F3 (hunt H6-3): the tick is decorative, so TalkBack must hear the choice
                    // state from the row itself: "checked / not checked" (multi) or
                    // "selected" (single), never just the label.
                    isSelected = selected[index]
                    val isChecked = selected[index]
                    androidx.core.view.ViewCompat.setAccessibilityDelegate(
                        this,
                        object : androidx.core.view.AccessibilityDelegateCompat() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = if (multi) {
                                    android.widget.CheckBox::class.java.name
                                } else {
                                    android.widget.RadioButton::class.java.name
                                }
                                info.isCheckable = true
                                info.isChecked = isChecked
                            }
                        }
                    )
                    setBackgroundResource(ripple.resourceId)
                    setPadding(
                        SwStyle.dp(context, 6f), SwStyle.dp(context, 6f),
                        SwStyle.dp(context, 6f), SwStyle.dp(context, 6f)
                    )
                }
                row.addView(
                    SwStyle.twoLineLabel(
                        context, 15f, SwStyle.color(context, R.color.text_hologram_hi)
                    ).apply { text = label },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = SwStyle.dp(context, 12f) }
                )
                // W5a-2 (V059): a real radio/checkbox is visible in BOTH states. A
                // lone tick on the selected row gave no ON/OFF affordance at all.
                val on = selected[index]
                row.addView(android.widget.ImageView(context).apply {
                    setImageResource(
                        when {
                            multi && on -> R.drawable.ic_sw_checkbox_on
                            multi -> R.drawable.ic_sw_checkbox_off
                            on -> R.drawable.ic_sw_radio_on
                            else -> R.drawable.ic_sw_radio_off
                        }
                    )
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        SwStyle.color(
                            context,
                            if (on) R.color.cyan_primary else R.color.text_hologram_dim
                        )
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(
                        SwStyle.dp(context, 20f), SwStyle.dp(context, 20f)
                    )
                })
                row.setOnClickListener {
                    SwHaptics.tick(row)
                    if (multi) {
                        selected[index] = !selected[index]
                        render()
                        onPick(index, selected[index])
                    } else {
                        if (optionPicked) return@setOnClickListener
                        optionPicked = true
                        onPick(index, true)
                        dismissActions.dismiss()
                    }
                }
                SwMotion.press(row)
                list.addView(row)
                // W5a-2 (V063/V091): inset separators, not full-bleed hairlines.
                if (index < labels.lastIndex) {
                    list.addView(SwStyle.divider(context, insetDp = 6f))
                }
            }
        }
        render()
        val scroll = androidx.core.widget.NestedScrollView(context).apply {            isFillViewport = false
            addView(list)
        }
        setContent(scroll)
        SwMotion.stagger(list)
    }

    fun setPrimary(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet =
        setPrimary(text, null, onClick)

    fun setPrimary(text: CharSequence, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet = apply {
        primaryFired = false
        primaryButton.text = text
        setActionIcon(primaryButton, iconRes)
        primaryButton.isVisible = true
        primaryButton.setOnClickListener {
            if (primaryFired) return@setOnClickListener
            primaryFired = true
            onClick?.invoke()
            dismissActions.dismiss()
        }
    }

    fun setPrimary(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setPrimary(view.context.getString(res), onClick)

    fun setPrimary(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet =
        setPrimary(view.context.getString(res), iconRes, onClick)

    fun setSecondary(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet =
        setSecondary(text, null, onClick)

    fun setSecondary(text: CharSequence, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet = apply {
        // W5a-2 (V065): when the footer is a Cancel action it is the ONE dismissal
        // control. Keeping the header X as a second, identical exit was a duplicate.
        val cancelLabels = setOf(
            view.context.getString(R.string.sheet_cancel),
            view.context.getString(android.R.string.cancel)
        )
        if (text.toString().trim() in cancelLabels) closeButton.isVisible = false
        secondaryFired = false
        secondaryButton.text = text
        setActionIcon(secondaryButton, iconRes)
        secondaryButton.isVisible = true
        secondaryButton.setOnClickListener {
            if (secondaryFired) return@setOnClickListener
            secondaryFired = true
            onClick?.invoke()
            dismissActions.dismiss()
        }
    }

    fun setSecondary(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setSecondary(view.context.getString(res), onClick)

    fun setSecondary(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet =
        setSecondary(view.context.getString(res), iconRes, onClick)

    /** TextView compound drawables are decorative: accessibility reads only the action text. */
    private fun setActionIcon(button: TextView, iconRes: Int?) {
        val icon = iconRes?.let {
            androidx.core.content.ContextCompat.getDrawable(button.context, it)?.mutate()?.apply {
                val size = SwStyle.dp(button.context, 20f)
                setBounds(0, 0, size, size)
                androidx.core.graphics.drawable.DrawableCompat.setTint(this, button.currentTextColor)
            }
        }
        button.setCompoundDrawablesRelative(icon, null, null, null)
        button.compoundDrawablePadding = if (icon == null) 0 else SwStyle.dp(button.context, 8f)
        val padH = if (icon == null) 0 else SwStyle.dp(button.context, 16f)
        button.setPadding(padH, button.paddingTop, padH, button.paddingBottom)
    }

    private fun actionText(context: Context): TextView =
        object : androidx.appcompat.widget.AppCompatTextView(context) {
            override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
            override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = android.widget.Button::class.java.name
            }
        }

    /** Closes on the primary button; disable for a form that validates itself. */
    fun keepOpenOnPrimary() = apply {
        primaryButton.setOnClickListener(null)
    }

    /** Runs when the sheet closes for any reason (button, close icon, back, outside tap). */
    fun setOnDismissListener(action: () -> Unit) = apply {
        dismissActions.add(action)
    }

    /**
     * A primary whose work decides when to close: the sheet stays open (a
     * failed save keeps the retry in place) and the caller dismisses it.
     */
    fun setPrimaryStayOpen(text: CharSequence, onClick: (() -> Unit)? = null): SwSheet =
        setPrimaryStayOpen(text, null, onClick)

    fun setPrimaryStayOpen(text: CharSequence, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet = apply {
        primaryButton.text = text
        setActionIcon(primaryButton, iconRes)
        primaryButton.isVisible = true
        primaryButton.setOnClickListener { onClick?.invoke() }
    }

    fun setPrimaryStayOpen(res: Int, onClick: (() -> Unit)? = null): SwSheet =
        setPrimaryStayOpen(view.context.getString(res), onClick)

    fun setPrimaryStayOpen(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null): SwSheet =
        setPrimaryStayOpen(view.context.getString(res), iconRes, onClick)

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

    fun dismiss() = dismissActions.dismiss()

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
        fun setPrimary(text: CharSequence, iconRes: Int?, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimary(text, iconRes, onClick)
        }
        fun setPrimary(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimary(res, iconRes, onClick)
        }
        fun setSecondary(text: CharSequence, onClick: (() -> Unit)? = null) = apply {
            sheet.setSecondary(text, onClick)
        }

        fun setSecondary(res: Int, onClick: (() -> Unit)? = null) = apply { sheet.setSecondary(res, onClick) }
        fun setSecondary(text: CharSequence, iconRes: Int?, onClick: (() -> Unit)? = null) = apply {
            sheet.setSecondary(text, iconRes, onClick)
        }
        fun setSecondary(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null) = apply {
            sheet.setSecondary(res, iconRes, onClick)
        }
        fun setPrimaryStayOpen(res: Int, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimaryStayOpen(res, onClick)
        }
        fun setPrimaryStayOpen(res: Int, iconRes: Int?, onClick: (() -> Unit)? = null) = apply {
            sheet.setPrimaryStayOpen(res, iconRes, onClick)
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

/**
 * A Dialog has exactly one platform OnDismissListener, but the kit and its callers each need one.
 * Keep them here and install the real listener once, so adding a callback never silently removes
 * another (the lifecycle observer removal, the whirl stop or the caller's onDismiss).
 */
internal class DismissActions(private val dialog: Dialog) {
    private val actions = mutableListOf<Pair<() -> Unit, Boolean>>()
    /** One token per dismissal, in initiation order: true = lifecycle (silent) dismissal. */
    private val dismissals = ArrayDeque<Boolean>()
    private var installed = false

    fun add(action: () -> Unit) = add(action, internal = false)

    /** Internal cleanup (observer removal) still runs when caller callbacks are suppressed. */
    fun addInternal(action: () -> Unit) = add(action, internal = true)

    private fun add(action: () -> Unit, internal: Boolean) {
        actions += action to internal
        if (!installed) {
            installed = true
            dialog.setOnDismissListener { fire() }
        }
    }

    /** A user/flow dismissal: its callbacks must run, even if the owner finishes in the same frame. */
    fun dismiss() {
        dismissals.addLast(false)
        dialog.dismiss()
    }

    /**
     * The owner is being destroyed: close the window silently. The suppression token belongs to
     * THIS dismissal only; a manual dismissal already queued keeps its own (non-silent) token, so
     * a poster/callback from that earlier dismissal still runs the caller's logic exactly once.
     */
    fun closeFromLifecycle() {
        if (!dialog.isShowing) return
        dismissals.addLast(true)
        dialog.dismiss()
    }

    private fun fire() {
        val skipCallerActions = dismissals.removeFirstOrNull() ?: false
        actions.toList().forEach { (action, internal) ->
            if (!skipCallerActions || internal) action()
        }
    }
}

/**
 * KT1: when the owner is a ComponentActivity, follow its lifecycle. ON_DESTROY closes the window
 * (no WindowLeaked) without running caller callbacks from a finished screen; apps that restore
 * their own sheet after a rotation rely on that silence. A normal dismissal removes the observer
 * so a long-lived activity does not accumulate one per sheet. A plain Application context is not
 * a LifecycleOwner and is left alone.
 */
internal fun Context.attachDialogLifecycle(dialog: Dialog, actions: DismissActions) {
    val owner = this as? LifecycleOwner ?: return
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            // The posted dismiss callback runs the internal removal action below.
            actions.closeFromLifecycle()
        }
    }
    owner.lifecycle.addObserver(observer)
    actions.addInternal { owner.lifecycle.removeObserver(observer) }
}
