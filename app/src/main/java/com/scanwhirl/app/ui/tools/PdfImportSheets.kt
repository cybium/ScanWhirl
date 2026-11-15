package com.scanwhirl.app.ui.tools

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.PdfImportBudget
import java.util.Locale

/**
 * v2 UI: 03_tools_hub — the swsheets around a big PDF import: the pre-check decision, the page
 * range and the "Importing page k of N" progress with Cancel.
 */
object PdfImportSheets {

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        else -> String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024))
    }

    /** "This PDF has N pages": Import all / Choose pages / Cancel. */
    fun ask(
        activity: AppCompatActivity,
        plan: PdfImportBudget.Plan,
        onDecision: (ScanRepository.ImportDecision) -> Unit
    ): SwSheet {
        var settled = false
        fun decide(decision: ScanRepository.ImportDecision, sheet: SwSheet) {
            if (settled) return
            settled = true
            sheet.dismiss()
            onDecision(decision)
        }
        val sheet = SwSheet.builder(activity)
            .setTitle(activity.getString(R.string.tools_import_plan_title, plan.pageCount))
            .setMessage(
                activity.getString(
                    R.string.tools_import_plan_msg,
                    formatSize(plan.estimateBytes),
                    formatSize(plan.freeBytes)
                )
            )
            .setSecondary(R.string.sheet_cancel)
            .build()
        sheet.setPrimary(R.string.tools_import_all) { decide(ScanRepository.ImportDecision.All, sheet) }
        sheet.setSecondary(R.string.sheet_cancel) { decide(ScanRepository.ImportDecision.Cancel, sheet) }
        val choose = SwStyle.text(
            activity, 14f, SwStyle.color(activity, R.color.cyan_primary), bold = true
        ).apply {
            id = R.id.toolImportChoosePages
            text = activity.getString(R.string.tools_import_choose_pages)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(activity, 44f)
            isClickable = true
            setOnClickListener {
                if (settled) return@setOnClickListener
                settled = true
                sheet.dismiss()
                showRange(activity, plan.pageCount) { decision ->
                    onDecision(
                        if (decision is ScanRepository.ImportDecision.Pages) decision
                        else ScanRepository.ImportDecision.Cancel
                    )
                }.show()
            }
        }
        sheet.setContent(choose)
        sheet.setOnDismissListener {
            if (!settled) {
                settled = true
                onDecision(ScanRepository.ImportDecision.Cancel)
            }
        }
        return sheet
    }

    /** The page-range sheet ("1-20, 45"); invalid input stays open with a clear line. */
    fun showRange(
        activity: AppCompatActivity,
        pageCount: Int,
        onDecision: (ScanRepository.ImportDecision) -> Unit
    ): SwSheet {
        val sheet = SwSheet.builder(activity)
            .setTitle(R.string.tools_import_range_title)
            .setMessage(R.string.tools_import_range_msg)
            .setSecondary(R.string.sheet_cancel)
            .build()
        var settled = false
        val input = EditText(activity).apply {
            id = R.id.toolImportRange
            hint = activity.getString(R.string.tools_setup_ranges_hint)
            setHintTextColor(SwStyle.color(activity, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(activity, R.color.text_hologram_hi))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = SwStyle.rounded(
                activity,
                SwStyle.color(activity, R.color.glass_surface),
                radiusDp = 14f,
                stroke = SwStyle.color(activity, R.color.sw_field_border)
            )
            val pad = SwStyle.dp(activity, 14f)
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        sheet.setContent(input)
        sheet.setPrimary(R.string.tools_import_pages)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            if (settled) return@setOnClickListener
            val indices = PdfImportBudget.indices(input.text.toString(), pageCount)
            if (indices == null) {
                sheet.setMessage(R.string.tools_import_range_error)
            } else {
                settled = true
                sheet.dismiss()
                onDecision(ScanRepository.ImportDecision.Pages(indices))
            }
        }
        sheet.setSecondary(R.string.sheet_cancel) {
            if (!settled) {
                settled = true
                onDecision(ScanRepository.ImportDecision.Cancel)
            }
        }
        sheet.setOnDismissListener {
            // Closing without a range means no import.
            if (!settled) {
                settled = true
                onDecision(ScanRepository.ImportDecision.Cancel)
            }
        }
        return sheet
    }

    /** "Importing page k of N" with Cancel. Update with [SwSheet.setMessage]. */
    fun progress(activity: AppCompatActivity, onCancel: () -> Unit): SwSheet {
        val sheet = SwSheet.builder(activity)
            .setTitle(R.string.tools_import_progress_title)
            .setMessage(R.string.tools_import_starting)
            .setSecondary(R.string.sheet_cancel) { onCancel() }
            .build()
        sheet.setContent(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    SwStyle.text(activity, 13f, SwStyle.color(activity, R.color.text_hologram_dim)).apply {
                        text = activity.getString(R.string.tools_import_cancel_note)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(activity, 4f) }
                )
            }
        )
        return sheet
    }

    /** "Not enough free space": the same refusal copy for every device-PDF path. */
    fun refuse(activity: AppCompatActivity, plan: PdfImportBudget.Plan): SwSheet =
        SwSheet.builder(activity)
            .setTitle(R.string.tools_import_low_space_title)
            .setMessage(
                activity.getString(
                    R.string.tools_import_no_space,
                    plan.estimateBytes / (1024 * 1024),
                    plan.freeBytes / (1024 * 1024)
                )
            )
            .setPrimary(R.string.sheet_got_it)
            .build()

    /** Progress line for [SwSheet.setMessage]. */
    fun progressText(activity: AppCompatActivity, done: Int, total: Int): String =
        activity.getString(R.string.tools_import_progress_msg, done, total)
}
