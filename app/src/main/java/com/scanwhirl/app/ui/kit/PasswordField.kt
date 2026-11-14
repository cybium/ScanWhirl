package com.scanwhirl.app.ui.kit

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.scanwhirl.app.R

/**
 * v2 UI: 05_protect_pdf / 09_unlock_sheet — password field with a show toggle,
 * an error line and an optional 4-segment strength meter.
 */
class PasswordField(context: Context) : LinearLayout(context) {

    val editText: EditText
    val toggleButton: ImageButton
    val errorView: TextView

    /** The four strength segments, left to right. */
    val segments: List<View>

    var showStrength: Boolean = true
        set(value) {
            field = value
            strengthRow.isVisible = value
        }

    private val fieldBox: LinearLayout
    private val strengthRow: LinearLayout

    /** True while the text wears a password transformation. */
    val isHidden: Boolean
        get() = !revealed

    private var revealed = false

    /** How many meter segments are lit for the current text. */
    val litSegments: Int
        get() = if (showStrength) PasswordStrength.score(editText.text.toString()) else 0

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density

        fieldBox = LinearLayout(context).apply {
            id = R.id.swPasswordField
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.void_nebula),
                radiusDp = 16f,
                stroke = SwStyle.color(context, R.color.sw_field_border)
            )
            minimumHeight = (56 * d).toInt()
            setPadding((16 * d).toInt(), 0, (8 * d).toInt(), 0)
        }

        fieldBox.addView(
            android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_sym_key)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(context, R.color.text_hologram_dim)
                )
                layoutParams = LayoutParams((24 * d).toInt(), (24 * d).toInt())
            }
        )

        editText = EditText(context).apply {
            hint = context.getString(R.string.pw_hint)
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            background = null
            isSingleLine = true
            setPadding((12 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
        fieldBox.addView(
            editText,
            LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL }
        )

        toggleButton = SwStyle.iconButton(
            context, R.drawable.ic_sym_visibility, SwStyle.color(context, R.color.text_hologram_dim)
        ).apply {
            id = R.id.swPasswordToggle
            contentDescription = context.getString(R.string.pw_show)
            setOnClickListener { toggleVisibility() }
        }
        fieldBox.addView(toggleButton)

        addView(
            fieldBox,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt())
        )

        errorView = SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.laser_danger), bold = true).apply {
            id = R.id.swPasswordError
            isVisible = false
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sym_error, 0, 0, 0)
            compoundDrawablePadding = (6 * d).toInt()
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.laser_danger)
            )
        }
        addView(
            errorView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * d).toInt() }
        )

        strengthRow = LinearLayout(context).apply {
            id = R.id.swStrengthRow
            orientation = HORIZONTAL
            isVisible = showStrength
        }
        segments = listOf(R.id.swStrength1, R.id.swStrength2, R.id.swStrength3, R.id.swStrength4).mapIndexed { index, id ->
            View(context).apply {
                this.id = id
                background = SwStyle.rounded(
                    context, SwStyle.color(context, R.color.rv_hairline), radiusDp = 2f
                )
            }.also { segment ->
                strengthRow.addView(
                    segment,
                    LayoutParams(0, (4 * d).toInt(), 1f).apply {
                        if (index > 0) marginStart = (6 * d).toInt()
                    }
                )
            }
        }
        addView(
            strengthRow,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt())
                .apply { topMargin = (10 * d).toInt() }
        )

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = paintStrength()
        })
        paintStrength()
    }

    fun text(): String = editText.text.toString()

    fun setError(message: CharSequence?) {
        errorView.isVisible = message != null
        errorView.text = message ?: ""
        fieldBox.background = SwStyle.rounded(
            context,
            SwStyle.color(context, R.color.void_nebula),
            radiusDp = 16f,
            stroke = SwStyle.color(
                context,
                if (message != null) R.color.laser_danger else R.color.sw_field_border
            )
        )
    }

    fun toggleVisibility() {
        val reveal = !revealed
        revealed = reveal
        editText.transformationMethod =
            if (reveal) null else PasswordTransformationMethod.getInstance()
        editText.inputType = if (reveal) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        editText.setSelection(editText.text.length)
        toggleButton.setImageResource(
            if (reveal) R.drawable.ic_sym_visibility_off else R.drawable.ic_sym_visibility
        )
        toggleButton.contentDescription =
            context.getString(if (reveal) R.string.pw_hide else R.string.pw_show)
    }

    private fun paintStrength() {
        val lit = PasswordStrength.score(editText.text.toString())
        val strong = SwStyle.color(context, R.color.ok_green)
        val fair = SwStyle.color(context, R.color.rv_warn)
        val weak = SwStyle.color(context, R.color.laser_danger)
        val off = SwStyle.color(context, R.color.rv_hairline)
        for ((index, segment) in segments.withIndex()) {
            val on = index < lit
            val color = when {
                !on -> off
                lit <= 2 -> weak
                lit == 3 -> fair
                else -> strong
            }
            segment.background = SwStyle.rounded(context, color, radiusDp = 2f)
        }
        strengthRow.contentDescription = when {
            editText.text.isEmpty() -> ""
            lit <= 2 -> context.getString(R.string.pw_strength_weak)
            lit == 3 -> context.getString(R.string.pw_strength_good)
            else -> context.getString(R.string.pw_strength_strong)
        }
    }
}
