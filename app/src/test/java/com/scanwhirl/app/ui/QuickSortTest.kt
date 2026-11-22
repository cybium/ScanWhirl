package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * T4.4 â€” Quick Sort (plan Â§4.8): the Home nudge counts the Unsorted/Other
 * docs and opens the sheet, the Tools sheet carries the extra Quick sort
 * tile, picking saves instantly and advances, and the X close button (Rev 4
 * Â§3D) just closes â€” the nudge keeps the remaining count.
 *
 * The first three cases were written **before** the fix against existing
 * symbols only (R.id.nudge, R.id.btnTools, the wireframe's literal labels),
 * so they run red first. The sheet's own ids (qsProgress, qsChips, qsClose â€¦)
 * arrive with the fix â€” those guards are in the section below it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QuickSortTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    // ------------------------------------------------------------------
    // Red-proof (existing symbols only)
    // ------------------------------------------------------------------

    @Test
    fun `tools sheet opens quick sort and the nudge stays retired`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // M08: the Home nudge is retired; Quick sort lives in Tools.
                val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
                assertFalse(
                    "the nudge stays hidden even with an unsorted doc",
                    nudge.visibility == View.VISIBLE
                )

                openSheetFromTools(activity)
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("the Tools tile must open the Quick Sort sheet", sheet)
                assertTrue("the sheet must be showing", sheet!!.isShowing)
                assertNotNull(
                    "the sheet must be the queue, not the Tools sheet",
                    sheet.findViewById<View>(R.id.qsProgress)
                )
            }
        }
    }

    @Test
    fun `tools sheet offers a Quick sort tile`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnTools).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Tools sheet opens", sheet)
                assertNotNull(
                    "T4.4: the Tools sheet must carry the Quick sort tile",
                    labelByText(sheet!!.window!!.decorView, "Quick sort")
                )
                sheet.dismiss()
            }
        }
    }

    @Test
    fun `nudge stays hidden when nothing needs sorting`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Wait for the first render (the nudge updates there) before
                // asserting the absence, so the guard is never vacuous.
                val rail = activity.findViewById<View>(R.id.chipRail)
                assertTrue(
                    "the chip rail must be built first",
                    pollUntil { rail.visibility == View.VISIBLE }
                )
                val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
                assertFalse(
                    "no Unsorted/Other docs -> no nudge",
                    nudge.visibility == View.VISIBLE
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Guards added with the fix (they reference the new sheet ids
    // qsProgress/qsDone/qsChips/qsClose and the qs_progress/all_sorted
    // strings, so they could not compile before it â€” appended after the
    // implementation, same approach as T4.1/T4.3).
    // ------------------------------------------------------------------

    @Test
    fun `picking saves instantly and advances to the next document`() {
        seed("Lecture notes", CategoryRules.UNSORTED)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                openSheetFromTools(activity)

                val sheet = ShadowDialog.getLatestDialog()!!
                assertEquals(
                    "first document of two",
                    app.getString(R.string.qs_progress, 1, 2),
                    sheet.findViewById<TextView>(R.id.qsProgress)!!.text.toString()
                )

                tapChip(sheet, CategoryRules.RECEIPTS)
            }

            assertTrue(
                "picking must save the category immediately",
                pollUntil {
                    listOf("Lecture notes", "Electricity bill")
                        .mapNotNull { name -> docByName(name) }
                        .count { it.category == CategoryRules.RECEIPTS } == 1
                }
            )

            scenario.onActivity {
                val sheet = ShadowDialog.getLatestDialog()!!
                assertEquals(
                    "the sheet advances to the second document",
                    app.getString(R.string.qs_progress, 2, 2),
                    sheet.findViewById<TextView>(R.id.qsProgress)!!.text.toString()
                )

                // Skip the last one -> the queue is done ("All sorted").
                tapChip(sheet, "skip")
                assertEquals(
                    "the queue ends with All sorted",
                    app.getString(R.string.all_sorted),
                    sheet.findViewById<TextView>(R.id.qsDone)!!.text.toString()
                )
                assertTrue(
                    "the all-sorted banner replaces the queue UI",
                    sheet.findViewById<TextView>(R.id.qsDone)!!.visibility == View.VISIBLE
                )
                assertTrue(
                    "the chip row leaves with the queue",
                    sheet.findViewById<View>(R.id.qsChips)!!.visibility != View.VISIBLE
                )
            }
        }
    }

    @Test
    fun `closing mid queue leaves the rest unsorted`() {
        seed("Lecture notes", CategoryRules.UNSORTED)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                openSheetFromTools(activity)
                val sheet = ShadowDialog.getLatestDialog()!!
                tapChip(sheet, CategoryRules.RECEIPTS)

                // Rev 4 Â§3D: the X button just closes â€” no confirmation, the
                // remaining docs stay unsorted.
                sheet.findViewById<View>(R.id.qsClose).performClick()
                assertFalse(
                    "the X button must close the sheet",
                    sheet.isShowing
                )

                // M08: the nudge is retired; the remaining count is still visible
                // on the Unsorted chip, and the doc stays unsorted.
                assertFalse(
                    "the retired nudge stays hidden",
                    activity.findViewById<android.view.ViewGroup>(R.id.nudge).isVisible
                )
                // M08: the nudge is retired. The remaining count is carried by the
                // library itself — one doc is still unsorted (asserted below).
                assertFalse(
                    "the retired nudge stays hidden",
                    activity.findViewById<android.view.ViewGroup>(R.id.nudge).isVisible
                )

                val stillUnsorted = listOf("Lecture notes", "Electricity bill")
                    .mapNotNull { name -> docByName(name) }
                    .count { it.category == CategoryRules.UNSORTED }
                assertEquals(
                    "closing mid-queue categorizes nothing",
                    1,
                    stillUnsorted
                )
                assertNull(
                    "the closed sheet is gone",
                    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun openSheetFromTools(activity: MainActivity) {
        // M08: the nudge is retired; Quick sort is opened from the Tools sheet.
        activity.findViewById<android.view.View>(R.id.btnTools).performClick()
        val tools = ShadowDialog.getLatestDialog()
        assertNotNull("Tools sheet opens", tools)
        tools!!.findViewById<android.view.View>(R.id.tile_quick_sort)!!.performClick()
        assertNotNull(
            "the Tools tile must open the Quick Sort sheet",
            ShadowDialog.getLatestDialog()
        )
    }

    /** Taps the queue chip tagged [tag] (categories carry their value; skip is "skip"). */
    private fun tapChip(sheet: android.app.Dialog, tag: String) {
        val group = sheet.findViewById<android.view.ViewGroup>(R.id.qsChips)
        assertNotNull("the sheet must carry its chip row", group)
        val chip = (0 until group!!.childCount).map { group.getChildAt(it) }
            .firstOrNull { it.tag == tag }
        assertNotNull("chip for '$tag' must exist", chip)
        chip!!.performClick()
    }

    private fun docByName(name: String) = runBlocking {
        repo.listDocuments().firstOrNull { it.name == name }
    }

    private fun labelByText(root: View, wanted: String): TextView? {
        if (root is TextView && root.text?.toString() == wanted) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                labelByText(root.getChildAt(i), wanted)?.let { return it }
            }
        }
        return null
    }

    private fun seed(name: String, category: String?): String = runBlocking {
        val uri = contentUri(jpegBytes())
        repo.saveScan(listOf(uri), null, name, category = category).id
    }

    private fun contentUri(bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://quick_sort_${System.nanoTime()}.jpg")
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
