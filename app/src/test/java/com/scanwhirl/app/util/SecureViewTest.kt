package com.scanwhirl.app.util

import android.app.Application
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SECURITY_AUDIT 2026-11-15 — "Block screenshots of documents".
 *
 * Protected / Vault documents are blocked whatever the setting says; ordinary
 * scans follow the user's switch (default off, so normal scans stay easy to
 * screenshot) and the Settings row persists both states.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SecureViewTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setBlockDocScreenshots(app, false)
    }

    @After
    fun tearDown() {
        Prefs.setBlockDocScreenshots(app, false)
    }

    private fun activity(): android.app.Activity =
        Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

    @Test
    fun `an ordinary document is screenshot-friendly by default`() {
        val activity = activity()
        SecureView.applyForDocument(activity, activity.window, protected = false)
        assertFalse(
            "ordinary scans default to screenshot-friendly",
            SecureView.isSecure(activity.window)
        )
    }

    @Test
    fun `the setting blocks screenshots of ordinary documents`() {
        Prefs.setBlockDocScreenshots(app, true)
        val activity = activity()
        SecureView.applyForDocument(activity, activity.window, protected = false)
        assertTrue(
            "the switch blocks ordinary documents",
            SecureView.isSecure(activity.window)
        )
    }

    @Test
    fun `protected and vault documents are always blocked`() {
        Prefs.setBlockDocScreenshots(app, false)
        val activity = activity()
        SecureView.applyForDocument(activity, activity.window, protected = true)
        assertTrue(
            "a protected/Vault document is blocked even when the switch is off",
            SecureView.isSecure(activity.window)
        )
    }

    @Test
    fun `clearing the flag re-enables screenshots on that window`() {
        val activity = activity()
        SecureView.apply(activity.window)
        assertTrue(SecureView.isSecure(activity.window))
        SecureView.clear(activity.window)
        assertFalse(SecureView.isSecure(activity.window))
    }

    @Test
    fun `the Settings switch shows and persists both states`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val toggle = activity.findViewById<SwitchCompat>(R.id.switchBlockScreenshots)
        assertNotNull("the setting is in Settings", toggle)
        assertFalse("off by default for ordinary documents", toggle!!.isChecked)
        val row = activity.findViewById<android.view.ViewGroup>(R.id.rowBlockScreenshots)
        assertTrue(
            "Privacy & security rows start with a leading icon like the XML rows",
            row.getChildAt(0) is android.widget.ImageView
        )
        assertEquals(
            "24dp leading icon",
            (24 * activity.resources.displayMetrics.density).toInt(),
            row.getChildAt(0).layoutParams.width
        )

        toggle.isChecked = true
        assertTrue("turning it on is saved", Prefs.blockDocScreenshots(app))

        toggle.isChecked = false
        assertFalse("turning it off is saved", Prefs.blockDocScreenshots(app))
    }
}
