package com.scanwhirl.app.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * test25 Phase 4: the gate every ambient (endless/looping) animation must pass
 * before it starts — FAB pulse, globe auto-rotation, ring drift, star twinkle,
 * ring-track shimmer, the scan-launch reveal.
 *
 * Master-plan formula: `ANIMATOR_DURATION_SCALE > 0 && FINGERPRINT !=
 * "robolectric" && powerManager.isInteractive`. The checks run in that order so
 * every branch stays reachable under Robolectric (`Settings.Global` writes and
 * `ShadowPowerManager.setIsInteractive` work there; a fingerprint
 * short-circuit first would make the other two dead code in tests).
 *
 * Hard constraint from the plan: **all infinite animators must check this
 * before `start()`** — the shadow Choreographer serves vsync eagerly inside
 * looper `idle()`, so a wrongly-started infinite animator hangs the suite.
 */
object SwMotion {

    /**
     * True when ambient motion (pulse, shimmer, drift, reveal) may run.
     *
     * @param fingerprint injection point for tests: the real value is
     * [Build.FINGERPRINT], and passing a non-"robolectric" one lets the scale
     * and interactive branches be proven under Robolectric (which would
     * otherwise always short-circuit on the fingerprint check).
     */
    fun ambientAllowed(context: Context, fingerprint: String = Build.FINGERPRINT): Boolean =
        animatorScaleOn(context) && deviceInteractive(context) && fingerprint != "robolectric"

    /** `Settings.Global.ANIMATOR_DURATION_SCALE > 0` (absent → 1 = on). */
    private fun animatorScaleOn(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    }.getOrDefault(true)

    /** Screen on and interactive — nothing loops on a dark device. */
    private fun deviceInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }
}
