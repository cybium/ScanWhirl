package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.Durable
import java.io.File

/**
 * V2T T1 — durable, single "last result" receipt.
 *
 * The result screen writes this as soon as it is created, and again whenever a
 * verified Documents copy is saved. After process death the same outputs can be
 * reopened with [ToolResultActivity.intentLastResult]; the receipt is a fact
 * record, never a storage instruction. It never reintroduces Replace/Keep.
 */
class V2tResultReceiptStore(private val context: Context) {

    data class Receipt(
        val toolId: String,
        val docIds: List<String>,
        val files: List<String>,
        val mime: String?,
        val note: String?,
        val targetBytes: Long?,
        val beforeBytes: Long?,
        val outputIdentity: String?,
        val receiptSourceId: String?,
        val sourceKept: Boolean,
        val chosenLocationName: String?,
        val documentsExistingName: String?,
        val undone: Boolean,
        val savedAt: Long,
        /** Lane RS: SMALLER / NOT_SMALLER / TARGET_MISSED. Absent in older receipts. */
        val outcomeClass: String? = null
    )

    private fun file(): File = File(context.filesDir, "v2t_last_result.receipt")

    fun save(receipt: Receipt) {
        Durable.atomicWriteText(file(), buildString {
            append(HEADER).append('\n')
            append("toolId=").append(enc(receipt.toolId)).append('\n')
            append("docIds=").append(receipt.docIds.joinToString(",") { enc(it) }).append('\n')
            append("files=").append(receipt.files.joinToString(",") { enc(it) }).append('\n')
            append("mime=").append(enc(receipt.mime)).append('\n')
            append("note=").append(enc(receipt.note)).append('\n')
            append("targetBytes=").append(receipt.targetBytes ?: -1L).append('\n')
            append("beforeBytes=").append(receipt.beforeBytes ?: -1L).append('\n')
            append("outputIdentity=").append(enc(receipt.outputIdentity)).append('\n')
            append("receiptSourceId=").append(enc(receipt.receiptSourceId)).append('\n')
            append("sourceKept=").append(receipt.sourceKept).append('\n')
            append("chosenLocationName=").append(enc(receipt.chosenLocationName)).append('\n')
            append("documentsExistingName=").append(enc(receipt.documentsExistingName)).append('\n')
            append("undone=").append(receipt.undone).append('\n')
            append("savedAt=").append(receipt.savedAt).append('\n')
            append("outcomeClass=").append(enc(receipt.outcomeClass)).append('\n')
        })
    }

    fun load(): Receipt? {
        val f = file()
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != HEADER) return null
        val v = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at > 0) v[line.substring(0, at)] = line.substring(at + 1)
        }
        return Receipt(
            toolId = dec(v["toolId"]).orEmpty(),
            docIds = v["docIds"].orEmpty().split(',').filter { it.isNotEmpty() }
                .map { dec(it).orEmpty() },
            files = v["files"].orEmpty().split(',').filter { it.isNotEmpty() }
                .map { dec(it).orEmpty() },
            mime = dec(v["mime"]),
            note = dec(v["note"]),
            targetBytes = v["targetBytes"]?.toLongOrNull()?.takeIf { it > 0 },
            beforeBytes = v["beforeBytes"]?.toLongOrNull()?.takeIf { it > 0 },
            outputIdentity = dec(v["outputIdentity"]),
            receiptSourceId = dec(v["receiptSourceId"]),
            sourceKept = v["sourceKept"]?.toBooleanStrictOrNull() ?: false,
            chosenLocationName = dec(v["chosenLocationName"]),
            documentsExistingName = dec(v["documentsExistingName"]),
            undone = v["undone"]?.toBooleanStrictOrNull() ?: false,
            savedAt = v["savedAt"]?.toLongOrNull() ?: 0L,
            outcomeClass = dec(v["outcomeClass"])
        )
    }

    fun clear() {
        file().delete()
    }

    // The two lists are encoded as a whole, so their separator must be escaped too.
    private fun enc(value: String?): String = value.orEmpty()
        .replace("%", "%25").replace(",", "%2C").replace("=", "%3D").replace("\n", "%0A")

    private fun dec(value: String?): String? = value
        ?.replace("%0A", "\n")
        ?.replace("%3D", "=")
        ?.replace("%2C", ",")
        ?.replace("%25", "%")

    private companion object {
        const val HEADER = "scanwhirl-v2t-result-v1"
    }
}
