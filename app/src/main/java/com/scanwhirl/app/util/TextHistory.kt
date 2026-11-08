package com.scanwhirl.app.util

/**
 * Undo/redo history for a single page of OCR text.
 *
 * Stacks hold "text before" snapshots. `record` ignores `old == new`, clears
 * the redo stack, and pushes `old` — unless `kind == TYPING` and the previous
 * record was also `TYPING` within 1,000 ms (then the burst shares the earlier
 * snapshot). `ACTION` always pushes. After any `undo`/`redo` the next
 * `TYPING` starts a new group.
 *
 * Eviction drops the oldest entries when the stack exceeds [maxEntries] or
 * the summed length exceeds [maxChars].
 */
class TextHistory(
    private val maxEntries: Int = 50,
    private val maxChars: Int = 200_000,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Kind { TYPING, ACTION }

    /** "Text before" snapshots, newest last. Undo pops from the end. */
    private val undoStack = ArrayList<String>()
    /** "Text after" snapshots, newest last. Redo pops from the end. */
    private val redoStack = ArrayList<String>()
    /** Timestamp of the last TYPING record (for burst grouping). */
    private var lastTypingTime: Long = 0L

    /** Summed length of all entries across both stacks. */
    private var totalChars: Int = 0

    /** Can undo? */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Can redo? */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** For tests: number of entries on the undo stack. */
    internal val undoStackSize: Int get() = undoStack.size

    /** For tests: summed length of all entries across both stacks. */
    internal val totalCharsSize: Int get() = totalChars

    /**
     * Record a transition from [old] to [new]. Ignored when `old == new`.
     * Clears the redo stack. Pushes `old` onto the undo stack (unless it's a
     * TYPING burst within 1,000 ms of the previous TYPING record).
     */
    fun record(old: String, new: String, kind: Kind) {
        if (old == new) return
        redoStack.clear()
        totalChars -= redoStack.sumOf { it.length }

        when (kind) {
            Kind.TYPING -> {
                val now = clock()
                val isBurst = (now - lastTypingTime) <= 1000L
                lastTypingTime = now
                if (!isBurst || undoStack.isEmpty()) {
                    pushUndo(old)
                }
                // If it's a burst, we keep the earlier snapshot (old was already
                // pushed or is about to be superseded by the next record).
            }
            Kind.ACTION -> {
                lastTypingTime = 0L
                pushUndo(old)
            }
        }
    }

    /** Undo: return the previous text (the snapshot before the last change), or null. */
    fun undo(current: String): String? {
        val previous = undoStack.removeAtOrNull(undoStack.size - 1) ?: return null
        totalChars -= previous.length
        redoStack.add(current)
        totalChars += current.length
        evict()
        lastTypingTime = 0L // next typing starts a new group
        return previous
    }

    /** Redo: return the next text (the snapshot after the last undone change), or null. */
    fun redo(current: String): String? {
        val next = redoStack.removeAtOrNull(redoStack.size - 1) ?: return null
        totalChars -= next.length
        undoStack.add(current)
        totalChars += current.length
        evict()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        totalChars = 0
        lastTypingTime = 0L
    }

    private fun pushUndo(text: String) {
        undoStack.add(text)
        totalChars += text.length
        evict()
    }

    private fun evict() {
        while (undoStack.size > maxEntries || totalChars > maxChars) {
            if (undoStack.isEmpty()) break
            val oldest = undoStack.removeAt(0)
            totalChars -= oldest.length
        }
        while (redoStack.size > maxEntries || totalChars > maxChars) {
            if (redoStack.isEmpty()) break
            val oldest = redoStack.removeAt(0)
            totalChars -= oldest.length
        }
    }

    private fun <T> ArrayList<T>.removeAtOrNull(index: Int): T? =
        if (index >= 0 && index < size) removeAt(index) else null
}
