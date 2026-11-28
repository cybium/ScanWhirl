package com.scanwhirl.app.ui.result

import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * The hand-off to the single result surface. Only produced outputs belong here, never inputs.
 * Identity is captured by the producer on IO; this contract grants no new deletion authority.
 * Destination receipts are deliberately NOT producer supplied: the result records verified writes.
 */
data class ResultSpec(
    val toolId: String,
    val docIds: List<String> = emptyList(),
    val files: List<File> = emptyList(),
    val mime: String? = null,
    val note: String? = null,
    val targetBytes: Long? = null,
    val beforeBytes: Long? = null,
    val receiptSourceId: String? = null,
    val outputIdentity: String? = null,
    val sourceKept: Boolean = false,
    val compressTargetMissed: Boolean = false,
    val compressGrew: Boolean = false,
    val outcomeClass: String? = null,
    val noStrongerPreset: Boolean = false,
    /** A retained workspace's explicit intent. CLEAR_TOP/SINGLE_TOP resumes its exact live inputs. */
    val adjustIntent: Intent? = null,
    /** Measured facts, never requested dimensions. The result remeasures available output bytes. */
    val facts: Facts? = null
) {
    data class Facts(val bytes: Long, val pages: Int? = null, val width: Int? = null, val height: Int? = null)
    enum class Kind { IMAGE, PDF, FILE }
    enum class WhereState { NOT_SAVED, LIBRARY }
    enum class Action { SAVE_TO_PHONE, SHARE, OPEN, SHOW_IN_LIBRARY, ADJUST, REMOVE_COPY }

    val kind: Kind get() = when {
        docIds.isNotEmpty() || mime == "application/pdf" -> Kind.PDF
        mime?.startsWith("image/") == true ||
            files.firstOrNull()?.extension?.lowercase() in setOf("jpg", "jpeg", "png") -> Kind.IMAGE
        else -> Kind.FILE
    }
    val whereState: WhereState get() = if (docIds.isEmpty()) WhereState.NOT_SAVED else WhereState.LIBRARY
    val primary: Action get() = if (kind == Kind.PDF) Action.SHARE else Action.SAVE_TO_PHONE
    val actions: Set<Action> get() = buildSet {
        add(Action.OPEN)
        add(Action.SHARE)
        if (docIds.isNotEmpty()) add(Action.SHOW_IN_LIBRARY)
        if (adjustIntent != null) add(Action.ADJUST)
        if (outputIdentity != null) add(Action.REMOVE_COPY)
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString("tool_id", toolId)
        putStringArrayList("doc_ids", ArrayList(docIds))
        putStringArrayList("files", ArrayList(files.map { it.path }))
        putString("mime", mime)
        putString("note", note)
        targetBytes?.let { putLong("target_bytes", it) }
        beforeBytes?.let { putLong("before_bytes", it) }
        putString("l3_receipt_source_id", receiptSourceId)
        putString("l3_output_identity", outputIdentity)
        putBoolean("source_kept", sourceKept)
        putBoolean("compress_missed", compressTargetMissed)
        putBoolean("compress_grew", compressGrew)
        putString("rs_outcome_class", outcomeClass)
        putBoolean("rs_no_stronger_preset", noStrongerPreset)
        putParcelable("result_adjust", adjustIntent?.let { Intent(it) })
        facts?.let {
            putLong("result_bytes", it.bytes)
            it.pages?.let { n -> putInt("result_pages", n) }
            it.width?.let { n -> putInt("result_width", n) }
            it.height?.let { n -> putInt("result_height", n) }
        }
    }

    companion object {
        /** Also accepts the shipped extras, so old callers and durable receipts remain compatible. */
        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent): ResultSpec {
            val b = intent.extras ?: Bundle()
            return ResultSpec(
                toolId = b.getString("tool_id").orEmpty(),
                docIds = b.getStringArrayList("doc_ids").orEmpty(),
                files = b.getStringArrayList("files").orEmpty().map(::File),
                mime = b.getString("mime"), note = b.getString("note"),
                targetBytes = b.getLong("target_bytes").takeIf { it > 0 },
                beforeBytes = b.getLong("before_bytes").takeIf { it > 0 },
                receiptSourceId = b.getString("l3_receipt_source_id"),
                outputIdentity = b.getString("l3_output_identity"),
                sourceKept = b.getBoolean("source_kept"),
                compressTargetMissed = b.getBoolean("compress_missed"),
                compressGrew = b.getBoolean("compress_grew"),
                outcomeClass = b.getString("rs_outcome_class"),
                noStrongerPreset = b.getBoolean("rs_no_stronger_preset"),
                adjustIntent = b.getParcelable("result_adjust"),
                facts = if (b.containsKey("result_bytes")) Facts(
                    b.getLong("result_bytes"),
                    b.getInt("result_pages").takeIf { it > 0 },
                    b.getInt("result_width").takeIf { it > 0 },
                    b.getInt("result_height").takeIf { it > 0 }
                ) else null
            )
        }
    }
}
