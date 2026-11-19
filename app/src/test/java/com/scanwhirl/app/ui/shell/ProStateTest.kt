package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.BuildConfig
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.util.ProGate
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: Pro state consistency — a Pro build hides every PRO tag and the Me row
 * says the suite is active; test builds can flip to Free from Settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProStateTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) out += v.text?.toString().orEmpty()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** Opens the Create sheet from the centre Scan action. */
    private fun openCreate(activity: ShellActivity): android.app.Dialog {
        activity.findViewById<View>(R.id.swNavScan).performClick()
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        org.junit.Assert.assertNotNull("the Create sheet opens", dialog)
        return dialog!!
    }

    private fun qrProBadge(dialog: android.app.Dialog): TextView? =
        dialog.findViewById<TextView>(R.id.swCreateQrPro)

    @Test
    fun `a Pro build has no PRO marker in the Create sheet and the Me row is active`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue(
                    "test builds start Pro",
                    ProGate.isPro(activity)
                )
                val sheet = openCreate(activity)
                val badge = qrProBadge(sheet)
                assertTrue(
                    "no PRO marker in a Pro build",
                    badge == null || badge.visibility != View.VISIBLE
                )
                sheet.dismiss()

                activity.findViewById<View>(R.id.swNavMe).performClick()
                idle()
                val meRow = activity.findViewById<View>(R.id.swMePro)
                assertNotNull(meRow)
                val rowTexts = texts(meRow)
                assertTrue(
                    "the Me row thanks the owner: $rowTexts",
                    rowTexts.any { it.contains("active", ignoreCase = true) }
                )
            }
        }
    }

    @Test
    fun `the Pro marker follows the override when the Create sheet is reopened`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                var sheet = openCreate(activity)
                assertTrue(
                    "start Pro: no marker",
                    qrProBadge(sheet)?.visibility != View.VISIBLE
                )
                sheet.dismiss()

                ProGate.setTestOverride(app, false)
                activity.syncProState()
                idle()
                sheet = openCreate(activity)
                val freeBadge = qrProBadge(sheet)
                assertTrue(
                    "flipping to Free marks the QR entry",
                    freeBadge != null && freeBadge.visibility == View.VISIBLE
                )
                assertEquals("Pro", freeBadge!!.text.toString())
                sheet.dismiss()

                ProGate.setTestOverride(app, null)
                activity.syncProState()
                idle()
                sheet = openCreate(activity)
                assertTrue(
                    "flipping back to Pro hides it again",
                    qrProBadge(sheet)?.visibility != View.VISIBLE
                )
                sheet.dismiss()
            }
        }
    }

    @Test
    fun `the test-only Free switch is present in a test build and flips the gate`() {
        assertTrue("this suite runs as a test build", BuildConfig.TEST_BUILD)
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = settings.get()
        val toggle = activity.findViewById<SwitchCompat>(R.id.switchTestFree)
        assertNotNull("test-build Settings offers act-as-Free", toggle)
        assertFalse("Pro by default", toggle!!.isChecked)
        val testRow = activity.findViewById<ViewGroup>(R.id.rowTestFree)
        assertTrue(
            "the test row has a leading icon like the other Settings rows",
            testRow.getChildAt(0) is android.widget.ImageView
        )

        toggle.isChecked = true
        assertFalse("the gate now reports Free", ProGate.isPro(activity))
        assertEquals(false, ProGate.testOverride(activity))

        // A Free build marks the Pro route again.
        val shellController = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val shell = shellController.get()
        idle()
        shell.findViewById<View>(R.id.swNavScan).performClick()
        idle()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull("the Create sheet opens", sheet)
        val badge = sheet!!.findViewById<TextView>(R.id.swCreateQrPro)
        assertTrue(
            "Free builds mark the Pro route in the Create sheet",
            badge != null && badge.visibility == View.VISIBLE
        )
        shellController.pause().stop().destroy()
        settings.pause().stop().destroy()
    }
}
