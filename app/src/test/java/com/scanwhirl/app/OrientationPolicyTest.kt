package com.scanwhirl.app

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ORI (owner/lead decision 22 Nov): on a phone the app is portrait-only. A landscape phone
 * broke the capture/review loop, so [ScanWhirlApp] locks our activities to portrait when
 * `smallestScreenWidthDp < 600`. Book mode capture is the one exception; tablets (sw >= 600)
 * and activities that are not ours (Google's ML Kit scanner) are left alone.
 *
 * These tests verify the policy without changing the manifest and without changing any
 * existing test: Robolectric's default sw is a phone, and `recreate()` in the existing
 * rotation tests does not change `requestedOrientation`, so those tests keep passing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrientationPolicyTest {

    @After
    fun tearDown() {
        RuntimeEnvironment.setQualifiers("")
    }

    private fun smallestWidth(): Int =
        ApplicationProvider.getApplicationContext<ScanWhirlApp>().resources.configuration.smallestScreenWidthDp

    /** Builds one activity under [qualifiers], destroys it, and returns its requested orientation. */
    private fun requestedOrientationOf(clazz: Class<out Activity>, qualifiers: String): Int {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val controller = Robolectric.buildActivity(clazz)
        try {
            return controller.setup().get().requestedOrientation
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `a phone activity is locked to portrait`() {
        val orientation = requestedOrientationOf(
            com.scanwhirl.app.ui.shell.ShellActivity::class.java,
            "en-rUS-sw360dp-w360dp-h800dp-xhdpi"
        )
        assertEquals("phone qualifier really is sw360", 360, smallestWidth())
        assertEquals(
            "phone ShellActivity must be portrait",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            orientation
        )
    }

    // W8: the Book-pages rotation exception was deleted with that unreachable screen.
    // ScanWhirlApp keeps the (now inert) allow-list string; no live activity uses it.

    @Test
    fun `a tablet activity keeps its unset orientation`() {
        val orientation = requestedOrientationOf(
            com.scanwhirl.app.ui.shell.ShellActivity::class.java,
            "en-rUS-sw600dp-w600dp-h900dp-xhdpi"
        )
        assertEquals("tablet qualifier really is sw600", 600, smallestWidth())
        assertEquals(
            "tablets may rotate, so we must not set requestedOrientation",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation
        )
    }

    @Test
    fun `an activity that is not ours is left alone on a phone`() {
        // Stands in for Google's ML Kit scanner activity (com.google.*), which runs in our
        // process but is not one of ours, so the policy must never touch it.
        val orientation = requestedOrientationOf(
            Activity::class.java,
            "en-rUS-sw360dp-w360dp-h800dp-xhdpi"
        )
        assertEquals("phone qualifier really is sw360", 360, smallestWidth())
        assertEquals(
            "a foreign activity must keep the system default",
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation
        )
    }
}
