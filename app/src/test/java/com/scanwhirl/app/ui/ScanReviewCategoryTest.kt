package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * T4.1: the category rail on the scan-review screen — pre-select from the
 * name, re-suggest live while the name changes, stop when the owner picks a
 * chip by hand, and hand the choice to `saveScan`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanReviewCategoryTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @Test
    fun `an unsorted name shows the hint with nothing selected`() {
        withReview("Doc") { activity ->
            val caption = activity.findViewById<TextView>(R.id.categoryCaption)
            assertEquals(
                "the hint must be shown while nothing is selected",
                View.VISIBLE,
                caption.visibility
            )
            assertEquals(
                app.getString(R.string.category_unsorted_hint),
                caption.text.toString()
            )
            val rail = activity.findViewById<ChipGroup>(R.id.categoryRail)
            assertEquals(CategoryRules.ALL.size, rail.childCount)
            assertTrue(
                "no chip may be selected for an unsorted name",
                (0 until rail.childCount).none { (rail.getChildAt(it) as Chip).isChecked }
            )
        }
    }

    @Test
    fun `typing a receipt name preselects Receipts`() {
        withReview("Doc") { activity ->
            activity.findViewById<EditText>(R.id.titleInput).setText("Invoice May")

            val rail = activity.findViewById<ChipGroup>(R.id.categoryRail)
            assertTrue(
                "\"Invoice May\" must preselect the Receipts chip",
                rail.chip(CategoryRules.RECEIPTS).isChecked
            )
            assertFalse(
                "the hint must hide once something is selected",
                activity.findViewById<TextView>(R.id.categoryCaption).isVisible
            )
        }
    }

    @Test
    fun `a manual pick saves that category and stops the live suggestion`() {
        val staging = stagingWithPage()
        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            var opened: Intent? = null
            scenario.onActivity { activity ->
                val rail = activity.findViewById<ChipGroup>(R.id.categoryRail)
                rail.chip(CategoryRules.STUDY).performClick()

                // The name now suggests Receipts — a manual pick must win.
                activity.findViewById<EditText>(R.id.titleInput).setText("Invoice May")
                assertTrue(
                    "the manual pick must survive a rename",
                    rail.chip(CategoryRules.STUDY).isChecked
                )
                assertFalse(
                    "the live suggestion must not override a manual pick",
                    rail.chip(CategoryRules.RECEIPTS).isChecked
                )

                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            val done = pollUntil {
                scenario.onActivity { activity ->
                    opened = shadowOf(activity).peekNextStartedActivity()
                }
                opened != null
            }
            assertTrue("save must open the document viewer", done)

            val doc = opened!!.getStringExtra("doc_id")?.let { repo.getDocument(it) }
            assertNotNull("the saved document must exist", doc)
            assertEquals(
                "the chip picked by hand must be the saved category",
                CategoryRules.STUDY,
                doc!!.category
            )
        }
        staging.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun withReview(name: String, block: (ScanReviewActivity) -> Unit) {
        val staging = stagingWithPage()
        val intent = ScanReviewActivity.intent(app, staging.path, null, name)
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity(block)
        }
        staging.deleteRecursively()
    }

    private fun ChipGroup.chip(tag: String): Chip =
        (0 until childCount).map { getChildAt(it) as Chip }.first { it.tag == tag }

    private fun stagingWithPage(): File =
        File(app.cacheDir, "catrail_${System.nanoTime()}").apply { mkdirs() }
            .also { File(it, "page_000.jpg").writeBytes(jpegBytes()) }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            310, 444, android.graphics.Bitmap.Config.ARGB_8888
        )
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
