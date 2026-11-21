package com.scanwhirl.app.ux130.l2

import android.app.Application
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.CropView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L2-01: the canvas crop surface must be operable without dragging pixels.
 * The checks use the real Android virtual-node provider, not a test-only seam,
 * so a passing result describes what TalkBack / switch access can enumerate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CropAccessibilityTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun cropView(): CropView = CropView(app).apply {
        setImage(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1920)
    }

    private fun provider(view: CropView): AccessibilityNodeProviderCompat {
        val found = ViewCompat.getAccessibilityNodeProvider(view)
        assertNotNull("CropView must expose virtual nodes for TalkBack", found)
        return found!!
    }

    @Test
    fun `the crop frame exposes eight stable virtual handles with position labels`() {
        val view = cropView()
        val provider = provider(view)
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProviderCompat.HOST_VIEW_ID)
        assertNotNull(host)
        assertEquals("4 corners + 4 edges", 8, host!!.childCount)

        val labels = (1..8).map { id ->
            provider.createAccessibilityNodeInfo(id)?.text?.toString().orEmpty()
        }
        assertEquals(app.getString(R.string.ux130_l2_crop_corner_tl), labels[0])
        assertEquals(app.getString(R.string.ux130_l2_crop_corner_tr), labels[1])
        assertEquals(app.getString(R.string.ux130_l2_crop_corner_br), labels[2])
        assertEquals(app.getString(R.string.ux130_l2_crop_corner_bl), labels[3])
        assertEquals(app.getString(R.string.ux130_l2_crop_edge_top), labels[4])
        assertEquals(app.getString(R.string.ux130_l2_crop_edge_right), labels[5])
        assertEquals(app.getString(R.string.ux130_l2_crop_edge_bottom), labels[6])
        assertEquals(app.getString(R.string.ux130_l2_crop_edge_left), labels[7])
    }

    @Test
    fun `each handle offers named move actions`() {
        val view = cropView()
        val provider = provider(view)
        val labels = provider.createAccessibilityNodeInfo(1)!!.actionList.map { it.label?.toString() }
        assertTrue(
            "corner exposes the four move actions: $labels",
            labels.contains(app.getString(R.string.ux130_l2_crop_move_left)) &&
                labels.contains(app.getString(R.string.ux130_l2_crop_move_right)) &&
                labels.contains(app.getString(R.string.ux130_l2_crop_move_up)) &&
                labels.contains(app.getString(R.string.ux130_l2_crop_move_down))
        )
        val edgeLabels = provider.createAccessibilityNodeInfo(5)!!.actionList.map { it.label?.toString() }
        assertTrue(
            "right edge exposes left/right moves: $edgeLabels",
            edgeLabels.contains(app.getString(R.string.ux130_l2_crop_move_left)) &&
                edgeLabels.contains(app.getString(R.string.ux130_l2_crop_move_right))
        )
    }

    @Test
    fun `a move action changes the crop rectangle through the real geometry`() {
        val view = cropView()
        val provider = provider(view)
        val before = view.getCropRect()
        assertTrue(provider.performAction(1, ACTION_RIGHT, null))
        val after = view.getCropRect()
        assertTrue("top-left corner moved right", after.left > before.left + 1f)
        assertTrue("other corner unchanged", after.bottom == before.bottom && after.right == before.right)
    }

    @Test
    fun `an edge move keeps a preset aspect ratio and the image bounds`() {
        val view = cropView()
        val ratio = 85.6f / 54f
        view.aspectRatio = ratio
        val provider = provider(view)
        val before = view.getCropRect()
        assertTrue(provider.performAction(5, ACTION_LEFT, null))
        val after = view.getCropRect()
        assertTrue("right edge moved left", after.right < before.right - 1f)
        assertEquals(ratio, after.width() / after.height(), 0.05f)
        assertTrue(after.left >= 0f && after.right <= 400f && after.top >= 0f && after.bottom <= 600f)
    }

    @Test
    fun `repeated moves stop at the opposite boundary and never invert the rectangle`() {
        val view = cropView()
        val provider = provider(view)
        repeat(40) { provider.performAction(1, ACTION_RIGHT, null) }
        repeat(40) { provider.performAction(1, ACTION_DOWN, null) }
        val crop = view.getCropRect()
        assertTrue("rectangle stays positive", crop.width() > 0f && crop.height() > 0f)
        assertTrue("inside image", crop.right <= 400f && crop.bottom <= 600f)
    }

    @Test
    fun `accessibility focus marks a handle and keyboard arrows move the selected handle`() {
        val view = cropView()
        val provider = provider(view)
        assertTrue(
            provider.performAction(2, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
        )
        assertEquals(2, view.getTag(R.id.ux130_l2_crop_handle))

        val before = view.getCropRect()
        assertTrue("D-pad is a keyboard alternative", view.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, null))
        assertTrue("bottom-right corner moved left", view.getCropRect().right < before.right - 1f)
    }

    private companion object {
        const val ACTION_LEFT = 0x01000001
        const val ACTION_RIGHT = 0x01000002
        const val ACTION_UP = 0x01000003
        const val ACTION_DOWN = 0x01000004
    }
}
