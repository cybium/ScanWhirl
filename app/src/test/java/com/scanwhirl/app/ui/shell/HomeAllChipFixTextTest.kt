package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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

    private fun allChip(activity: ShellActivity): View {
        val row = activity.findViewById<LinearLayout>(R.id.swChipRow)
        assertTrue("the category chips are shown", row.childCount > 0)
        return row.getChildAt(0)
    }

    @Test
    fun `the All chip opens the category choice and filters the library`() {
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

                allChip(activity).performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("tapping All opens the category choice", sheet)
                assertEquals(
                    activity.getString(R.string.home_filter_category_title),
                    sheet.findViewById<android.view.View>(R.id.swSheetTitle)?.let {
                        (it as android.widget.TextView).text.toString()
                    }
                )
                val items = sheet.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("the sheet lists the categories", items)
                assertEquals("All + the five categories", 6, items.childCount)

                // 0 = All categories, 1 Study, 2 Receipts, ...
                items.getChildAt(2).performClick()
                idle()
                assertEquals(
                    "picking Receipts filters the library",
                    listOf("Electricity bill"),
                    cards(activity)
                )
                // The All chip is no longer active: tapping it clears the filter.
                allChip(activity).performClick()
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
