package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

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
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.tools.LockFlow
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
 * W8 port: the live LockFlow change-password gate driven from ShellActivity.
 * Asks the current password in an SwSheet with the kit PasswordField and paces wrong tries.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChangePasswordSheetTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
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
        // UI-W6: the document chooser is the shared searchable picker (rows in docPickerList).
        // LockFlow's own source list and actions stay AlertDialog / SwSheet.
        val d = ShadowDialog.getLatestDialog()
        val picker = d.findViewById<android.view.ViewGroup>(R.id.docPickerList)
        when {
            picker != null -> picker.getChildAt(index).performClick()
            d is AlertDialog -> d.listView.performItemClick(null, index, 0)
            else -> d.findViewById<android.view.ViewGroup>(R.id.swSheetItems).getChildAt(index).performClick()
        }
        idle()
    }

    /** W8: the live Library shell renders cards into swDocList. */
    private fun waitForHome(scenario: ActivityScenario<ShellActivity>, docs: Int) {
        assertTrue("library loads", pollUntil {
            var ok = false
            scenario.onActivity { a ->
                ok = (a.findViewById<android.view.ViewGroup>(R.id.swDocList)?.childCount ?: 0) >= docs
            }
            ok
        })
    }

    /**
     * W8: the live Lock entry is the shell's Tools → Lock tile. Drive the same
     * LockFlow route without the deleted Home host: chooser → document → Password.
     */
    private fun openChangePassword(activity: ShellActivity, docId: String) {
        val doc = requireNotNull(repo.getDocument(docId))
        LockFlow(activity, repo, activity, { listOf(doc) }, { _ -> }).chooseLockSource()
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
        // S4B: the current-password check is a suspending PBKDF2 off the main thread. Wait until
        // it has been applied (the button leaves its "Checking password…" label, or the sheet
        // closes) before the next step.
        val checkingLabel = app.getString(R.string.unlock_checking)
        assertTrue(
            "the check finishes",
            pollUntil {
                val sheet = currentPasswordSheet()
                sheet == null ||
                    sheet.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString() != checkingLabel
            }
        )
    }

    @Test
    fun `change password asks the current one in the kit sheet, wrong stays, right opens Protect`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openChangePassword(it, id) }

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
        val id = DocFixtures.locked(repo, app, password = "1234")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openChangePassword(it, id) }
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
