package com.scanwhirl.app.ui.home

/**
 * M06: "show me the document I just saved". Review records the new document's
 * id when a save is verified; Home consumes it on its next fresh library load and
 * scrolls the row into view. Home never clears a search or category filter on
 * its own: when the filter hides the document it says so and offers Show all.
 *
 * In-memory on purpose. After a process death, Home simply opens as usual.
 */
object HomeReveal {

    @Volatile private var pending: String? = null

    fun request(docId: String) {
        pending = docId
    }

    /** The pending id, cleared. */
    fun take(): String? {
        val id = pending
        pending = null
        return id
    }

    internal fun peek(): String? = pending
}
