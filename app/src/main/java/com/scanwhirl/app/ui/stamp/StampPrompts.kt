package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.scanwhirl.app.R
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.PageStamp

/**
 * L5 Phase B — the editor's sheets, all built with the design kit's [SwSheet]: text entry,
 * saved signatures, preset badges and the signature pad. No `AlertDialog` and no bespoke
 * sheet shell remains in this lane.
 */
object StampPrompts {

    fun text(
        activity: Activity,
        titleRes: Int,
        hintRes: Int,
        initial: String,
        onAdd: (String) -> Unit
    ) {
        val input = EditText(activity).apply {
            id = R.id.stampTextInput
            hint = activity.getString(hintRes)
            setText(initial)
            setSelection(text.length)
            setTextColor(SwStyle.color(activity, R.color.text_hologram_hi))
            setHintTextColor(SwStyle.color(activity, R.color.text_hologram_dim))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 3
            minHeight = SwStyle.dp(activity, 52f)
            setPadding(
                SwStyle.dp(activity, 14f), SwStyle.dp(activity, 12f),
                SwStyle.dp(activity, 14f), SwStyle.dp(activity, 12f)
            )
            background = SwStyle.rounded(
                activity,
                SwStyle.color(activity, R.color.glass_surface_raised),
                radiusDp = 14f,
                stroke = SwStyle.color(activity, R.color.sw_field_border)
            )
        }
        val sheet = SwSheet.builder(activity)
            .setTitle(titleRes)
            .setContent(input)
            .setPrimary(R.string.stamp_add)
            .build()
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                input.error = activity.getString(R.string.stamp_text_required)
            } else {
                sheet.dismiss()
                onAdd(value)
            }
        }
        sheet.show()
        input.requestFocus()
        val ime = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Saved signatures (vector paths), or the pad when none exist. A signature that cannot be
     * saved — a sixth, or empty ink — says why and keeps the pad open.
     */
    fun signature(
        activity: Activity,
        store: SignatureStore,
        onPick: (SignatureStore.SavedSignature) -> Unit
    ) {
        val saved = store.list()
        if (saved.isEmpty()) {
            pad(activity, store, onPick)
            return
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.stampSignatureRows
        }
        lateinit var sheet: SwSheet
        saved.forEach { signature ->
            list.addView(
                row(
                    activity,
                    signature.name,
                    onClick = {
                        sheet.dismiss()
                        onPick(signature)
                    },
                    onLongClick = {
                        sheet.dismiss()
                        confirmSignatureDelete(activity, store, signature) {
                            // Show what is left so a deletion never looks like a crash.
                            signature(activity, store, onPick)
                        }
                    }
                )
            )
        }
        sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_signature_title)
            .setContent(list)
            .setSecondary(R.string.stamp_signature_new) { pad(activity, store, onPick) }
            .build()
        sheet.show()
    }

    fun pad(
        activity: Activity,
        store: SignatureStore,
        onSaved: (SignatureStore.SavedSignature) -> Unit
    ) {
        val pad = SignaturePadView(activity).apply {
            id = R.id.stampSignaturePad
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SwStyle.dp(activity, 240f)
            )
            background = SwStyle.rounded(activity, Color.WHITE, radiusDp = 14f)
        }
        val sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_signature_sheet_title)
            .setContent(pad)
            .setPrimary(R.string.stamp_signature_save)
            .build()
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            if (pad.isEmpty()) {
                Toast.makeText(activity, R.string.stamp_signature_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = activity.getString(R.string.stamp_signature_name, store.list().size + 1)
            try {
                val saved = store.save(name, pad.vectorStrokes())
                sheet.dismiss()
                onSaved(saved)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    e.message ?: activity.getString(R.string.stamp_signature_full),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        sheet.show()
    }

    fun badges(activity: Activity, onPick: (PageStamp.BadgeKind) -> Unit) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.stampBadgeRows
        }
        lateinit var sheet: SwSheet
        val labels = listOf(
            PageStamp.BadgeKind.PAID to R.string.stamp_badge_paid,
            PageStamp.BadgeKind.APPROVED to R.string.stamp_badge_approved,
            PageStamp.BadgeKind.DRAFT to R.string.stamp_badge_draft,
            PageStamp.BadgeKind.RECEIVED to R.string.stamp_badge_received,
            PageStamp.BadgeKind.SELF_ATTESTED to R.string.stamp_badge_self_attested
        )
        labels.forEach { (kind, label) ->
            list.addView(
                row(activity, activity.getString(label)) {
                    sheet.dismiss()
                    onPick(kind)
                }
            )
        }
        sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_badge_title)
            .setContent(list)
            .build()
        sheet.show()
    }

    private fun confirmSignatureDelete(
        activity: Activity,
        store: SignatureStore,
        signature: SignatureStore.SavedSignature,
        onDeleted: () -> Unit
    ) {
        SwSheet.builder(activity)
            .setTitle(R.string.stamp_signature_delete_title)
            .setMessage(activity.getString(R.string.stamp_signature_delete_msg, signature.name))
            .setPrimary(R.string.stamp_delete) {
                store.delete(signature.id)
                onDeleted()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun row(
        activity: Activity,
        text: String,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView =
        SwStyle.text(
            activity, 16f, SwStyle.color(activity, R.color.text_hologram_hi)
        ).apply {
            this.text = text
            gravity = Gravity.CENTER_VERTICAL
            minHeight = SwStyle.dp(activity, 52f)
            setPadding(
                SwStyle.dp(activity, 14f), SwStyle.dp(activity, 10f),
                SwStyle.dp(activity, 14f), SwStyle.dp(activity, 10f)
            )
            background = SwStyle.rounded(
                activity,
                SwStyle.color(activity, R.color.glass_surface_raised),
                radiusDp = 14f,
                stroke = SwStyle.color(activity, R.color.sw_cyan_hair)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = SwStyle.dp(activity, 6f) }
        }
}
