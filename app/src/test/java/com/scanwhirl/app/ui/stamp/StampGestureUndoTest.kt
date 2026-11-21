package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

/**
 * E1 defect 1: drag / resize / rotate must record ONE undo step per gesture, not one per
 * ACTION_MOVE. A 60-event drag used to push 60 snapshots, evicting the earlier "add mark" step
 * (MAX_HISTORY 50) and needs 60 undos to get back. The test drives the real canvas with real
 * touch events so it exercises the whole canvas -> listener -> editor path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampGestureUndoTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(context, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    /** Centre of [box] in view pixels, using the canvas' own page-rect geometry. */
    private fun StampCanvasView.centreOf(box: PageStamp.Box): FloatArray {
        val bmp = pageBitmap ?: error("the canvas needs a page bitmap")
        val pad = resources.displayMetrics.density * 10f
        val availW = max(1f, width - 2 * pad)
        val availH = max(1f, height - 2 * pad)
        val scale = min(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return floatArrayOf(
            left + ((box.left + box.right) / 2f) * w,
            top + ((box.top + box.bottom) / 2f) * h
        )
    }

    private fun touch(canvas: StampCanvasView, action: Int, time: Long, x: Float, y: Float) {
        canvas.onTouchEvent(MotionEvent.obtain(0L, time, action, x, y, 0))
    }

    @Test
    fun `a 60-event drag is one undo step and the add step survives`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val box = PageStamp.Box(0.2f, 0.2f, 0.6f, 0.3f)
        screen.editor.add(PageStamp.Mark.Text("DRAG ME", box))
        // Step 1 in the history is the add; the gesture under test must be step 2 and the last.
        assertEquals(1, screen.editor.marks().size)

        val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)
        canvas.layout(0, 0, 600, 900)
        canvas.pageBitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        canvas.marks = screen.editor.marks()
        canvas.selectedIndex = 0

        val (x, y) = canvas.centreOf(box)
        touch(canvas, MotionEvent.ACTION_DOWN, 0L, x, y)
        for (i in 1..60) touch(canvas, MotionEvent.ACTION_MOVE, i.toLong(), x + i, y)
        touch(canvas, MotionEvent.ACTION_UP, 61L, x + 60, y)

        val moved = (screen.editor.marks().single() as PageStamp.Mark.Text).box.left
        assertTrue("the drag must actually move the mark", moved > box.left + 0.01f)

        // Exactly one undo returns to the pre-drag position.
        assertTrue("one undo must undo the whole drag", screen.editor.undo())
        val restored = (screen.editor.marks().single() as PageStamp.Mark.Text).box.left
        assertEquals("one undo returns to the pre-drag position", box.left, restored, 0.0001f)
        assertFalse("redo is cleared once by the gesture", screen.editor.canRedo)

        // The add step was not evicted by 60 intermediate snapshots.
        assertTrue("the add-mark step must survive", screen.editor.undo())
        assertTrue("a second undo removes the mark", screen.editor.marks().isEmpty())
    }
}
