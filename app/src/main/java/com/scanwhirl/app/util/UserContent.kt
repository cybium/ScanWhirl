package com.scanwhirl.app.util

import android.view.View
import com.scanwhirl.app.R

/**
 * Lane WC: the one supported way for a screen to tell the automated word-count
 * gate that a view shows USER content (a file/document name or OCR text), not a
 * word the app wrote. The gate (VisibleWords.count) skips any marked view and
 * its whole subtree.
 *
 * This costs no visible UI and no accessibility label; it is a keyed view tag,
 * so it cannot collide with the app's ordinary `View.tag` slot. Adding a mark
 * is the job of the lane that owns the screen; the WC lane only supplies the
 * id (res/values/ids_wc.xml) and this helper. It changes no behaviour at run
 * time beyond attaching a tag.
 */
object UserContent {
    private val MARKED = java.lang.Boolean.TRUE

    /** Mark a view (and, by inheritance in the walker, its subtree) as user content. */
    fun mark(view: View?) {
        if (view != null) view.setTag(R.id.sw_user_content, MARKED)
    }

    fun isMarked(view: View): Boolean = view.getTag(R.id.sw_user_content) === MARKED
}
