package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner F8: the Text (OCR) screen action chips were packed together. They now keep an 8dp gap
 * between chips, 12dp between the two rows, and a 48dp touch height for every chip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrChipSpacingFixUiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun dp(activity: OcrEditorActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun launch(block: (OcrEditorActivity) -> Unit) {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Invoice 0231")
        DocFixtures.withOcr(repo, id)
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Invoice 0231")
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()
                block(activity)
            }
        }
    }

    @Test
    fun `action chips keep their gaps and touch height`() {
        launch { activity ->
            val actions = activity.findViewById<ViewGroup>(R.id.ocrActions)
            assertEquals("two chip rows", 2, actions.childCount)
            val row1 = actions.getChildAt(0) as ViewGroup
            val row2 = actions.getChildAt(1) as ViewGroup

            assertEquals(
                "12dp between the two chip rows",
                dp(activity, 12),
                (row2.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            )

            for (row in listOf(row1, row2)) {
                assertTrue("every row has chips", row.childCount >= 2)
                for (i in 0 until row.childCount) {
                    val chip = row.getChildAt(i)
                    assertTrue(
                        "chip $i in a row must be at least 48dp tall to tap",
                        chip.minimumHeight >= dp(activity, 48)
                    )
                    val end = (chip.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
                    if (i < row.childCount - 1) {
                        assertEquals("8dp gap between chips", dp(activity, 8), end)
                    } else {
                        assertEquals("no trailing gap after the last chip", 0, end)
                    }
                }
            }
        }
    }

    @Test
    fun `the action chips stay in the same two rows`() {
        launch { activity ->
            val actions = activity.findViewById<ViewGroup>(R.id.ocrActions)
            val row1 = actions.getChildAt(0) as ViewGroup
            val row2 = actions.getChildAt(1) as ViewGroup
            assertEquals(3, row1.childCount)
            assertEquals(2, row2.childCount)
            assertEquals(row1, activity.findViewById<View>(R.id.btnCopy).parent)
            assertEquals(row2, activity.findViewById<View>(R.id.btnToWord).parent)
        }
    }
}
