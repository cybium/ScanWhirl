package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner QA run 1 (022/023_crop_panel): a preset must reshape the frame, the frame must have
 * draggable corner + edge handles, the preset row must scroll to its last chip, and the page
 * toolbar must be hidden while cropping.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CropPanelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun cropView(): CropView = CropView(app).apply {
        setImage(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1920)
    }

    private fun drag(view: CropView, fromIndex: Int, dx: Float, dy: Float) {
        val points = view.handlePoints()
        val x = points[fromIndex * 2]
        val y = points[fromIndex * 2 + 1]
        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0))
        view.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
    }

    @Test
    fun `a preset reshapes the frame to its aspect ratio and stays inside the image`() {
        val view = cropView()
        val ratio = 85.6f / 54f
        view.aspectRatio = ratio

        val crop = view.getCropRect()
        assertEquals("preset must change the frame", ratio, crop.width() / crop.height(), 0.02f)
        assertTrue(crop.left >= 0f && crop.top >= 0f)
        assertTrue(crop.right <= 400f && crop.bottom <= 600f)
    }

    @Test
    fun `corner and edge handles both move the frame`() {
        val view = cropView()
        val before = view.getCropRect()

        // Top-right corner (index 1) dragged in.
        drag(view, 1, -140f, 120f)
        val afterCorner = view.getCropRect()
        assertTrue("corner handle must move the frame", afterCorner.right < before.right - 1f)

        // Right edge (index 5) dragged left.
        drag(view, 5, -120f, 0f)
        val afterEdge = view.getCropRect()
        assertTrue("edge handle must move the frame", afterEdge.right < afterCorner.right - 1f)
    }

    @Test
    fun `a preset keeps its aspect while a handle is dragged`() {
        val view = cropView()
        val ratio = 35f / 45f
        view.aspectRatio = ratio
        drag(view, 0, 90f, 90f)
        val crop = view.getCropRect()
        assertEquals(ratio, crop.width() / crop.height(), 0.03f)
    }

    @Test
    fun `all eight handles are laid out inside the view`() {
        val view = cropView()
        val points = view.handlePoints()
        assertEquals("4 corners + 4 edges", 16, points.size)
        for (i in 0 until 8) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            assertTrue("handle $i x=$x", x in 0f..1080f)
            assertTrue("handle $i y=$y", y in 0f..1920f)
        }
    }

    @Test
    fun `crop mode hides the page toolbar and shows only cancel and apply`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 0)).use { scenario ->
            assertTrue("the page loads off main", pollUntil {
                var n = 0
                scenario.onActivity { a ->
                    n = (a.findViewById<View>(R.id.pager) as androidx.recyclerview.widget.RecyclerView)
                        .adapter?.itemCount ?: 0
                }
                n >= 1
            })
            scenario.onActivity { it.findViewById<View>(R.id.btnCrop)!!.performClick() }
            var open = false
            assertTrue("crop overlay opens", pollUntil {
                scenario.onActivity {
                    open = it.findViewById<View>(R.id.cropOverlay)!!.visibility == View.VISIBLE
                }
                open
            })
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<View>(R.id.pageDock).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btnClose).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.pageIndicator).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnCancelCrop).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnApplyCrop).visibility)
            }
            scenario.onActivity { it.findViewById<View>(R.id.btnCancelCrop)!!.performClick() }
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageDock).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.cropOverlay).visibility)
            }
        }
    }

    @Test
    fun `picking a preset chip reshapes the shown frame and the row scrolls to the end`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 0)).use { scenario ->
            assertTrue("the page loads off main", pollUntil {
                var n = 0
                scenario.onActivity { a ->
                    n = (a.findViewById<View>(R.id.pager) as androidx.recyclerview.widget.RecyclerView)
                        .adapter?.itemCount ?: 0
                }
                n >= 1
            })
            scenario.onActivity { it.findViewById<View>(R.id.btnCrop)!!.performClick() }
            var ready = false
            assertTrue("crop page decodes", pollUntil {
                scenario.onActivity {
                    val crop = it.findViewById<CropView>(R.id.cropView)
                    ready = crop.visibility == View.VISIBLE && crop.getCropRect().width() > 0f
                }
                ready
            })

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.chipCropIdCard)!!.performClick()
                val crop = activity.findViewById<CropView>(R.id.cropView).getCropRect()
                assertEquals(85.6f / 54f, crop.width() / crop.height(), 0.02f)

                val presets = activity.findViewById<android.widget.HorizontalScrollView>(R.id.cropPresets)
                presets.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST)
                )
                presets.layout(0, 0, 1080, presets.measuredHeight)
                assertTrue("end padding keeps the last chip clear", presets.paddingEnd > 0)
                assertFalse("end padding is drawn", presets.clipToPadding)
            }
        }
    }

    private fun pollUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }
}
