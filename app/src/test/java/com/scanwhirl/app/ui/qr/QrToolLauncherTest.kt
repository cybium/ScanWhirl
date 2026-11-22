package com.scanwhirl.app.ui.qr
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The QR tile must lead somewhere real: the Pro screen for Free, the scanner for Pro. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrToolLauncherTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ProOffer.testBuildForTests = null
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        ProOffer.testBuildForTests = null
    }

    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `Free QR tile opens the Pro offer instead of a dead-end Got it sheet`() {
        ProGate.setTestOverride(app, false)
        val activity = host()

        QrToolLauncher.open(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the tile must not be a dead tap", started)
        assertEquals(
            "Free users land on the Pro screen",
            ShellActivity::class.java.name,
            started!!.component?.className
        )
        assertTrue(
            "the shell must show the Pro offer",
            started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
        )
        assertTrue("no Got it sheet", ShadowDialog.getLatestDialog() == null)
    }

    @Test
    fun `Pro QR tile opens the scanner screen`() {
        ProGate.setTestOverride(app, true)
        val activity = host()

        QrToolLauncher.open(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(QrActivity::class.java.name, started!!.component?.className)
    }
}
