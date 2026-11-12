package com.scanwhirl.app.ui.home

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.PageTextMatches

/** Where a page link in a Home row goes. [pageIndex] is in the document's current order. */
data class PageLink(
    val docId: String,
    val revision: Long,
    val pageIndex: Int,
    val pageKey: String,
    val query: String
)

/**
 * Retrieval slice 5: binds a row's [RowMatch] into its page-link strips.
 *
 * Child views are inflated once per row view and reused, so a bind never
 * touches the disk or allocates more than the strips it needs.
 */
object PageHitViews {

    /**
     * @param match already checked by the caller to belong to [doc] at its current revision.
     * @return true when page links are shown (the caller then hides the document snippet).
     */
    fun bind(
        container: LinearLayout,
        scope: TextView,
        note: TextView,
        doc: ScanDocument,
        match: RowMatch?,
        enabled: Boolean,
        externalViewer: Boolean,
        onOpen: (ScanDocument, PageLink) -> Unit
    ): Boolean {
        // A locked document never shows page evidence, even if a caller hands one in.
        val usable = match.takeIf { !doc.isLocked }
        val showPages = enabled && usable != null && usable.isPageEvidence
        scope.isVisible = enabled && usable != null && !usable.isPageEvidence
        note.isVisible = showPages && externalViewer
        container.isVisible = showPages
        if (!showPages || usable == null) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.setOnClickListener(null)
                if (doc.isLocked) {
                    // Hidden strips must not keep a previous row's text either.
                    child.findViewById<TextView>(R.id.hitLabel)?.text = null
                    child.findViewById<TextView>(R.id.hitExcerpt)?.text = null
                    child.contentDescription = null
                }
            }
            return false
        }
        val context = container.context
        val res = context.resources
        val strips = ArrayList<Strip>()
        for (p in usable.pages) {
            val label = res.getQuantityString(R.plurals.page_hit_label, p.count, p.pageIndex + 1, p.count)
            val desc = context.getString(R.string.page_hit_open_desc, doc.name, p.pageIndex + 1) +
                ". " + label
            strips += Strip(label, p.excerpt, desc, PageLink(doc.id, usable.revision, p.pageIndex, p.pageKey, usable.query))
        }
        val firstKey = usable.firstPageKey
        if (usable.pageHitCount > usable.pages.size && firstKey != null) {
            strips += Strip(
                res.getQuantityString(R.plurals.page_hit_see_all, usable.pageHitCount, usable.pageHitCount),
                null,
                context.getString(R.string.page_hit_see_all_desc, doc.name, usable.pageHitCount),
                PageLink(doc.id, usable.revision, usable.firstPageIndex, firstKey, usable.query)
            )
        }
        while (container.childCount < strips.size) {
            LayoutInflater.from(context).inflate(R.layout.item_home_page_hit, container, true)
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val strip = strips.getOrNull(i)
            child.isVisible = strip != null
            if (strip == null) {
                child.setOnClickListener(null)
                continue
            }
            child.findViewById<TextView>(R.id.hitLabel).text = strip.label
            val excerptView = child.findViewById<TextView>(R.id.hitExcerpt)
            excerptView.isVisible = strip.excerpt != null
            excerptView.text = strip.excerpt?.let { styled(context, it) }
            child.contentDescription = strip.description
            child.setOnClickListener { onOpen(doc, strip.link) }
        }
        return true
    }

    /** Highlights only the genuine term occurrences the excerpt reports. */
    fun styled(context: android.content.Context, excerpt: PageTextMatches.Excerpt): CharSequence {
        val accent = ContextCompat.getColor(context, R.color.rv_accent)
        val wash = ContextCompat.getColor(context, R.color.rv_accent_fill)
        return try {
            SpannableString(excerpt.text).apply {
                for (r in excerpt.highlights) {
                    if (r.first < 0 || r.last >= length) continue
                    val end = r.last + 1
                    setSpan(StyleSpan(Typeface.BOLD), r.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(accent), r.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(BackgroundColorSpan(wash), r.first, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (_: Exception) {
            excerpt.text
        }
    }

    private class Strip(
        val label: String,
        val excerpt: PageTextMatches.Excerpt?,
        val description: String,
        val link: PageLink
    )
}
