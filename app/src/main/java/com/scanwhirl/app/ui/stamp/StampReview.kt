package com.scanwhirl.app.ui.stamp

/** A draft review grants no export authority. The repository still verifies encoded burn-in. */
internal class StampReview(private val revision: Long, val pages: Set<Int>) {
    var hidden = true
        private set
    private val visited = mutableSetOf<Int>()
    var acknowledged = false

    fun showOriginal() { hidden = false; acknowledged = false }
    fun showHidden() { hidden = true }
    fun visit(page: Int) { if (hidden && page in pages) visited += page }
    fun allVisited(): Boolean = visited.containsAll(pages)
    fun canSave(currentRevision: Long): Boolean =
        pages.isNotEmpty() && hidden && acknowledged && allVisited() && currentRevision == revision
}
