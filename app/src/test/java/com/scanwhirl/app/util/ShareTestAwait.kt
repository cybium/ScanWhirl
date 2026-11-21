package com.scanwhirl.app.util

import android.app.Activity
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertNotNull
import org.robolectric.Shadows.shadowOf

/** Wait for the real IO copy and then drain the main-thread chooser launch. */
internal fun awaitShareChooser(activity: Activity): Intent {
    val deadline = System.nanoTime() + 30_000_000_000L
    while (shadowOf(activity).peekNextStartedActivity() == null && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
    val chooser = shadowOf(activity).nextStartedActivity
    assertNotNull("completed share must start the chooser", chooser)
    return chooser
}
