package com.scanwhirl.app.ui.pagegrid

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Organize pages without a finger: each tile says "Page 3 of 10, selected" and offers
 * Move up / Move down, which is the accessibility twin of long-press-and-drag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageGridAccessibilityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun tileOf(grid: PageGridView, index: Int): View? =
        grid.findViewHolderForAdapterPosition(index)?.itemView

    private fun actionsOf(view: View): List<Int> {
        val info = AccessibilityNodeInfoCompat.obtain()
        ViewCompat.onInitializeAccessibilityNodeInfo(view, info)
        return info.actionList.map { it.id }
    }

    @Test
    fun `a selected tile names its position and state`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Grid a11y")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(
                    "the grid loads",
                    pollUntil {
                        a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                )
                val grid = a.findViewById<PageGridView>(R.id.pageList)
                grid.onLongPress?.invoke(1)
                idle()

                val tile = requireNotNull(tileOf(grid, 1))
                assertEquals(
                    "Page 2 of 3, selected",
                    tile.contentDescription?.toString()
                )
                val actions = actionsOf(tile)
                assertTrue("move up is offered", R.id.a11y_move_up in actions)
                assertTrue("move down is offered", R.id.a11y_move_down in actions)

                val unselected = requireNotNull(tileOf(grid, 0))
                assertEquals("Page 1 of 3", unselected.contentDescription?.toString())
            }
        }
    }

    @Test
    fun `move down is the accessibility twin of a drag`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Grid move")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(
                    "the grid loads",
                    pollUntil {
                        a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                )
                val grid = a.findViewById<PageGridView>(R.id.pageList)
                val firstPage = grid.pageAt(0)
                val secondPage = grid.pageAt(1)
                grid.onLongPress?.invoke(0)
                idle()

                val tile = requireNotNull(tileOf(grid, 0))
                assertTrue("no move up on the first tile", R.id.a11y_move_up !in actionsOf(tile))
                tile.performAccessibilityAction(R.id.a11y_move_down, null)
                idle()

                assertEquals("the pages swapped", firstPage, grid.pageAt(1))
                assertEquals(secondPage, grid.pageAt(0))
                assertEquals(
                    "the moved tile announces its new slot",
                    "Page 2 of 3",
                    requireNotNull(tileOf(grid, 1)).contentDescription?.toString()
                )
            }
        }
    }
}
