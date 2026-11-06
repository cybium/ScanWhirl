package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument

/**
 * One place for "does this document match the search box?" so the globe
 * home and the Shelf list can never disagree.
 *
 * Behaviour (identical to the original Shelf search):
 * - name matches always count (Free and Pro);
 * - when the name search finds nothing, Pro also searches the stored OCR
 *   text; Free does not (OCR is a Pro extra);
 * - when any name matched, OCR results are not mixed in.
 *
 * Vaulted documents are excluded: their names must never surface before the
 * vault PIN is entered.
 */
object SearchMatcher {

    fun byName(query: String, docs: List<ScanDocument>): List<ScanDocument> =
        docs.filter { it.name.contains(query, true) }

    fun ocrMatches(
        query: String,
        docs: List<ScanDocument>,
        ocr: (String) -> Map<String, String>
    ): List<ScanDocument> = docs.filter { doc ->
        ocr(doc.id).values.any { it.contains(query, ignoreCase = true) }
    }

    /**
     * The documents to show for [query]. [ocrLookup] is only consulted when
     * [ocrEnabled] (Pro) and the name search came up empty.
     *
     * [includeVaulted] is for the vault screen itself, where the list *is*
     * the vault contents (the PIN has already been entered).
     */
    fun match(
        query: String,
        docs: List<ScanDocument>,
        ocrEnabled: Boolean,
        includeVaulted: Boolean = false,
        ocrLookup: (String) -> Map<String, String> = { emptyMap() }
    ): Pair<List<ScanDocument>, List<ScanDocument>> {
        val visible = if (includeVaulted) docs else docs.filter { !it.isVaulted }
        val byName = byName(query, visible)
        val ocr = if (!ocrEnabled || query.isBlank() || byName.isNotEmpty()) {
            emptyList()
        } else {
            ocrMatches(query, visible, ocrLookup)
        }
        val shown = if (byName.isEmpty() && ocr.isNotEmpty()) ocr else byName
        return shown to ocr
    }
}
