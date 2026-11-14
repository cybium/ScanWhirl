package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.FlowUtil
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_DRAW
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_HIGHLIGHT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_PAGE_NUMBERS
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_REDACT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_SIGN
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_STAMP
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_TEXT
import com.scanwhirl.app.ui.stamp.StampEditorActivity.Companion.TOOL_WATERMARK
import com.scanwhirl.app.util.ProGate

/**
 * L5 Phase B — the Tools-hub entry point for the eight Edit & sign tools.
 *
 * The order is always the same: Pro gate → the existing library chooser
 * ([FlowUtil.pickScan], the same one Merge and Compress use) → [StampEditorActivity]
 * already armed for the tapped tool. Nothing is picked for a Free build, and a protected
 * document is never offered (the editor cannot re-encrypt a copy).
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
        if (!ProGate.isPro(activity)) {
            showPro(activity)
            return
        }
        val docs = ScanRepository(activity).listDocuments()
            .filter { !it.isLocked && !it.isVaulted }
        if (docs.isEmpty()) {
            Toast.makeText(activity, R.string.stamp_no_documents, Toast.LENGTH_LONG).show()
            return
        }
        val host = activity as? AppCompatActivity ?: return
        FlowUtil.pickScan(host, R.string.stamp_choose_document, docs) { doc ->
            activity.startActivity(StampEditorActivity.intent(activity, doc.id, tool))
        }
    }

    /** Kept for callers that only have a [Context] (they must be able to start an Activity). */
    fun openFrom(context: Context, toolId: String) {
        if (context is Activity) open(context, toolId)
    }

    /** Free build: name the group honestly; Billing is not switched on yet (design §3). */
    private fun showPro(activity: Activity) {
        SwSheet.builder(activity)
            .setTitle(R.string.pro_suite_title)
            .setMessage(R.string.stamp_pro_message)
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }
}
