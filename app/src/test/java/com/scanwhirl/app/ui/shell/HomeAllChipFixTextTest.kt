package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.ProGate
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

/**
 * H8 (found on device, 1.2.2): the Home "All" chip was a dead tap while the library
 * already showed everything. It now opens the category filter choice, and a pick
 * filters the library; tapping All again clears the filter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeAllChipFixTextTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun cards(activity: ShellActivity): List<String> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { (list.getChildAt(it) as DocCard).doc?.name }
    }

    private fun categoryRows(): ViewGroup {
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("the shared chooser opens", sheet)
        val row = sheet!!.findViewById<ViewGroup>(R.id.swChipRow)
        assertNotNull("the category rows are shown", row)
        assertTrue("there are category rows", row.childCount > 0)
        return row
    }

    private fun categoryText(label: String): TextView {
        val sheet = ShadowDialog.getLatestDialog()!!
        val row = sheet.findViewById<ViewGroup>(R.id.swChipRow)
        return (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<TextView>()
            .first { it.text.toString().contains(label, ignoreCase = true) }
    }

    @Test
    fun `the shared chooser offers categories and filtering stays reversible`() {
        val receipt = DocFixtures.saved(repo, app, pages = 1, name = "Electricity bill")
        repo.setCategory(receipt, CategoryRules.RECEIPTS)
        DocFixtures.saved(repo, app, pages = 1, name = "Lecture notes").let {
            repo.setCategory(it, CategoryRules.STUDY)
        }

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(
                    "both documents are listed before filtering",
                    setOf("Electricity bill", "Lecture notes"),
                    cards(activity).toSet()
                )

                // Council §7: OLD tapped a face "All" chip and got a separate six-option
                // category sheet. NEW opens the one folder/category chooser; the category
                // rows inside it are the one-tap filter choice.
                activity.findViewById<View>(R.id.swAllFolders).performClick()
                idle()
                assertTrue("All categories plus the categories with documents", categoryRows().childCount >= 2)
                categoryText("Receipts").performClick()
                idle()
                assertEquals(
                    "picking Receipts filters the library",
                    listOf("Electricity bill"),
                    cards(activity)
                )

                activity.findViewById<View>(R.id.swAllFolders).performClick()
                idle()
                categoryText(activity.getString(R.string.home_filter_all_documents)).performClick()
                idle()
                assertEquals(
                    "tapping All again shows every document",
                    setOf("Electricity bill", "Lecture notes"),
                    cards(activity).toSet()
                )
            }
        }
    }
}
