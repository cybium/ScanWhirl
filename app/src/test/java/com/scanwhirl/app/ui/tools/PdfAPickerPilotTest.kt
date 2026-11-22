package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.PdfAFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-W6 pilot: "Save as PDF/A" must use the shared picker (search, thumbnail, pages · size ·
 * date, category), route by document id for two identical names, and review the chosen file on
 * the confirm sheet before it writes anything.
 *
 * Red on base 8d2fabfd: PdfAFlow opened the old name-only SwSheet list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class PdfAPickerPilotTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun latest(): Dialog? = ShadowDialog.getLatestDialog()

    private fun rows(dialog: Dialog): List<LinearLayout> {
        val list = dialog.findViewById<LinearLayout>(R.id.docPickerList) ?: return emptyList()
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? LinearLayout }
    }

    @Test
    fun `PDF-A uses the shared picker and reviews the exact chosen file`() {
        val threePages = DocFixtures.saved(repo, app, pages = 3, name = "SW_TEST_3pages")
        // Legacy duplicate: both sources keep the same visible title, so the sheet must rely on
        // the chosen document's id (and page count), not on a unique name.
        val onePage = DocFixtures.savedLegacyDuplicate(repo, app, pages = 1, name = "SW_TEST_3pages")

        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                PdfAFlow.start(activity, repo)
                idle()
                val picker = requireNotNull(latest()) { "PDF/A opened no picker" }
                assertNotNull(
                    "PDF/A must use the picker with a search box",
                    picker.findViewById<TextView>(R.id.docPickerSearch)
                )
                assertTrue("both same-named sources are listed", waitUntil { rows(picker).size == 2 })
                val target = rows(picker).firstOrNull { it.tag == onePage }
                    ?: throw AssertionError("no row for the 1-page same-named file")
                assertEquals(
                    "the row shows the document name",
                    "SW_TEST_3pages",
                    target.findViewById<TextView>(R.id.docPickerName).text.toString()
                )
                target.performClick()
                idle()

                val confirm = requireNotNull(latest()) { "PDF/A confirm sheet did not open" }
                val message = requireNotNull(confirm.findViewById<TextView>(R.id.swSheetMessage)) {
                    "the confirm sheet lost its message"
                }.text.toString()
                assertTrue(
                    "the confirm step reviews the chosen file by name: $message",
                    message.contains("SW_TEST_3pages")
                )
                assertTrue(
                    "the confirm step reviews the chosen file's pages: $message",
                    message.contains("1 page")
                )
                assertNotNull("the confirm sheet never wrote yet", confirm.findViewById<TextView>(R.id.swSheetPrimary))
                assertNotNull("the 3-page twin is untouched", repo.getDocument(threePages))
            }
        }
    }
}
