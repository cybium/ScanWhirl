package com.scanwhirl.app.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityPdfViewerBinding
import com.scanwhirl.app.ui.home.PageHitViews
import com.scanwhirl.app.util.PageTextMatches
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Retrieval slice 5 — Find inside the ScanWhirl reader, over the document's
 * STORED per-page OCR text only.
 *
 * Every search re-reads the current page order and `ocr.txt` off the main thread
 * (so results never outlive an edit or a finished indexing run), refuses to
 * point at pages when the page order and the opened PDF disagree, and shows its
 * evidence as text: an excerpt with the genuine matches emphasised. Nothing is
 * painted on the page image. External PDFs get an explanation, not a search box
 * that pretends to work.
 */
internal class ReaderFind(
    private val context: Context,
    private val b: ActivityPdfViewerBinding,
    /** Null for an external PDF (path or content Uri): there is no stored text. */
    private val docId: String?,
    /** Pages in the OPENED PDF; the stored page order must have the same count. */
    private val pdfPageCount: Int,
    private val currentPage: () -> Int,
    private val goToPage: (Int) -> Unit,
    /** Reads the current page order + stored text for a document. Runs on a worker. */
    private val load: (String) -> Snapshot?
) {

    /** What one search reads from disk. */
    data class Snapshot(val pageKeys: List<String>, val ocr: Map<String, String>, val pending: Boolean)

    /** One navigable match: a page in the current order and a range in its stored text. */
    data class Match(val pageIndex: Int, val pageKey: String, val range: IntRange)

    private sealed class Outcome {
        data class Unavailable(@StringRes val message: Int) : Outcome()
        data class Ready(
            val query: String,
            val result: PageTextMatches.Result,
            val texts: Map<String, String>,
            val pending: Boolean
        ) : Outcome()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "reader-find").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var destroyed = false
    private var outcome: Outcome? = null

    /** Debounce for typing; tests set 0. */
    var debounceMs = 150L

    var matches: List<Match> = emptyList()
        private set
    var index = -1
        private set
    val isOpen: Boolean get() = b.findPanel.isVisible
    val query: String get() = b.findInput.text?.toString()?.trim().orEmpty()

    /** On the next result: select the first match on this page (Home's "Page N"). */
    private var landOnPage: Int? = null
    /** On the next result: restore this match index (recreation). */
    private var restoreIndex: Int? = null

    private val runSearch = Runnable { search() }

    fun bind() {
        b.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isOpen) return
                main.removeCallbacks(runSearch)
                if (debounceMs <= 0) search() else main.postDelayed(runSearch, debounceMs)
            }
        })
        b.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(runSearch)
                search()
                hideKeyboard()
                true
            } else {
                false
            }
        }
        b.btnFindMore.setOnClickListener { showFindMore() }
        b.btnFindClose.setOnClickListener { close() }
        b.btnFindPrev.setOnClickListener { step(-1) }
        b.btnFindNext.setOnClickListener { step(+1) }
        b.findExcerpt.setOnClickListener { matches.getOrNull(index)?.let { goToPage(it.pageIndex) } }
    }

    /**
     * Shows the panel. [prefill] sets the query (from Home or a restore);
     * [focus] raises the keyboard (the user tapped Find).
     */
    fun open(prefill: String? = null, focus: Boolean = false, landOn: Int? = null, restore: Int? = null) {
        landOnPage = landOn
        restoreIndex = restore
        b.findPanel.isVisible = true
        if (docId == null) {
            show(Outcome.Unavailable(R.string.v3_reader_find_external))
            return
        }
        b.findInput.isEnabled = true
        if (prefill != null && prefill != b.findInput.text?.toString()) {
            b.findInput.setText(prefill)
            b.findInput.setSelection(b.findInput.text?.length ?: 0)
        }
        main.removeCallbacks(runSearch)
        search()
        if (focus) {
            b.findInput.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(b.findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        main.removeCallbacks(runSearch)
        generation++
        hideKeyboard()
        b.findPanel.isVisible = false
        b.findInput.setText("")
        matches = emptyList()
        index = -1
        outcome = null
    }

    fun shutdown() {
        destroyed = true
        main.removeCallbacks(runSearch)
        executor.shutdownNow()
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.findInput.windowToken, 0)
    }

    private fun search() {
        val id = docId ?: return
        val q = query
        val gen = ++generation
        executor.execute {
            val result = try {
                val snap = load(id)
                when {
                    snap == null -> Outcome.Unavailable(R.string.doc_not_found)
                    // PDF-only document: no page images, so no stored page text to search.
                    snap.pageKeys.isEmpty() -> Outcome.Unavailable(R.string.v3_reader_find_no_text)
                    snap.pageKeys.size != pdfPageCount -> Outcome.Unavailable(R.string.v3_reader_find_out_of_step)
                    else -> Outcome.Ready(
                        q, PageTextMatches.find(q, snap.pageKeys, snap.ocr), snap.ocr, snap.pending
                    )
                }
            } catch (_: Exception) {
                Outcome.Unavailable(R.string.v3_reader_find_no_text)
            }
            main.post { if (!destroyed && gen == generation && isOpen) show(result) }
        }
    }

    private fun show(result: Outcome) {
        outcome = result
        when (result) {
            is Outcome.Unavailable -> {
                matches = emptyList()
                index = -1
                b.findInput.isEnabled = docId != null && result.message != R.string.v3_reader_find_out_of_step
            }
            is Outcome.Ready -> {
                matches = result.result.hits.flatMap { hit ->
                    hit.ranges.map { Match(hit.pageIndex, hit.pageKey, it) }
                }.take(MAX_MATCHES)
                val restore = restoreIndex
                val land = landOnPage
                restoreIndex = null
                landOnPage = null
                index = when {
                    matches.isEmpty() -> -1
                    restore != null -> restore.coerceIn(0, matches.size - 1)
                    land != null -> matches.indexOfFirst { it.pageIndex == land }
                        .takeIf { it >= 0 } ?: nearestFromCurrent()
                    else -> nearestFromCurrent()
                }
                // A new search moves to its match; a landing/restore is already on its page.
                if (restore == null && land == null) {
                    matches.getOrNull(index)?.let { if (it.pageIndex != currentPage()) goToPage(it.pageIndex) }
                }
            }
        }
        render()
    }

    private fun nearestFromCurrent(): Int {
        val page = currentPage()
        return matches.indexOfFirst { it.pageIndex >= page }.takeIf { it >= 0 } ?: 0
    }

    private fun step(delta: Int) {
        if (matches.isEmpty()) return
        index = ((index + delta) % matches.size + matches.size) % matches.size
        val m = matches[index]
        if (m.pageIndex != currentPage()) goToPage(m.pageIndex)
        render()
    }

    private fun render() {
        val res = context.resources
        val lines = ArrayList<String>()
        var excerpt: CharSequence? = null
        var excerptDesc: String? = null
        when (val o = outcome) {
            null -> Unit
            is Outcome.Unavailable -> lines += res.getString(o.message)
            is Outcome.Ready -> {
                val cov = o.result.coverage
                when {
                    o.query.isBlank() -> lines += res.getString(
                        if (cov.isEmpty && !o.pending) R.string.v3_reader_find_no_text else R.string.reader_find_type
                    )
                    cov.isEmpty && !o.pending -> lines += res.getString(R.string.v3_reader_find_no_text)
                    matches.isEmpty() -> lines += res.getString(R.string.reader_find_none, o.query)
                    else -> {
                        val m = matches[index]
                        lines += res.getString(R.string.reader_find_position, index + 1, matches.size, m.pageIndex + 1)
                        val text = o.texts[m.pageKey].orEmpty()
                        val ex = PageTextMatches.excerpt(text, m.range, o.result.terms)
                        val label = res.getString(R.string.reader_find_excerpt_page, m.pageIndex + 1)
                        excerpt = SpannableStringBuilder().apply {
                            append(label)
                            setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(
                                ForegroundColorSpan(ContextCompat.getColor(context, R.color.rv_ink)),
                                0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            append("  ")
                            append(PageHitViews.styled(context, ex))
                        }
                        excerptDesc = res.getString(R.string.reader_find_excerpt_desc, m.pageIndex + 1, ex.text)
                    }
                }
                if (o.pending) lines += res.getString(R.string.v3_reader_find_pending)
                else if (cov.isPartial) lines += res.getString(
                    R.string.v3_reader_find_partial, cov.indexedPages, cov.totalPages
                )
            }
        }
        b.findStatus.text = lines.joinToString("\n")
        b.findExcerpt.isVisible = excerpt != null
        b.findExcerpt.text = excerpt
        b.findExcerpt.contentDescription = excerptDesc
        b.findNav.isVisible = matches.isNotEmpty()
        val canStep = matches.size > 1
        for (v in listOf(b.btnFindPrev, b.btnFindNext)) {
            v.isEnabled = canStep
            v.alpha = if (canStep) 1f else 0.45f
        }
        // V3: the explanation is no longer printed under every search; More owns it.
        b.findNote.isVisible = false
    }

    /** V3: the full Find explanation, available on demand instead of permanently. */
    private fun showFindMore() {
        com.scanwhirl.app.ui.kit.SwSheet.note(
            context,
            context.getString(R.string.v3_find_more_a11y),
            context.getString(R.string.v3_reader_find_note_more)
        ).show()
    }

    companion object {
        /** Navigable matches kept for one search (each page keeps at most 200). */
        const val MAX_MATCHES = 1000
    }
}
