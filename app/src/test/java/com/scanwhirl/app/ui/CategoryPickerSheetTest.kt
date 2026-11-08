package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Dialog
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.scanwhirl.app.R
import com.scanwhirl.app.util.CategoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * T4.2: the category picker sheet — five coloured rows, Unsorted only when
 * it is the current value, the current row carries the check, and a tap
 * reports the category and closes. Additive (the sheet cannot compile
 * pre-fix), so this class is the guard rather than a runnable red-proof.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CategoryPickerSheetTest {

    @Test
    fun `five rows when the current category is one of the five`() {
        withSheet(CategoryRules.STUDY) { _, rows, _ ->
            assertEquals(
                CategoryRules.ALL,
                rows.tags()
            )
            assertFalse(
                "Unsorted must not appear when it is not the current value",
                rows.tags().contains(CategoryRules.UNSORTED)
            )
        }
    }

    @Test
    fun `unsorted row appears only when unsorted is current`() {
        withSheet(CategoryRules.UNSORTED) { _, rows, _ ->
            assertEquals(
                listOf(CategoryRules.UNSORTED) + CategoryRules.ALL,
                rows.tags()
            )
        }
        withSheet(null) { _, rows, _ ->
            assertFalse(
                "a null current value is not Unsorted — no extra row",
                rows.tags().contains(CategoryRules.UNSORTED)
            )
        }
    }

    @Test
    fun `the current row carries the check, the others do not`() {
        withSheet(CategoryRules.IDS) { _, rows, _ ->
            for (i in 0 until rows.childCount) {
                val row = rows.getChildAt(i) as LinearLayout
                val check = row.getChildAt(2) as ImageView
                val expected =
                    if (row.tag == CategoryRules.IDS) android.view.View.VISIBLE
                    else android.view.View.GONE
                assertEquals(
                    "check visibility for ${row.tag}",
                    expected,
                    check.visibility
                )
            }
        }
    }

    @Test
    fun `tapping a row reports the category and closes the sheet`() {
        var picked: String? = null
        withSheet(CategoryRules.STUDY, onPick = { picked = it }) { _, rows, dialog ->
            val receipts = rows.row(CategoryRules.RECEIPTS)
            assertNotNull("Receipts row must exist", receipts)
            receipts!!.performClick()

            assertEquals("onPick must report the tapped category", CategoryRules.RECEIPTS, picked)
            assertFalse("the sheet must close after a pick", dialog.isShowing)
        }
    }

    @Test
    fun `labels are the display names, one per row`() {
        withSheet(CategoryRules.OTHER) { activity, rows, dialog ->
            for (i in 0 until rows.childCount) {
                val row = rows.getChildAt(i) as LinearLayout
                val label = row.getChildAt(1) as TextView
                assertEquals(
                    CategoryRules.label(row.tag as String),
                    label.text.toString()
                )
                assertEquals(
                    "row tint must come from CategoryStyle for ${row.tag}",
                    com.scanwhirl.app.util.CategoryStyle.color(row.tag as String),
                    (row.getChildAt(0).background as android.graphics.drawable.GradientDrawable)
                        .color?.defaultColor
                )
            }
            assertEquals(
                activity.getString(R.string.category_picker_title),
                dialog.findViewById<TextView>(R.id.pickerTitle).text.toString()
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun withSheet(
        current: String?,
        onPick: (String) -> Unit = {},
        block: (Activity, LinearLayout, Dialog) -> Unit
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                CategoryPickerSheet.show(activity, current, onPick)
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("the picker sheet must be showing", dialog)
                val rows = dialog!!.findViewById<LinearLayout>(R.id.pickerRows)
                assertNotNull("sheet_category_picker must have a rows container", rows)
                block(activity, rows!!, dialog)
                dialog.dismiss()
            }
        }
    }

    private fun LinearLayout.tags(): List<String> =
        (0 until childCount).map { getChildAt(it).tag as String }

    private fun LinearLayout.row(tag: String): android.view.View? =
        (0 until childCount).map { getChildAt(it) }.firstOrNull { it.tag == tag }
}
