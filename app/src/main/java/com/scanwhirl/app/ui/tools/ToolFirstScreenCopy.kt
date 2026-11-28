package com.scanwhirl.app.ui.tools

import android.content.Context
import androidx.annotation.StringRes
import com.scanwhirl.app.R

/**
 * VQA V103–V107 / V114: a bare format noun ("ZIP", "Word", "Protect") is not an instruction.
 * Each named tool's first screen gets one short line saying what happens. The Tools hub tile
 * and the shared result screen keep their own owners; this helper only feeds first screens.
 */
object ToolFirstScreenCopy {

    @StringRes
    fun forTool(toolId: String): Int? = when (toolId) {
        "split" -> R.string.w5e_purpose_split
        "to_word" -> R.string.w5e_purpose_to_word
        "to_excel" -> R.string.w5e_purpose_to_excel
        "to_powerpoint" -> R.string.w5e_purpose_to_powerpoint
        "long_image" -> R.string.w5e_purpose_long_image
        "zip" -> R.string.w5e_purpose_zip
        "pdfa" -> R.string.w5e_purpose_pdfa
        "batch_tools" -> R.string.w5e_purpose_batch_tools
        "searchable_pdf" -> R.string.w5e_purpose_searchable_pdf
        "link_index" -> R.string.w5e_purpose_link_index
        "form_ready" -> R.string.w5e_purpose_form_ready
        "remove_metadata" -> R.string.w5e_purpose_remove_metadata
        "qr_code" -> R.string.w5e_purpose_qr_code
        "protect" -> R.string.w5e_purpose_protect
        "unlock" -> R.string.w5e_purpose_unlock
        "read_text" -> R.string.w5e_purpose_read_text
        else -> null
    }

    fun text(context: Context, toolId: String): String? =
        forTool(toolId)?.let(context::getString)
}
