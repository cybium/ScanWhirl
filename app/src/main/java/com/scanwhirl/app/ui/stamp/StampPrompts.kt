package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.scanwhirl.app.R
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.store.StampStore
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
        val ime = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        sheet.primaryButton.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                input.error = activity.getString(R.string.stamp_text_required)
            } else {
                // Close the keyboard with the sheet, or the next Back only hides the IME.
                ime.hideSoftInputFromWindow(input.windowToken, 0)
                sheet.dismiss()
                onAdd(value)
            }
        }
        sheet.show()
        input.requestFocus()
        ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Saved signatures (vector or imported PNG), or the pad when none exist. A signature that
     * cannot be saved (a sixth, or empty ink) says why and keeps the pad open. "From photo"
     * imports ink from a gallery/camera image.
     */
    fun signature(
        activity: Activity,
        store: SignatureStore,
        onPick: (SignatureStore.SavedSignature) -> Unit,
        onImportPhoto: () -> Unit = {}
    ) {
        val saved = store.list()
        if (saved.isEmpty()) {
            pad(activity, store, onPick, onImportPhoto)
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
                            signature(activity, store, onPick, onImportPhoto)
                        }
                    }
                )
            )
        }
        list.addView(
            row(activity, activity.getString(R.string.stamp_import_photo)) {
                sheet.dismiss()
                onImportPhoto()
            }
        )
        sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_signature_title)
            .setContent(list)
            .setSecondary(R.string.stamp_signature_new) { pad(activity, store, onPick, onImportPhoto) }
            .build()
        sheet.show()
    }

    fun pad(
        activity: Activity,
        store: SignatureStore,
        onSaved: (SignatureStore.SavedSignature) -> Unit,
        onImportPhoto: () -> Unit = {}
    ) {
        val pad = SignaturePadView(activity).apply {
            id = R.id.stampSignaturePad
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SwStyle.dp(activity, 240f)
            )
            background = SwStyle.rounded(activity, Color.WHITE, radiusDp = 14f)
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(pad) }
        val remember = CheckBox(activity).apply {
            setText(R.string.j34_remember_signature)
            tag = "remember-signature"
            minHeight = SwStyle.dp(activity, SwStyle.TOUCH_MIN_DP)
        }
        content.addView(remember)
        val clear = SwStyle.quietAction(activity, SwStyle.text(activity,
            com.scanwhirl.app.ui.kit.SwType.BODY_SP, SwStyle.color(activity, R.color.text_hologram_hi))).apply {
            setText(R.string.j34_clear)
            tag = "signature-clear"
            setOnClickListener { pad.clear() }
        }
        val undo = SwStyle.quietAction(activity, SwStyle.text(activity,
            com.scanwhirl.app.ui.kit.SwType.BODY_SP, SwStyle.color(activity, R.color.text_hologram_hi))).apply {
            setText(R.string.stamp_undo)
            setOnClickListener { pad.undo() }
        }
        content.addView(clear); content.addView(undo)
        val sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_signature_sheet_title)
            .setContent(content)
            .setPrimary(R.string.stamp_signature_save)
            .setSecondary(R.string.stamp_import_photo) { onImportPhoto() }
            .build()
        // Owner F14: the pad owns the gesture area. The sheet never drags away under a downward
        // stroke; it closes only through its X or Save.
        sheet.lockDrag()
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            if (pad.isEmpty()) {
                Toast.makeText(activity, R.string.stamp_signature_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = activity.getString(R.string.stamp_signature_name, store.list().size + 1)
            try {
                val saved = if (remember.isChecked) store.save(name, pad.vectorStrokes())
                    else SignatureStore.SavedSignature("session", name, pad.vectorStrokes(), System.currentTimeMillis())
                sheet.dismiss()
                pad.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30)
                    android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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

    fun badges(
        activity: Activity,
        stampStore: StampStore,
        onPick: (PageStamp.BadgeKind) -> Unit,
        onStamp: (StampStore.SavedStamp) -> Unit,
        onImportPhoto: () -> Unit = {}
    ) {
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
        val stamps = stampStore.list()
        list.addView(
            SwStyle.text(
                activity, 12f, SwStyle.color(activity, R.color.text_hologram_dim), bold = true
            ).apply {
                text = activity.getString(R.string.stamp_my_stamps)
                setPadding(
                    SwStyle.dp(activity, 6f), SwStyle.dp(activity, 10f),
                    SwStyle.dp(activity, 6f), SwStyle.dp(activity, 4f)
                )
            }
        )
        stamps.forEach { stamp ->
            list.addView(
                row(activity, stamp.name) {
                    sheet.dismiss()
                    onStamp(stamp)
                }
            )
        }
        list.addView(
            row(activity, activity.getString(R.string.stamp_stamp_import)) {
                sheet.dismiss()
                onImportPhoto()
            }
        )
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
