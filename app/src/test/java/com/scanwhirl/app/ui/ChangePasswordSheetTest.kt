package com.scanwhirl.app.ui

import android.app.Application
import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * v2 UI: 09_unlock_sheet — "Change password" asks the current password in an SwSheet with the kit
 * PasswordField (not the old SwDialog.input card) and paces wrong tries like the Unlock sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChangePasswordSheetTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, true)
        ShadowToast.reset()
    }

    @After fun tearDown() = Faults.resetAll()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun clickItem(index: Int) {
        // v2 UI: 09_unlock_sheet — choosers are kit SwSheets after L1's sweep (rows in swSheetItems);
        // L3's own lists may still be AlertDialogs, so both are handled.
        val d = ShadowDialog.getLatestDialog()
        if (d is AlertDialog) d.listView.performItemClick(null, index, 0)
        else d.findViewById<android.view.ViewGroup>(R.id.swSheetItems).getChildAt(index).performClick()
        idle()
    }

    private fun waitForHome(scenario: ActivityScenario<MainActivity>, docs: Int) {
        assertTrue("home list loads", pollUntil {
            var ok = false
            scenario.onActivity { a ->
                ok = (a.findViewById<RecyclerView>(R.id.scanList).adapter?.itemCount ?: 0) >= docs
            }
            ok
        })
    }

    /** Tools → Lock → Saved scan → the only document → "Password" (change). */
    private fun openChangePassword(activity: MainActivity) {
        activity.findViewById<View>(R.id.btnTools).performClick()
        val tools = ShadowDialog.getLatestDialog()
        assertNotNull("Tools sheet opens", tools)
        tools!!.findViewById<View>(R.id.tile_lock).performClick()
        idle()
        clickItem(0) // source_saved_scan
        clickItem(0) // the only document (FlowUtil.pickScan)
        clickItem(1) // Password / change
    }

    private fun currentPasswordSheet(): Dialog? {
        val d = ShadowDialog.getLatestDialog() ?: return null
        return d.takeIf { it.findViewById<View>(R.id.unlockPassword) != null }
    }

    private fun submit(text: String) {
        val d = ShadowDialog.getLatestDialog()!!
        d.findViewById<EditText>(R.id.unlockPassword)!!.setText(text)
        d.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
        idle()
    }

    @Test
    fun `change password asks the current one in the kit sheet, wrong stays, right opens Protect`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openChangePassword(it) }

            val sheet = currentPasswordSheet()
            assertNotNull("Change password asks for the current password", sheet)
            assertTrue("the password window is FLAG_SECURE",
                com.scanwhirl.app.util.SecureView.isSecure(sheet!!.window))
            assertEquals(
                "Current password",
                sheet!!.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )
            assertNotNull(
                "the kit PasswordField is used, not the old input card",
                sheet.findViewById<View>(R.id.swPasswordField)
            )

            submit("0000")
            val afterWrong = currentPasswordSheet()
            assertNotNull("a wrong password keeps the sheet open", afterWrong)
            val error = afterWrong!!.findViewById<TextView>(R.id.swPasswordError)
            assertEquals("the tries-left line is shown", View.VISIBLE, error.visibility)
            assertTrue("four tries left after one wrong try", error.text.contains("4 tries left"))
            assertTrue("a wrong current password never reaches the new-password sheet",
                afterWrong.findViewById<View>(R.id.protectPassword) == null)
            assertTrue("the document is still locked", repo.getDocument(id)!!.isLocked)

            submit("1234")
            assertTrue("the right current password opens the Change password sheet (mockup 05)",
                pollUntil {
                    val d = ShadowDialog.getLatestDialog()
                    d?.findViewById<View>(R.id.protectPassword) != null &&
                        d.findViewById<View>(R.id.protectConfirm) != null
                })
            val protect = ShadowDialog.getLatestDialog()!!
            assertEquals(
                "Change password",
                protect.findViewById<TextView>(R.id.protectTitle)?.text?.toString()
            )
            assertTrue("nothing changes until the new password is submitted",
                repo.getDocument(id)!!.isLocked)
            protect.dismiss()
        }
    }

    @Test
    fun `five wrong current passwords pause the sheet like Unlock`() {
        DocFixtures.locked(repo, app, password = "1234")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openChangePassword(it) }
            assertNotNull("the current-password sheet opens", currentPasswordSheet())

            repeat(5) { submit("0000") }
            val paused = currentPasswordSheet()
            assertNotNull("the sheet is still open during the pause", paused)
            val error = paused!!.findViewById<TextView>(R.id.swPasswordError)
            assertEquals("the pause is announced", View.VISIBLE, error.visibility)
            assertTrue(error.text.contains("paused"))
            assertFalse(
                "Continue is disabled while the pause runs",
                paused.findViewById<View>(R.id.swSheetPrimary).isEnabled
            )
        }
    }
}
