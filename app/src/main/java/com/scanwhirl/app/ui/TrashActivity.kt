package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityTrashBinding
import com.scanwhirl.app.databinding.ItemTrashBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.home.DocThumbBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M07 P1: Trash. Every document moved here is complete (pages, originals, PDF,
 * metadata, corrected OCR text and geometry) and can be restored whole, or deleted
 * permanently by the person. Entries older than the 30-day restore window are expired by
 * the repository at startup and whenever this screen resumes; nothing is purged sooner.
 *
 * Exports already saved to Documents/ScanWhirl are outside Trash: a move never
 * touches them, and permanent deletion only offers to remove copies the document
 * can prove it made (see [DeleteExports]).
 */
class TrashActivity : BaseActivity() {

    private lateinit var binding: ActivityTrashBinding
    private val repo by lazy { ScanRepository(this) }
    private val adapter = TrashAdapter()

    /** Items currently shown (empty until the first load completes). */
    private var items: List<ScanRepository.TrashedDocument> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The plain ink desk (like Home), painted once by the window, not twice.
        window.setBackgroundDrawableResource(R.color.rv_bg)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.trashList.layoutManager = LinearLayoutManager(this)
        binding.trashList.adapter = adapter
        binding.trashList.itemAnimator = com.scanwhirl.app.ui.kit.SwItemAnimator(this)
        binding.trashList.addItemDecoration(TrashGap())
        binding.btnTrashRetry.setOnClickListener { reload() }
    }

    override fun onResume() {
        super.onResume()
        // SH1 #5: expiry is not only a cold-start job. A screen left open past the 30-day
        // window must drop expired entries and redraw the rest when it comes back. Expiry
        // stays in the repository; this screen only asks it to run before it reloads.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repo.recoverTrash() } }
            reload()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_trash, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.action_empty_trash)
        item?.isEnabled = items.isNotEmpty()
        item?.isVisible = items.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_empty_trash -> {
            confirmEmptyTrash()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    internal fun reload() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.listTrash() } }
            result.onSuccess { list ->
                items = list
                adapter.submitList(list)
                showState(error = false)
            }.onFailure {
                items = emptyList()
                adapter.submitList(emptyList())
                showState(error = true)
            }
            invalidateOptionsMenu()
        }
    }

    private fun showState(error: Boolean) {
        val empty = items.isEmpty()
        binding.trashList.isVisible = !empty
        binding.trashEmpty.isVisible = empty
        binding.trashEmptyTitle.setText(if (error) R.string.trash_error_title else R.string.trash_empty_title)
        binding.trashEmptyBody.setText(if (error) R.string.trash_error_body else R.string.trash_empty_body)
        binding.btnTrashRetry.isVisible = error
        binding.trashCount.isVisible = !empty
        binding.trashCount.text = if (empty) null else {
            resources.getQuantityString(R.plurals.trash_count, items.size, items.size)
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun restore(item: ScanRepository.TrashedDocument) {
        lifecycleScope.launch {
            val result = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { repo.restoreFromTrash(item.key) }
            }
            result.onSuccess { doc ->
                TrashActions.queueIndexing(this@TrashActivity, repo, listOf(doc.id))
                Snackbar.make(binding.root, getString(R.string.trash_restored_one, doc.name), Snackbar.LENGTH_LONG)
                    .show()
            }.onFailure {
                Snackbar.make(binding.root, R.string.trash_restore_failed, Snackbar.LENGTH_LONG).show()
            }
            reload()
        }
    }

    private fun confirmDeleteForever(item: ScanRepository.TrashedDocument) {
        lifecycleScope.launch {
            val (copies, unproven) = withContext(Dispatchers.IO) {
                DeleteExports.copiesFor(repo, item) to DeleteExports.unprovenFor(repo, item)
            }
            val body = DeleteExports.dialogBody(this@TrashActivity, copies, unproven)
            com.scanwhirl.app.ui.kit.SwSheet.builder(this@TrashActivity)
                .setTitle(R.string.trash_delete_forever_title)
                .setMessage(getString(R.string.trash_delete_forever_message, item.document.name))
                .setContent(body)
                .setPrimary(R.string.trash_delete_forever) {
                    val removeCopies = body.findViewById<android.widget.CheckBox>(R.id.deleteCopiesBox)
                        ?.isChecked == true
                    deleteForever(listOf(item), if (removeCopies) copies else emptyList())
                }
                .setSecondary(android.R.string.cancel)
                .build()
                .show()
        }
    }

    private fun confirmEmptyTrash() {
        val all = items
        if (all.isEmpty()) return
        lifecycleScope.launch {
            val (copies, unproven) = withContext(Dispatchers.IO) {
                all.flatMap { DeleteExports.copiesFor(repo, it) }.distinctBy { it.uri } to
                    all.flatMap { DeleteExports.unprovenFor(repo, it) }.distinct()
            }
            val body = DeleteExports.dialogBody(this@TrashActivity, copies, unproven)
            com.scanwhirl.app.ui.kit.SwSheet.builder(this@TrashActivity)
                .setTitle(R.string.trash_empty_confirm_title)
                .setMessage(resources.getQuantityString(R.plurals.trash_empty_confirm_message, all.size, all.size))
                .setContent(body)
                .setPrimary(R.string.trash_empty_action) {
                    val removeCopies = body.findViewById<android.widget.CheckBox>(R.id.deleteCopiesBox)
                        ?.isChecked == true
                    deleteForever(all, if (removeCopies) copies else emptyList())
                }
                .setSecondary(android.R.string.cancel)
                .build()
                .show()
        }
    }

    /** Copies are re-checked against their fingerprints while their records still exist. */
    private fun deleteForever(targets: List<ScanRepository.TrashedDocument>, copies: List<DeleteExports.Copy>) {
        lifecycleScope.launch {
            val outcome = withContext(NonCancellable + Dispatchers.IO) {
                val (removed, kept) = if (copies.isEmpty()) 0 to 0 else {
                    DeleteExports.removeCopies(applicationContext, copies)
                }
                val failedKeys = mutableSetOf<String>()
                for (t in targets) {
                    try {
                        repo.deleteFromTrash(t.key)
                    } catch (_: Exception) {
                        failedKeys += t.key
                    }
                }
                // UI-W8: report what the list really shows. A removal can die after the entry
                // was renamed aside: it is gone from Trash, but its bytes are cleaned at the
                // next start. A removal can also refuse before the rename: it is still listed.
                val listed = runCatching { repo.listTrash().map { it.key }.toSet() }.getOrNull()
                DeleteOutcome(removed, kept, failedKeys, listed)
            }
            val msg = deleteMessage(targets, outcome)
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            reload()
        }
    }

    private class DeleteOutcome(
        val copiesRemoved: Int,
        val copiesKept: Int,
        val failedKeys: Set<String>,
        /** Keys still listed after the attempt; null when the list could not be read. */
        val stillListed: Set<String>?
    )

    /**
     * UI-W8: the result line is assembled from what actually happened, not from "some failed".
     * A partial Empty Trash says how many left, how many are still in Trash, and how many
     * removals still finish at the next start. Export-copy outcomes are never dropped.
     */
    private fun deleteMessage(
        targets: List<ScanRepository.TrashedDocument>,
        outcome: DeleteOutcome
    ): CharSequence {
        val failed = outcome.failedKeys.size
        val stillInTrash = if (outcome.stillListed == null) failed else {
            outcome.failedKeys.count { it in outcome.stillListed }
        }
        // A thrown removal that is no longer listed was renamed aside: its cleanup is pending.
        val cleanupPending = failed - stillInTrash
        val deleted = targets.size - failed
        val parts = mutableListOf<CharSequence>()
        when {
            failed == 0 -> parts += resources.getQuantityString(R.plurals.trash_deleted, targets.size, targets.size)
            deleted == 0 -> parts += getString(R.string.trash_delete_failed)
            else -> {
                parts += getString(R.string.trash_deleted_partial, deleted, targets.size)
                if (stillInTrash > 0) {
                    parts += resources.getQuantityString(
                        R.plurals.trash_still_in_trash, stillInTrash, stillInTrash
                    )
                }
                if (cleanupPending > 0) {
                    parts += resources.getQuantityString(
                        R.plurals.trash_cleanup_pending, cleanupPending, cleanupPending
                    )
                }
            }
        }
        if (outcome.copiesRemoved > 0 || outcome.copiesKept > 0) {
            parts += DeleteExports.message(this, outcome.copiesRemoved, outcome.copiesKept)
        }
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    private inner class TrashAdapter :
        ListAdapter<ScanRepository.TrashedDocument, TrashVH>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashVH =
            TrashVH(ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: TrashVH, position: Int) = holder.bind(getItem(position))

        override fun onViewRecycled(holder: TrashVH) = holder.recycle()
    }

    private inner class TrashVH(private val b: ItemTrashBinding) : RecyclerView.ViewHolder(b.root) {

        fun recycle() = DocThumbBinder.unbind(b.trashThumb, b.trashGlyph)

        fun bind(item: ScanRepository.TrashedDocument) {
            val doc = item.document
            // PM-2.2c: a version moved aside by "Replace original" says so in the row title.
            b.trashName.text = if (item.previousToken != null) {
                getString(R.string.trash_previous_version, doc.name)
            } else {
                doc.name
            }
            val pages = resources.getQuantityString(R.plurals.trash_pages, doc.pageCount, doc.pageCount)
            val whenText = DateUtils.formatDateTime(
                this@TrashActivity, item.trashedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            )
            b.trashMeta.text = getString(R.string.trash_row_meta, pages, whenText)
            DocThumbBinder.bind(b.trashThumb, b.trashGlyph, doc)
            b.btnTrashRestore.setOnClickListener { restore(item) }
            b.btnTrashDelete.setOnClickListener { confirmDeleteForever(item) }
            b.btnTrashRestore.contentDescription = getString(R.string.trash_restore_named, doc.name)
            b.btnTrashDelete.contentDescription = getString(R.string.trash_delete_forever_named, doc.name)

            // At large text the two labelled actions stack instead of clipping.
            val stacked = resources.configuration.fontScale >= MainActivity.LARGE_TEXT_SCALE
            b.trashActions.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            listOf(b.btnTrashDelete, b.btnTrashRestore).forEach { v ->
                (v.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                    marginStart = if (stacked || v === b.btnTrashDelete) 0 else (8 * resources.displayMetrics.density).toInt()
                }
            }

            // TalkBack: one row announcement plus both actions in the actions menu.
            b.trashRow.contentDescription = getString(R.string.trash_row_description, doc.name, b.trashMeta.text)
            ViewCompat.setAccessibilityDelegate(b.trashRow, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.a11y_trash_restore, getString(R.string.trash_restore))
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.a11y_trash_delete, getString(R.string.trash_delete_forever))
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                    R.id.a11y_trash_restore -> { restore(item); true }
                    R.id.a11y_trash_delete -> { confirmDeleteForever(item); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    /** 6dp between rows. */
    private class TrashGap : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.top = (6 * view.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, TrashActivity::class.java)

        private val DIFF = object : DiffUtil.ItemCallback<ScanRepository.TrashedDocument>() {
            override fun areItemsTheSame(
                a: ScanRepository.TrashedDocument,
                b: ScanRepository.TrashedDocument
            ) = a.key == b.key

            override fun areContentsTheSame(
                a: ScanRepository.TrashedDocument,
                b: ScanRepository.TrashedDocument
            ) = a == b
        }
    }
}
