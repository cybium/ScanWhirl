package com.scanwhirl.app.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * v2 UI: 74_motion — the one haptic helper.
 *
 * Uses the view's haptic feedback (no VIBRATE permission, and the user's
 * "touch feedback" system setting is respected): a tick for entering selection
 * and toggles, a confirm pattern for a finished job, a reject pattern for a
 * wrong password.
 */
object SwHaptics {

    /** Selection start, drag pickup/drop, toggles. */
    fun tick(view: View?) {
        view ?: return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A finished job (save, protect, export, unlock). */
    fun confirm(view: View?) {
        view ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** A wrong password / refused action. */
    fun reject(view: View?) {
        view ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
}
