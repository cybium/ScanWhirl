package com.scanwhirl.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import androidx.core.view.doOnNextLayout
import com.scanwhirl.app.R
import com.scanwhirl.app.util.SwMotion

/**
 * test25 Phase 4: window transitions for the Globe↔Shelf warp dive plus the
 * radial reveal for the scan launch.
 *
 * - The four warp anims (`globe/shelf_enter/exit_warp`) were built in Phase 0.
 * - `overridePendingTransition` is finite, so it is deliberately **not**
 *   ambient-gated: the system already scales window animations by
 *   `ANIMATOR_DURATION_SCALE`.
 * - The reveal *does* delay the launch, so it **is** gated: ambient off
 *   (Robolectric, or the user disabled animations) runs [onEnd] synchronously
 *   and never touches the overlay — callers and tests keep their same-frame
 *   launch.
 */
object SwTransitions {

    /** M3 standard curve — `FastOutSlowInInterpolator` is not on the classpath. */
    private val PATH = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** A reveal in flight (at most one: the globe home is the only caller). */
    private var revealAnimator: Animator? = null

    /** Overlay shown but its layout listener has not fired yet. */
    private var revealPending = false

    /**
     * Increments whenever a reveal is started or abandoned, so a one-shot
     * `doOnNextLayout` listener from an abandoned reveal can never fire for a
     * later one (which would double-launch).
     */
    private var revealSession = 0

    /** Globe → Shelf: the Shelf warps in while the globe warps out. */
    fun warpToShelf(activity: Activity) {
        activity.overridePendingTransition(R.anim.shelf_enter_warp, R.anim.globe_exit_warp)
    }

    /** Shelf → Globe (from `MainActivity.finish()` when the globe is below). */
    fun warpBackToGlobe(activity: Activity) {
        activity.overridePendingTransition(R.anim.globe_enter_warp, R.anim.shelf_exit_warp)
    }

    /**
     * Radial reveal of [overlay] expanding from [anchor]'s centre; [onEnd]
     * (the actual launch) fires at full cover. Ambient off → immediate [onEnd].
     */
    fun revealScanLaunch(activity: Activity, anchor: View, overlay: View, onEnd: () -> Unit) {
        if (!SwMotion.ambientAllowed(activity)) {
            onEnd()
            return
        }
        // GONE views are never laid out (width/height stay 0) and
        // createCircularReveal needs a laid-out, attached view — register the
        // one-shot layout listener first, then show the overlay.
        val session = ++revealSession
        revealPending = true
        overlay.alpha = 1f
        overlay.doOnNextLayout {
            if (session != revealSession) return@doOnNextLayout // abandoned
            revealPending = false
            val anchorLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            val overlayLoc = IntArray(2)
            overlay.getLocationOnScreen(overlayLoc)
            val cx = anchorLoc[0] - overlayLoc[0] + anchor.width / 2f
            val cy = anchorLoc[1] - overlayLoc[1] + anchor.height / 2f
            val reach = kotlin.math.hypot(
                maxOf(cx, overlay.width - cx),
                maxOf(cy, overlay.height - cy)
            )
            var cancelled = false
            val reveal = ViewAnimationUtils.createCircularReveal(
                overlay, cx.toInt(), cy.toInt(), 0f, reach
            )
            reveal.duration = 260L
            reveal.interpolator = PATH
            reveal.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    revealAnimator = null
                    if (!cancelled) onEnd()
                }
            })
            revealAnimator = reveal
            reveal.start()
        }
        overlay.visibility = View.VISIBLE // triggers the layout pass above
    }

    /**
     * Abort a reveal that never completed — the activity paused mid-flight and
     * the launch has NOT happened, so drop the wash (and never let the stale
     * listener fire later). A reveal that already ended has cleared both
     * flags, so **the launch's own pause keeps the wash up**: the globe must
     * stay covered while the Shelf's enter animation fades in over it.
     */
    fun cancelReveal(overlay: View) {
        revealSession++ // invalidate a still-pending layout listener
        val running = revealAnimator
        revealAnimator = null
        running?.cancel()
        if (running != null || revealPending) {
            overlay.visibility = View.GONE
            overlay.alpha = 1f
        }
        revealPending = false
    }
}
