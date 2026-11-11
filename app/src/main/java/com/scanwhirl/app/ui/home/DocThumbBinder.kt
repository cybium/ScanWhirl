package com.scanwhirl.app.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.ScanFilter

/**
 * Binds a Home row's paper thumbnail.
 *
 * Privacy first: a real page image is decoded only for an ordinary document —
 * never for a password-locked one and never for a Private (vaulted) one, even
 * while the vault is open, so no private page ever enters the shared bitmap
 * cache. Those rows show a dark sheet whose glyph says why.
 *
 * Decoding is PageThumbLoader's: background threads, sampled + exact-fit to
 * [targetPx] (≤ [MAX_THUMB_PX]), the shared 24 MiB LRU, and the view-tag guard
 * that drops a late result once the row is recycled for another document.
 * Nothing is ever written to disk.
 */
object DocThumbBinder {

    /** Longest side of a Home thumb in px; 100 thumbs stay ≈ 13 MB of ARGB. */
    const val MAX_THUMB_PX = 220

    /** Cross-fade for a freshly decoded page only (cached rows appear at once). */
    const val FADE_MS = 160

    /** Why a row shows no page image. */
    enum class Kind { PAGE, LOCKED, PRIVATE, PDF_ONLY, UNREADABLE }

    /** The thumbnail this document may show — pure, testable, decided before any I/O. */
    fun kindFor(doc: ScanDocument): Kind = when {
        doc.isVaulted -> Kind.PRIVATE
        doc.isLocked -> Kind.LOCKED
        doc.pageFiles.isEmpty() -> Kind.PDF_ONLY
        else -> Kind.PAGE
    }

    fun targetPx(context: Context): Int =
        minOf((80 * context.resources.displayMetrics.density).toInt(), MAX_THUMB_PX)

    fun bind(image: ImageView, glyph: ImageView, doc: ScanDocument) {
        // Any earlier request for this recycled view is now stale.
        PageThumbLoader.cancel(image)
        image.animate().cancel()
        val context = image.context
        // Paper is dimmed on the dark desk, never inverted.
        image.setColorFilter(ContextCompat.getColor(context, R.color.sw_page_dim), PorterDuff.Mode.MULTIPLY)
        when (val kind = kindFor(doc)) {
            Kind.PAGE -> {
                glyph.isVisible = false
                val file = doc.pageFiles.first()
                PageThumbLoader.load(
                    file = file,
                    targetPx = targetPx(context),
                    filter = ScanFilter.ORIGINAL,
                    into = image,
                    fitExactly = true,
                    placeholder = R.drawable.bg_thumb_paper,
                    onLoaded = { bitmap, fromCache -> show(image, bitmap, animate = !fromCache) },
                    onFailed = { showGlyph(image, glyph, Kind.UNREADABLE) },
                    skipStale = true
                )
            }
            else -> showGlyph(image, glyph, kind)
        }
    }

    /** Recycled rows drop their pending request and their bitmap reference. */
    fun unbind(image: ImageView, glyph: ImageView) {
        PageThumbLoader.cancel(image)
        image.animate().cancel()
        image.setImageResource(R.drawable.bg_thumb_paper)
        glyph.isVisible = false
    }

    private fun show(image: ImageView, bitmap: Bitmap, animate: Boolean) {
        val context = image.context
        if (!animate || !transitionsOn(context)) {
            image.setImageBitmap(bitmap)
            return
        }
        val placeholder = ContextCompat.getDrawable(context, R.drawable.bg_thumb_paper)
        if (placeholder == null) {
            image.setImageBitmap(bitmap)
            return
        }
        val fade = TransitionDrawable(arrayOf(placeholder, BitmapDrawable(context.resources, bitmap)))
        fade.isCrossFadeEnabled = true
        image.setImageDrawable(fade)
        fade.startTransition(FADE_MS)
    }

    private fun showGlyph(image: ImageView, glyph: ImageView, kind: Kind) {
        PageThumbLoader.cancel(image)
        if (kind == Kind.UNREADABLE) {
            // A page file that will not decode keeps the blank-paper placeholder:
            // the row stays recognisable by name, and nothing pretends to be its image.
            image.setImageResource(R.drawable.bg_thumb_paper)
            glyph.isVisible = false
            return
        }
        image.setImageResource(R.drawable.bg_thumb_vault)
        glyph.setImageResource(
            when (kind) {
                Kind.LOCKED -> R.drawable.ic_sw_lock
                Kind.PRIVATE -> R.drawable.ic_sw_private
                else -> R.drawable.ic_sw_pdf_file
            }
        )
        glyph.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(image.context, R.color.rv_ink_2))
        glyph.visibility = View.VISIBLE
    }

    /** Reduced motion: the system animator scale at 0 turns the fade off. */
    private fun transitionsOn(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)
}
