package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_DRAW
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_HIGHLIGHT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_PAGE_NUMBERS
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_REDACT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_SIGN
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_STAMP
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_TEXT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_WATERMARK

/**
 * L5 Phase B — the Tools-hub entry point for the eight Edit & sign tools.
 *
 * It lists unlocked library documents in a kit [SwSheet] and opens [StampEditorActivity]
 * already armed for the tapped tool. The Tools hub routes its eight ids here.
 */
object StampToolLauncher {

    /** Tool ids from `ToolRegistry` to [StampEditorActivity]'s tool extra. */
    fun toolFor(id: String): String? = when (id.trim().lowercase()) {
        "sign" -> TOOL_SIGN
        "add_text" -> TOOL_TEXT
        "draw" -> TOOL_DRAW
        "highlight" -> TOOL_HIGHLIGHT
        "watermark" -> TOOL_WATERMARK
        "page_numbers" -> TOOL_PAGE_NUMBERS
        "stamp" -> TOOL_STAMP
        "redact" -> TOOL_REDACT
        else -> null
    }

    fun open(activity: Activity, toolId: String) {
        val tool = toolFor(toolId) ?: return
        val docs = ScanRepository(activity).listDocuments()
            .filter { !it.isLocked && !it.isVaulted }
        if (docs.isEmpty()) {
            Toast.makeText(activity, R.string.stamp_no_documents, Toast.LENGTH_LONG).show()
            return
        }

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.stampDocumentRows
        }
        lateinit var sheet: SwSheet
        docs.forEach { doc ->
            list.addView(
                StampPrompts.documentRow(activity, doc.name, doc.pageCount) {
                    sheet.dismiss()
                    activity.startActivity(StampEditorActivity.intent(activity, doc.id, tool))
                }
            )
        }
        sheet = SwSheet.builder(activity)
            .setTitle(R.string.stamp_choose_document)
            .setContent(list)
            .build()
        sheet.show()
    }

    /** Kept for callers that only have a [Context] (they must be able to start an Activity). */
    fun openFrom(context: Context, toolId: String) {
        if (context is Activity) open(context, toolId)
    }
}
