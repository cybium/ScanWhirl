package com.scanwhirl.app.ux130.l2

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.PageViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
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
import java.io.File

/**
 * L2-03: one honest status, an undoable filter choice, a labelled preview and
 * named zoom actions. All checks drive the real activities and resources.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReviewControlsTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val dirs = mutableListOf<File>()

    @After fun clean() {
        ShadowDialog.reset()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun staging(pages: Int = 2): File {
        val dir = File(app.cacheDir, "ux130_l2_review_${System.nanoTime()}").apply { mkdirs() }
        repeat(pages) { File(dir, String.format("page_%03d.jpg", it + 1)).writeBytes(ImportFixtures.image(width = 100)) }
        dirs += dir
        return dir
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(app.mainLooper).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        shadowOf(app.mainLooper).idle()
        assertTrue("timed out waiting for condition", condition())
    }

    @Test
    fun `filter change offers undo that restores the prior stored filter`() {
        val dir = staging()
        val filters = File(dir, "filters.txt")
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Filter test")
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.btnToolFilter).performClick() }
            await { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetItems) != null }

            ShadowDialog.getLatestDialog()!!
                .findViewById<ViewGroup>(R.id.swSheetItems)!!
                .getChildAt(1).performClick()
            shadowOf(app.mainLooper).idle()

            val changed = filters.readText()
            assertTrue("a non-default filter was stored: $changed", changed.contains("=enhance"))

            var undone = false
            scenario.onActivity { activity ->
                val action = activity.window.decorView
                    .findViewById<TextView>(com.google.android.material.R.id.snackbar_action)
                assertNotNull("filter change must offer Undo", action)
                action.performClick()
                undone = true
            }
            assertTrue(undone)
            shadowOf(app.mainLooper).idle()
            await {
                val restored = filters.readText()
                restored.contains("page_001.jpg=original") && !restored.contains("=enhance")
            }
        }
    }

    @Test
    fun `the visible review status is one line with page position and unsaved state`() {
        val dir = staging(3)
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Status test")
        ).use { scenario ->
            scenario.onActivity { a ->
                val status = a.findViewById<TextView>(R.id.reviewStatus).text.toString()
                assertTrue("status names the current page: $status", status.startsWith("Page 1 of 3"))
                assertTrue("status names the unsaved state: $status", status.endsWith("Not saved yet"))
                assertEquals(app.getString(R.string.ux130_l2_review_status, 1, 3), status.take(11))
            }
        }
    }

    @Test
    fun `pager layout has a meaningful default label and a zoomable page names its position`() {
        val inflated = LayoutInflater.from(app).inflate(R.layout.item_pager_page, null, false)
        val labelled = inflated.findViewById<View>(R.id.pageImage).contentDescription?.toString()
        assertEquals(app.getString(R.string.ux130_l2_preview_page_default), labelled)

        val id = DocFixtures.saved(repo, app, pages = 2)
        ActivityScenario.launch<PageViewerActivity>(
            PageViewerActivity.intent(app, id, 1)
        ).use { scenario ->
            var image: View? = null
            await {
                scenario.onActivity { a ->
                    val pager = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pager)
                    pager.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)
                    )
                    pager.layout(0, 0, 1080, 1200)
                    image = pager.findViewHolderForAdapterPosition(1)?.itemView
                        ?.findViewById(R.id.pageImage)
                }
                image != null && image!!.contentDescription?.toString() == "Page 2 of 2"
            }
            val info = AccessibilityNodeInfoCompat.obtain()
            ViewCompat.onInitializeAccessibilityNodeInfo(image!!, info)
            val labels = info.actionList.map { it.label?.toString() }
            assertTrue(
                "page image exposes named zoom actions: $labels",
                labels.contains(app.getString(R.string.ux130_l2_zoom_page)) &&
                    labels.contains(app.getString(R.string.ux130_l2_close_zoom))
            )
            info.recycle()
        }
    }
}
