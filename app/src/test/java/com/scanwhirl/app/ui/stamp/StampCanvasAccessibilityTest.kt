package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stamp canvas is one custom view: its marks must be operable without a precision drag.
 * TalkBack gets Move, Resize, Rotate and Delete as accessibility actions, plus a description
 * that names the selected mark and the page it sits on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampCanvasAccessibilityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun ink(): Bitmap = Bitmap.createBitmap(80, 50, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
        Canvas(this).drawRect(6f, 8f, 74f, 42f, Paint().apply { color = Color.rgb(120, 20, 160) })
    }

    private fun node(canvas: StampCanvasView): AccessibilityNodeInfoCompat =
        AccessibilityNodeInfoCompat.obtain().also {
            ViewCompat.onInitializeAccessibilityNodeInfo(canvas, it)
        }

    @Test
    fun `a selected mark offers move resize rotate and delete`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Canvas a11y")
        val screen = activity(id)
        screen.placeImportedStampForTest(ink())

        val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)
        assertTrue(
            "the canvas names the selected mark: ${canvas.contentDescription}",
            canvas.contentDescription.contains("Picture")
        )
        assertTrue(
            "and its page: ${canvas.contentDescription}",
            canvas.contentDescription.contains("page 1 of 2")
        )

        val actions = node(canvas).actionList.map { it.id }
        for (action in listOf(
            R.id.a11y_move_left, R.id.a11y_move_right, R.id.a11y_move_up, R.id.a11y_move_down,
            R.id.a11y_grow, R.id.a11y_shrink, R.id.a11y_rotate_cw, R.id.a11y_rotate_ccw,
            R.id.a11y_delete_mark
        )) {
            assertTrue("action $action is missing from $actions", action in actions)
        }

        val before = screen.editor.marks().single() as PageStamp.Mark.Picture
        canvas.performAccessibilityAction(R.id.a11y_move_right, null)
        val moved = screen.editor.marks().single() as PageStamp.Mark.Picture
        assertEquals("move right by 2%", before.box.left + 0.02f, moved.box.left, 0.001f)
        assertEquals(before.box.top, moved.box.top, 0.001f)

        val widthBefore = moved.box.right - moved.box.left
        canvas.performAccessibilityAction(R.id.a11y_grow, null)
        val grown = screen.editor.marks().single() as PageStamp.Mark.Picture
        assertTrue("grow widens the box", grown.box.right - grown.box.left > widthBefore)

        canvas.performAccessibilityAction(R.id.a11y_rotate_cw, null)
        val rotated = screen.editor.marks().single() as PageStamp.Mark.Picture
        assertEquals("rotate clockwise by 5 degrees", 5f, rotated.rotation, 0.01f)

        canvas.performAccessibilityAction(R.id.a11y_delete_mark, null)
        assertTrue("delete removes the selected mark", screen.editor.marks().isEmpty())
    }

    @Test
    fun `an empty canvas announces the page and offers no mark actions`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Canvas empty")
        val screen = activity(id)
        val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)

        assertTrue(
            "the page is announced: ${canvas.contentDescription}",
            canvas.contentDescription.contains("1 of 3")
        )
        val ids = node(canvas).actionList.map { it.id }
        assertFalse("nothing to move", R.id.a11y_move_left in ids)
        assertFalse("nothing to delete", R.id.a11y_delete_mark in ids)
    }
}
