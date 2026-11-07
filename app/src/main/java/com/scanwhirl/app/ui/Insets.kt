package com.scanwhirl.app.ui

import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/** Views already fitted, keyed weakly so finished activities are collected. */
private val fitted = WeakHashMap<View, Rect>()

/**
 * Pads this view so its content clears the system bars (status/navigation) and,
 * on Android 11+, the IME.
 *
 * The view's *original* padding is captured once and insets are always added on
 * top of it, so repeated inset dispatches never stack. Pair with
 * `WindowCompat.setDecorFitsSystemWindows(window, false)` — done once in
 * [BaseActivity.onCreate] — so behaviour is identical on every Android version.
 *
 * Android 11+ ignores `adjustResize` once decor fitting is off, so the bottom
 * inset includes the IME there; below that `adjustResize` still resizes the
 * window and the IME inset is skipped to avoid double padding.
 *
 * @param top pad by the status-bar (plus cutout) inset.
 * @param bottom pad by the navigation-bar inset, and the IME when it is taller.
 */
fun View.applySystemBarsPadding(top: Boolean = true, bottom: Boolean = true) {
    if (fitted.containsKey(this)) return
    val original = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    fitted[this] = original
    val imeAware = bottom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = if (imeAware) insets.getInsets(WindowInsetsCompat.Type.ime()) else Insets.NONE
        v.setPadding(
            original.left,
            original.top + if (top) bars.top else 0,
            original.right,
            original.bottom + if (bottom) maxOf(bars.bottom, ime.bottom) else 0
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
