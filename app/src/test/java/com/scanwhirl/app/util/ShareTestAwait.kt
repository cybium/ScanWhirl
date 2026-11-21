package com.scanwhirl.app.util

import android.app.Activity
import android.content.Intent
import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * Wait for the real IO copy and then drain the main-thread chooser launch. A freshly shared
 * file is published asynchronously, so a single idle() is not enough; unrelated activities
 * started earlier in the same test are skipped so the chooser is the one returned.
 */
internal fun awaitShareChooser(activity: Activity): Intent {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        var started = shadowOf(activity).nextStartedActivity
        while (started != null && started.action != Intent.ACTION_CHOOSER) {
            started = shadowOf(activity).nextStartedActivity
        }
        if (started != null) return started
        Thread.sleep(10)
    }
    throw AssertionError("completed share must start the chooser")
}
