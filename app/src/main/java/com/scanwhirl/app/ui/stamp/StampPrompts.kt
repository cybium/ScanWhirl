package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.SignatureStore
import com.scanwhirl.app.util.PageStamp

/**
 * L5 Phase B — the editor's three sheets (text entry, saved signatures, preset badges) and the
 * signature pad. They are [BottomSheetDialog]s, not `AlertDialog`s, so they already use the v2
 * sheet language; L1's `SwSheet` can restyle them later without changing behaviour.
 */
object StampPrompts {

    fun text(
        activity: Activity,
        titleRes: Int,
        hintRes: Int,
        initial: String,
        onAdd: (String) -> Unit
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_stamp_text, null)
        sheet.setContentView(view)
        val title = view.findViewById<TextView>(R.id.sheetTitle)
        val input = view.findViewById<android.widget.EditText>(R.id.sheetInput)
        title.setText(titleRes)
        input.hint = activity.getString(hintRes)
        input.setText(initial)
        input.setSelection(input.text.length)
        view.findViewById<View>(R.id.sheetCancel).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.sheetAdd).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                sheet.dismiss()
                onAdd(value)
            }
        }
        sheet.setOnShowListener {
            input.requestFocus()
            val ime = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        sheet.show()
    }

    /**
     * Shows the saved signatures, or goes straight to the pad when there is none. A signature
     * that could not be saved (a sixth, empty ink) says why and keeps the pad open.
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
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_stamp_signatures, null)
        sheet.setContentView(view)
        val rows = view.findViewById<LinearLayout>(R.id.signatureRows)
        saved.forEach { signature ->
            rows.addView(
                sheetRow(activity, signature.name) {
                    sheet.dismiss()
                    onPick(signature)
                }
            )
        }
        view.findViewById<View>(R.id.drawNewSignature).setOnClickListener {
            sheet.dismiss()
            pad(activity, store, onPick)
        }
        sheet.show()
    }

    fun pad(
        activity: Activity,
        store: SignatureStore,
        onSaved: (SignatureStore.SavedSignature) -> Unit
    ) {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_stamp_signature_pad, null)
        sheet.setContentView(view)
        val pad = view.findViewById<SignaturePadView>(R.id.signaturePad)
        view.findViewById<View>(R.id.signatureCancel).setOnClickListener { sheet.dismiss() }
        view.findViewById<View>(R.id.signatureSave).setOnClickListener {
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
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_stamp_badges, null)
        sheet.setContentView(view)
        val rows = view.findViewById<LinearLayout>(R.id.badgeRows)
        val labels = listOf(
            PageStamp.BadgeKind.PAID to R.string.stamp_badge_paid,
            PageStamp.BadgeKind.APPROVED to R.string.stamp_badge_approved,
            PageStamp.BadgeKind.DRAFT to R.string.stamp_badge_draft,
            PageStamp.BadgeKind.RECEIVED to R.string.stamp_badge_received,
            PageStamp.BadgeKind.SELF_ATTESTED to R.string.stamp_badge_self_attested
        )
        labels.forEach { (kind, label) ->
            rows.addView(
                sheetRow(activity, activity.getString(label)) {
                    sheet.dismiss()
                    onPick(kind)
                }
            )
        }
        sheet.show()
    }

    private fun sheetRow(activity: Activity, text: String, onClick: () -> Unit): TextView {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.text_hologram_hi))
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_sw_sheet_row)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
    }
}
