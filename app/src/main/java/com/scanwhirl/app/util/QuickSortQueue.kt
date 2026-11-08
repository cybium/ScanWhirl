package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument

/**
 * test26 T4.4 — the Quick Sort queue (plan §4.8): every document whose
 * category is [CategoryRules.UNSORTED] or [CategoryRules.OTHER], in the
 * library's own order.
 *
 * Pure logic — the queue never touches storage. The UI persists each [pick]
 * through `ScanRepository.setCategory` the moment it comes back, and a
 * mid-queue close (Rev 4 §3D) simply drops the queue: whatever was never
 * picked or skipped stays unsorted and the Home nudge keeps counting it.
 */
class QuickSortQueue private constructor(
    private val pending: ArrayDeque<ScanDocument>,
    val total: Int
) {

    /** The document to show now, or null once every one is decided. */
    fun current(): ScanDocument? = pending.firstOrNull()

    /**
     * Categorize the current document: it leaves the queue and is handed
     * back so the caller can save it immediately. Null when the queue is
     * already empty. Only real categories may be picked ([CategoryRules.ALL]).
     */
    fun pick(category: String): ScanDocument? {
        require(category in CategoryRules.ALL) { "not a category: $category" }
        return pending.removeFirstOrNull()
    }

    /** Leave the current document exactly as it is and move on. */
    fun skip(): ScanDocument? = pending.removeFirstOrNull()

    /** How many documents still wait for a decision. */
    fun remaining(): Int = pending.size

    /** How many are already decided (picked + skipped). */
    fun done(): Int = total - remaining()

    /** "2 of 7" — [Progress.position] is the 1-based number on screen. */
    fun progress(): Progress = Progress(done = done(), total = total)

    /** `position` is only meaningful while [current] is not null. */
    data class Progress(val done: Int, val total: Int) {
        val position: Int get() = done + 1
    }

    companion object {
        /**
         * Build the queue from the library: Unsorted + Other join, every
         * deliberately categorized document stays out (order preserved).
         */
        fun from(docs: List<ScanDocument>): QuickSortQueue {
            val queue = ArrayDeque(docs.filter {
                it.category == CategoryRules.UNSORTED || it.category == CategoryRules.OTHER
            })
            return QuickSortQueue(queue, queue.size)
        }
    }
}
