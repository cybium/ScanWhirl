package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

/** PM-3.1b: the Tools → Lock → Unlock entry point needs the current password (F9). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LockUiTest {

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

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun passwordDialog(): AlertDialog? {
        val d = ShadowDialog.getLatestDialog() as? AlertDialog ?: return null
        return d.takeIf { dlg -> allViews(dlg.window!!.decorView).any { it is EditText } }
    }

    private fun submit(dialog: AlertDialog, text: String) {
        allViews(dialog.window!!.decorView).filterIsInstance<EditText>().first().setText(text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle() // AlertDialog dispatches button clicks through a Handler message
    }

    private fun clickItem(index: Int) {
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, index, 0)
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

    /** Tools → Lock → Saved scan → the document → item 0 ("Unlock PDF"). */
    private fun chooseUnlock(activity: MainActivity) {
        activity.findViewById<View>(R.id.btnTools).performClick()
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("Tools sheet opens", sheet)
        sheet!!.findViewById<View>(R.id.tile_lock).performClick()
        idle()
        clickItem(0) // source_saved_scan
        clickItem(0) // the only document (FlowUtil.pickScan)
        clickItem(0) // Unlock PDF
    }

    @Test
    fun `Tools Lock unlock asks for the current password`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        assertTrue(repo.getDocument(id)!!.isLocked)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)

            // Unlock must ask first, and must not have unlocked anything yet.
            scenario.onActivity { chooseUnlock(it) }
            val first = passwordDialog()
            assertNotNull("Unlock must ask for the current password", first)
            assertTrue("document must still be locked while the prompt is open", repo.getDocument(id)!!.isLocked)

            // A wrong password: error toast, still locked.
            submit(first!!, "0000")
            assertTrue("wrong password shows the error", pollUntil {
                ShadowToast.getTextOfLatestToast()?.toString() == app.getString(R.string.unlock_pdf_wrong)
            })
            assertTrue("still locked after a wrong password", repo.getDocument(id)!!.isLocked)

            // The right password unlocks.
            scenario.onActivity { chooseUnlock(it) }
            val second = passwordDialog()
            assertNotNull("Unlock asks again", second)
            submit(second!!, "1234")
            assertTrue("the right password unlocks", pollUntil { !repo.getDocument(id)!!.isLocked })
            assertEquals(false, com.scanwhirl.app.util.PdfProtection.isEncrypted(repo.getDocument(id)!!.pdfFile))
        }
    }
}
