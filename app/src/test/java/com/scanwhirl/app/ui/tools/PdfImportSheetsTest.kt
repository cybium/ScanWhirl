package com.scanwhirl.app.ui.tools

import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.PdfImportBudget
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
 * v2 UI: 03_tools_hub — the big-import sheets: "This PDF has N pages" (Import all / Choose
 * pages / Cancel), the page-range form and the "Page k of N" progress with Cancel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfImportSheetsTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun plan(pages: Int = 800) = PdfImportBudget.plan(
        pages, freeBytes = 20L * 1024 * 1024 * 1024
    )

    @Test
    fun `import all is a decision and cancel is a decision`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val decisions = mutableListOf<ScanRepository.ImportDecision>()
            scenario.onActivity { activity ->
                PdfImportSheets.ask(activity, plan()) { decisions += it }.show()
                idle()
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
            assertEquals(listOf(ScanRepository.ImportDecision.All), decisions)

            scenario.onActivity { activity ->
                PdfImportSheets.ask(activity, plan()) { decisions += it }.show()
                idle()
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
            }
            assertEquals(
                listOf(ScanRepository.ImportDecision.All, ScanRepository.ImportDecision.Cancel),
                decisions
            )
        }
    }

    @Test
    fun `closing the plan sheet counts as cancel`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val decisions = mutableListOf<ScanRepository.ImportDecision>()
            scenario.onActivity { activity ->
                PdfImportSheets.ask(activity, plan()) { decisions += it }.show()
                idle()
                ShadowDialog.getLatestDialog().dismiss()
                idle()
            }
            assertEquals(listOf(ScanRepository.ImportDecision.Cancel), decisions)
        }
    }

    @Test
    fun `choose pages asks for a range and returns the selected pages`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val decisions = mutableListOf<ScanRepository.ImportDecision>()
            scenario.onActivity { activity ->
                PdfImportSheets.ask(activity, plan(100)) { decisions += it }.show()
                idle()
                ShadowDialog.getLatestDialog()
                    .findViewById<View>(R.id.toolImportChoosePages).performClick()
                idle()
                val range = ShadowDialog.getLatestDialog()
                assertNotNull(range.findViewById<View>(R.id.toolImportRange))
                range.findViewById<EditText>(R.id.toolImportRange).setText("1-20, 45")
                range.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
            }
            assertEquals(
                listOf(ScanRepository.ImportDecision.Pages((0..19).toList() + 44)),
                decisions
            )
        }
    }

    @Test
    fun `an invalid range keeps the sheet open and shows a clear line`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val decisions = mutableListOf<ScanRepository.ImportDecision>()
            scenario.onActivity { activity ->
                PdfImportSheets.ask(activity, plan(100)) { decisions += it }.show()
                idle()
                ShadowDialog.getLatestDialog()
                    .findViewById<View>(R.id.toolImportChoosePages).performClick()
                idle()
                val range = ShadowDialog.getLatestDialog()
                range.findViewById<EditText>(R.id.toolImportRange).setText("0-2")
                range.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                assertEquals(
                    activity.getString(R.string.tools_import_range_error),
                    range.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                assertTrue("still open", range.isShowing)
            }
            assertTrue("no decision on invalid input", decisions.isEmpty())
        }
    }

    @Test
    fun `progress shows the page count and cancels`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            var cancelled = false
            scenario.onActivity { activity ->
                val sheet = PdfImportSheets.progress(activity) { cancelled = true }
                sheet.show()
                idle()
                sheet.setMessage(PdfImportSheets.progressText(activity, 3, 10))
                idle()
                val dialog = ShadowDialog.getLatestDialog()
                assertEquals(
                    "Page 3 of 10",
                    dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                )
                dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
            }
            assertTrue("cancel is wired", cancelled)
        }
    }
}
