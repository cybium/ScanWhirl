package com.scanwhirl.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.scanwhirl.app.R

/**
 * Universal dialog & sheet manager for the Orbital design system.
 * Always builds and returns [androidx.appcompat.app.AlertDialog] (via [MaterialAlertDialogBuilder])
 * so existing Robolectric test assertions and ListView interactions remain 100% compatible.
 */
object SwDialog {

    fun card(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_ScanWhirl_Dialog)

    fun sheet(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_ScanWhirl_Dialog_Sheet)

    fun danger(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_ScanWhirl_Dialog_Danger)

    fun progress(
        context: Context,
        titleRes: Int? = null,
        messageRes: Int? = null
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (24 * density).toInt()
            val padV = (20 * density).toInt()
            setPadding(padH, padV, padH, padV)
        }

        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.cyan_primary)
            )
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (18 * density).toInt()
            }
        }
        root.addView(progress)

        val text = TextView(context).apply {
            text = when {
                messageRes != null -> context.getString(messageRes)
                titleRes != null -> context.getString(titleRes)
                else -> context.getString(R.string.progress_title)
            }
            setTextAppearance(R.style.TextAppearance_SW_Body)
            setTextColor(ContextCompat.getColor(context, R.color.text_hologram_hi))
        }
        root.addView(text)

        val builder = card(context)
            .setView(root)
            .setCancelable(false)

        return builder.showSw(isSheet = false)
    }

    fun input(
        context: Context,
        hint: String,
        initial: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): Pair<MaterialAlertDialogBuilder, EditText> {
        val density = context.resources.displayMetrics.density
        val container = FrameLayout(context).apply {
            val padH = (24 * density).toInt()
            val padV = (12 * density).toInt()
            setPadding(padH, padV, padH, (8 * density).toInt())
        }

        val editText = EditText(context).apply {
            this.hint = hint
            setHintTextColor(ContextCompat.getColor(context, R.color.text_hologram_dim))
            setTextColor(ContextCompat.getColor(context, R.color.text_hologram_hi))
            this.inputType = inputType
            if (!initial.isNullOrEmpty()) {
                setText(initial)
                setSelection(initial.length)
            }
            setBackgroundResource(R.drawable.bg_search_bar)
            val padInnerH = (16 * density).toInt()
            val padInnerV = (12 * density).toInt()
            setPadding(padInnerH, padInnerV, padInnerH, padInnerV)
            minHeight = (48 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(editText)

        val builder = card(context).setView(container)
        return Pair(builder, editText)
    }
}

/**
 * Creates and shows the dialog with Orbital window animations and gravity.
 */
fun MaterialAlertDialogBuilder.showSw(isSheet: Boolean = false): AlertDialog {
    val dialog = this.create()
    val window = dialog.window
    if (window != null) {
        if (isSheet) {
            window.setGravity(Gravity.BOTTOM)
            window.setWindowAnimations(R.style.Animation_ScanWhirl_Sheet)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else {
            window.setGravity(Gravity.CENTER)
            window.setWindowAnimations(R.style.Animation_ScanWhirl_Card)
        }
        window.setDimAmount(0.85f)
    }
    dialog.show()
    return dialog
}
