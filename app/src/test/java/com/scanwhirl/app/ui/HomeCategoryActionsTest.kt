package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
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

/**
 * T4.3 — change a category from Home: the card chip opens the picker, the
 * selection bar's Move moves the whole selection (toast + exit), and a
 * long-press opens the quick-actions sheet (owner decision: the sheet
 * replaces T3.7's long-press → selection entry; `Select` still enters it).
 *
 * Written **before** the fix against existing symbols only (R.id.catChip,
 * R.id.btnSelMove, R.id.pickerRows), so every case runs red first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeCategoryActionsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    @Test
    fun `long press opens the quick actions sheet`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()

                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull(
                    "T4.3: long-press must open the quick-actions sheet (not selection mode)",
                    dialog
                )
                assertTrue("sheet must be showing", dialog!!.isShowing)
            }
        }
    }

    @Test
    fun `the card category chip opens the picker`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val chip = list.findViewHolderForAdapterPosition(0)!!
                    .itemView.findViewById<View>(R.id.catChip)
                chip.performClick()

                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("T4.3: the category chip must open the picker", dialog)
                assertNotNull(
                    "the picker sheet must carry its rows (T4.2)",
                    dialog!!.findViewById<View>(R.id.pickerRows)
                )
            }
        }
    }

    @Test
    fun `Move in the selection bar opens the picker`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                // Capture the card first: Select re-lays out the list and the
                // holder lookup comes back null straight after the click.
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card.performClick()

                activity.findViewById<View>(R.id.btnSelMove).performClick()

                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("T4.3: Move must open the category picker", dialog)
                assertNotNull(
                    dialog!!.findViewById<View>(R.id.pickerRows)
                )
            }
        }
    }

    @Test
    fun `bulk move recategorizes the selection and exits it`() {
        val id = seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card.performClick()
                activity.findViewById<View>(R.id.btnSelMove).performClick()

                pickRow(CategoryRules.STUDY)
            }

            assertTrue(
                "the selected document must land in the picked category",
                pollUntil { repo.getDocument(id)?.category == CategoryRules.STUDY }
            )

            scenario.onActivity { activity ->
                val header = activity.findViewById<View>(R.id.selectionHeader)
                assertTrue(
                    "T4.3: bulk Move exits selection mode",
                    header.visibility != View.VISIBLE
                )
                assertEquals(
                    "toast confirms the move (plan: \"Moved N to Study\")",
                    "Moved 1 to Study",
                    ShadowToast.getTextOfLatestToast()?.toString()
                )
            }
        }
    }

    @Test
    fun `chip move recategorizes the document`() {
        val id = seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!
                    .itemView.findViewById<View>(R.id.catChip).performClick()

                pickRow(CategoryRules.RECEIPTS)
            }

            assertTrue(
                "picking on the chip's picker must save the new category",
                pollUntil { repo.getDocument(id)?.category == CategoryRules.RECEIPTS }
            )
        }
    }

    // ------------------------------------------------------------------
    // Guards added with the fix (they reference the new row ids, so they
    // could not compile before it — same approach as T4.1).
    // ------------------------------------------------------------------

    @Test
    fun `the sheet lists rename move share delete and move opens the picker`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()

                val sheet = ShadowDialog.getLatestDialog()!!
                assertEquals(
                    "Lecture notes",
                    sheet.findViewById<android.widget.TextView>(R.id.qaTitle)!!.text.toString()
                )
                assertEquals(
                    app.getString(R.string.rename),
                    sheet.findViewById<android.widget.TextView>(R.id.qaRename)!!.text.toString()
                )
                assertEquals(
                    app.getString(R.string.move_to_category),
                    sheet.findViewById<android.widget.TextView>(R.id.qaMove)!!.text.toString()
                )
                assertEquals(
                    app.getString(R.string.share_pdf),
                    sheet.findViewById<android.widget.TextView>(R.id.qaShare)!!.text.toString()
                )
                assertEquals(
                    app.getString(R.string.trash_move),
                    sheet.findViewById<android.widget.TextView>(R.id.qaDelete)!!.text.toString()
                )

                // Move → the T4.2 picker (same flow the bulk bar uses).
                sheet.findViewById<View>(R.id.qaMove).performClick()
                val picker = ShadowDialog.getLatestDialog()
                assertNotNull("Move must open the category picker", picker)
                assertNotNull(picker!!.findViewById<View>(R.id.pickerRows))
            }
        }
    }

    @Test
    fun `quick action rename saves the new name`() {
        val id = seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()

                ShadowDialog.getLatestDialog()!!
                    .findViewById<View>(R.id.qaRename).performClick()

                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val editText = firstEditText(dialog.window!!.decorView)
                assertNotNull("the rename prompt must carry an input", editText)
                editText!!.setText("Physics notes")
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }

            assertTrue(
                "Rename must persist on the document",
                pollUntil { repo.getDocument(id)?.name == "Physics notes" }
            )
        }
    }

    @Test
    fun `quick action Move to Trash removes the document from Home and Undo restores it`() {
        val id = seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()

                ShadowDialog.getLatestDialog()!!
                    .findViewById<View>(R.id.qaDelete).performClick()
            }

            assertTrue(
                "M07: Move to Trash takes the document out of the library at once",
                pollUntil { repo.getDocument(id) == null }
            )
            assertTrue("it is in Trash, whole", repo.listTrash().any { it.originalId == id })

            var undone = false
            assertTrue("the Undo snackbar appears", pollUntil {
                scenario.onActivity { a ->
                    val action = a.findViewById<android.widget.Button>(
                        com.google.android.material.R.id.snackbar_action
                    )
                    if (action != null && action.text.toString() == app.getString(R.string.undo)) {
                        action.performClick()
                        undone = true
                    }
                }
                undone
            })
            assertTrue("Undo restores it under the same id", pollUntil { repo.getDocument(id) != null })
            assertTrue("and Trash is empty again", repo.listTrash().isEmpty())
        }
    }

    /** The first EditText inside a dialog's decor (SwDialog.input has no id). */
    private fun firstEditText(view: android.view.View): android.widget.EditText? {
        if (view is android.widget.EditText) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                firstEditText(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Taps the picker row (T4.2) whose tag is [category]. */
    private fun pickRow(category: String) {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the picker must be open", dialog)
        val rows = dialog!!.findViewById<android.widget.LinearLayout>(R.id.pickerRows)
        assertNotNull("the picker must have rows", rows)
        val row = (0 until rows!!.childCount).map { rows.getChildAt(it) }
            .firstOrNull { it.tag == category }
        assertNotNull("row for $category must exist", row)
        row!!.performClick()
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://cat_actions_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seed(name: String, category: String?): String = runBlocking {
        val uri = contentUri(jpegBytes())
        repo.saveScan(listOf(uri), null, name, category = category).id
    }

    private fun forceLayout(rv: androidx.recyclerview.widget.RecyclerView) {
        if (rv.childCount == 0) {
            val w = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val h = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            rv.measure(w, h)
            rv.layout(0, 0, 1080, 1920)
        }
    }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }
}
