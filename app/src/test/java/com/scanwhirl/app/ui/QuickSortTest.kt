package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.widget.TextView
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
 * T4.4 — Quick Sort (plan §4.8): the Home nudge counts the Unsorted/Other
 * docs and opens the sheet, the Tools sheet carries the extra Quick sort
 * tile, picking saves instantly and advances, and the X close button (Rev 4
 * §3D) just closes — the nudge keeps the remaining count.
 *
 * The first three cases were written **before** the fix against existing
 * symbols only (R.id.nudge, R.id.btnTools, the wireframe's literal labels),
 * so they run red first. The sheet's own ids (qsProgress, qsChips, qsClose …)
 * arrive with the fix — those guards are in the section below it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QuickSortTest {

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
    fun `nudge shows the unsorted count and opens quick sort`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
                assertTrue(
                    "T4.4: the nudge must be visible while an unsorted doc exists",
                    pollUntil { nudge.visibility == View.VISIBLE }
                )
                assertTrue("the nudge must carry its label", nudge.childCount > 0)
                val text = (nudge.getChildAt(0) as TextView).text.toString()
                assertTrue(
                    "the nudge counts the unsorted docs (got '$text')",
                    text.contains("1 unsorted")
                )
                assertTrue(
                    "the nudge offers Quick sort (got '$text')",
                    text.contains("Quick sort")
                )

                nudge.performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull(
                    "T4.4: the nudge must open the Quick Sort sheet",
                    sheet
                )
                assertTrue("the sheet must be showing", sheet!!.isShowing)
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
    // strings, so they could not compile before it — appended after the
    // implementation, same approach as T4.1/T4.3).
    // ------------------------------------------------------------------

    @Test
    fun `picking saves instantly and advances to the next document`() {
        seed("Lecture notes", CategoryRules.UNSORTED)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                openSheetFromNudge(activity)

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
    fun `closing mid queue leaves the rest unsorted and the nudge keeps counting`() {
        seed("Lecture notes", CategoryRules.UNSORTED)
        seed("Electricity bill", CategoryRules.UNSORTED)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                openSheetFromNudge(activity)
                val sheet = ShadowDialog.getLatestDialog()!!
                tapChip(sheet, CategoryRules.RECEIPTS)

                // Rev 4 §3D: the X button just closes — no confirmation, the
                // remaining docs stay unsorted.
                sheet.findViewById<View>(R.id.qsClose).performClick()
                assertFalse(
                    "the X button must close the sheet",
                    sheet.isShowing
                )

                assertTrue(
                    "the nudge keeps the remaining count (1 unsorted)",
                    pollUntil {
                        val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
                        nudge.visibility == View.VISIBLE && nudge.childCount > 0 &&
                            (nudge.getChildAt(0) as TextView).text.toString()
                                .contains("1 unsorted")
                    }
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

    private fun openSheetFromNudge(activity: MainActivity) {
        val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
        assertTrue(
            "the nudge must be visible to open the sheet",
            pollUntil { nudge.visibility == View.VISIBLE }
        )
        nudge.performClick()
        assertNotNull(
            "T4.4: the nudge must open the Quick Sort sheet",
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
