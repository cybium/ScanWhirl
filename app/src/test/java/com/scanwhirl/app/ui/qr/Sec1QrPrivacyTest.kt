package com.scanwhirl.app.ui.qr

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SECURITY_JUDGE 1.3.0 finding 3 (MEDIUM): Wi-Fi QR secrets must not leak through
 * the activity-state Bundle, an unsecured reveal, or standard selection-copy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1QrPrivacyTest {

    companion object {
        private const val WIFI = "WIFI:T:WPA;S:Home;P:s3cret;;"
        private const val PASSWORD = "s3cret"
    }

    private lateinit var app: Application
    private lateinit var historyFile: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        historyFile = File(app.filesDir, QrHistory.FILE_NAME)
        historyFile.delete()
        QrScanners.override = null
    }

    @After
    fun tearDown() {
        QrScanners.override = null
        historyFile.delete()
    }

    private fun openWifi(): ActivityController<QrActivity> {
        QrScanners.override = QrScanner { _, callback ->
            callback(QrScanOutcome.Found(WIFI, QrKind.WIFI))
        }
        return Robolectric.buildActivity(QrActivity::class.java).setup().also {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** onSaveInstanceState is protected; reflection still exercises the real override. */
    private fun savedState(activity: Activity): Bundle {
        val out = Bundle()
        val method = Activity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
        method.isAccessible = true
        method.invoke(activity, out)
        return out
    }

    @Test(timeout = 30_000)
    fun `saved state never contains the wifi password`() {
        val c = openWifi()
        try {
            val state = savedState(c.get())
            val strings = state.keySet().mapNotNull { state.get(it) as? String }
            assertFalse(
                "the activity-state Bundle must not hold the password",
                strings.any { it.contains(PASSWORD) }
            )
            assertTrue(
                "the redacted Wi-Fi value is still saved so the result can be shown",
                strings.any { it.contains("S:Home") }
            )
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a recreated wifi result cannot show the password again`() {
        val c = openWifi()
        try {
            QrScanners.override = QrScanner { _, _ -> error("a restored result must not rescan") }
            c.recreate()
            assertNull(
                "after recreation the secret row is gone, not revealed",
                c.get().findViewById<View>(R.id.qrSecretValue)
            )
            assertFalse(SecureView.isSecure(c.get().window))
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `revealing the password secures the window and hiding clears it`() {
        val c = openWifi()
        try {
            val activity = c.get()
            assertFalse("a masked password needs no secure window", SecureView.isSecure(activity.window))
            val toggle = activity.findViewById<View>(R.id.qrFieldToggle) as TextView
            toggle.performClick()
            assertTrue(
                "a revealed password must not be screenshot-able",
                SecureView.isSecure(activity.window)
            )
            toggle.performClick()
            assertFalse("hiding the password restores normal screenshots", SecureView.isSecure(activity.window))
        } finally {
            c.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the secret value is not selectable so standard Copy cannot bypass privacy`() {
        val c = openWifi()
        try {
            val activity = c.get()
            val secret = activity.findViewById<TextView>(R.id.qrSecretValue)
            assertNotNull(secret)
            assertFalse(
                "selection handles would offer a system Copy outside ClipboardPrivacy",
                secret!!.isTextSelectable
            )
            val network = activity.findViewById<TextView>(R.id.qrFieldValue)
            assertNotNull("ordinary values stay selectable", network)
            assertTrue(network!!.isTextSelectable)
        } finally {
            c.destroy()
        }
    }
}
