package com.scanwhirl.app.ui.kit

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R

/**
 * v2 UI: 74_motion / 01_home_A — the kit snackbar.
 *
 * Glass ink surface, cyan action, 6 s, anchored ABOVE the shell's nav bar/disc
 * (never under the keyboard or the nav), with the action exposed to TalkBack.
 * The keyboard is dismissed before it appears.
 */
object SwSnackbar {

    /** The shell anchors above its nav; other screens use [anchorFrom]. */
    fun show(
        activity: Activity,
        root: View,
        message: CharSequence,
        actionLabel: CharSequence? = null,
        anchor: View? = anchorFrom(root),
        onAction: (() -> Unit)? = null
    ): Snackbar {
        // A snackbar must never hide under the IME: close it first.
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        val focused = activity.currentFocus
        focused?.clearFocus()

        val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setDuration(6000)
        val navAnchor = anchor ?: anchorFrom(root.rootView)
        navAnchor?.let {
            snackbar.anchorView = it
            snackbar.view.tag = it
        }
        if (actionLabel != null && onAction != null) {
            snackbar.setAction(actionLabel) { onAction() }
            snackbar.setActionTextColor(SwStyle.color(activity, R.color.cyan_primary))
        }
        snackbar.setTextColor(SwStyle.color(activity, R.color.text_hologram_hi))
        snackbar.view.background = SwStyle.rounded(
            activity,
            SwStyle.color(activity, R.color.tok_surface_2),
            radiusDp = SwStyle.RADIUS_CARD_DP,
            stroke = SwStyle.color(activity, R.color.sw_cyan_hair_strong)
        )
        // The action is a real button for TalkBack; give it the same words.
        snackbar.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)?.let {
            it.contentDescription = actionLabel ?: activity.getString(R.string.sheet_got_it)
        }
        snackbar.show()
        return snackbar
    }

    /**
     * The shell's nav host is the parent of `swShellNav`; other windows fall back
     * to the bottom of the screen (no anchor).
     */
    fun anchorFrom(root: View): View? =
        runCatching { root.findViewById<View>(R.id.swShellNav)?.parent as? View }.getOrNull()
}
