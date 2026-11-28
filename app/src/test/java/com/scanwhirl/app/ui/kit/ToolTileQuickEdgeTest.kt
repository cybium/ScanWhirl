package com.scanwhirl.app.ui.kit

import android.app.Application
import android.view.Gravity
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.kit.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * VQA V081: the Home quick strip scrolls, so its last tile is only partly visible. A tile
 * must keep its icon and its name inside the LEFT part of its own box, otherwise the visible
 * sliver shows a cut glyph and no readable label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ToolTileQuickEdgeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a partly visible quick tile keeps its whole icon and label in the visible edge`() {
        val tile = ToolTile(app).apply {
            asQuick()
            bind(ToolRegistry.byId("read_text")!!)
        }
        val density = app.resources.displayMetrics.density
        val dp = { value: Float -> (value * density).toInt() }
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(dp(88f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        tile.layout(0, 0, tile.measuredWidth, tile.measuredHeight)

        assertTrue(
            "the icon hugs the tile's leading edge (${tile.iconBox.left / density}dp)",
            tile.iconBox.left <= dp(5f)
        )
        // Only ~72dp of this tile is on screen in the VQA capture; the icon must fit there.
        assertTrue(
            "the whole icon fits the visible sliver (${tile.iconBox.right / density}dp)",
            tile.iconBox.right <= dp(72f)
        )
        assertEquals(
            "the label starts at the leading edge, not the clipped trailing edge",
            Gravity.START,
            tile.labelView.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
        )
        assertTrue("the label is still whole", tile.labelView.text.isNotBlank())
    }
}
