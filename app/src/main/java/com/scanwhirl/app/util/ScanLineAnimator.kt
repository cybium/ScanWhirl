package com.scanwhirl.app.util

import android.view.View
import android.animation.ValueAnimator
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R

/**
 * test26 T8.3 — scan-line sweep over a thumbnail while OCR runs.
 *
 * Finite 1.2 s sweep (no repeat), gated on [SwMotion.ambientAllowed] so the suite
 * stays deterministic under Robolectric (fingerprint = "robolectric" → no animation).
 *
 * Usage:
 *   val animator = ScanLineAnimator.start(thumbnailView)
 *   // when OCR ends:
 *   animator.cancel()
 */
object ScanLineAnimator {

    private const val SWEEP_MS = 1200L

    /** Start the scan-line sweep on [view]. Returns the animator (or null if gated off). */
    fun start(view: View): ValueAnimator? {
        if (!SwMotion.ambientAllowed(view.context)) return null
        view.background = ContextCompat.getDrawable(view.context, R.drawable.bg_scan_line)
        // One 1.2 s sweep per OCR pass — never endless. An endless animator would
        // outlive the thumbnail (and, under Robolectric's paused looper, the test).
        val animator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = SWEEP_MS
            repeatCount = 0
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // Move the line diagonally across the view by shifting the gradient offset.
                view.background?.setColorFilter(
                    android.graphics.PorterDuffColorFilter(
                        android.graphics.Color.argb(
                            (255 * (0.5 + 0.5 * t)).toInt(),
                            0, 0xF0, 0xFF
                        ),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                )
            }
            start()
        }
        return animator
    }
}
