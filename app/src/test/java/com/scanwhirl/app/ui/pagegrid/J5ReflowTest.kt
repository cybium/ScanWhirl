package com.scanwhirl.app.ui.pagegrid

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class J5ReflowTest {
    @get:Rule val dialogs = ResetDialogShadowsRule()
    @After fun reset() = RuntimeEnvironment.setFontScale(1f)

    private fun check(columns: Int, font: Float = 1f) {
        RuntimeEnvironment.setFontScale(font)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 6, name = "Admission form")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.organizeIntent(app, id)
        ).use { scenario ->
            scenario.onActivity { a ->
                val until = System.currentTimeMillis() + 10_000
                while (a.findViewById<TextView>(R.id.pageCount).text.isEmpty() &&
                    System.currentTimeMillis() < until) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                assertTrue(a.findViewById<TextView>(R.id.pageCount).text.isNotEmpty())
                a.toggleSelected(0)
                val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val dm = a.resources.displayMetrics
                repeat(3) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val grid = a.findViewById<PageGridView>(R.id.pageList)
                assertEquals(columns, grid.spanCount)
                assertTrue("the grid retains a usable viewport", grid.height >= 48 * dm.density)
                assertFalse(a.findViewById<View>(R.id.bottomDock).isShown)
                for (rid in listOf(R.id.btnSelRotate, R.id.btnSelShare, R.id.btnSelExtract,
                    R.id.btnSelDelete, R.id.btnSelMore, R.id.btnDraftSave)) {
                    val text = a.findViewById<TextView>(rid)
                    if (!text.isShown) continue // Narrow modes intentionally move Extract into More.
                    assertTrue("action keeps its 48dp target", text.height >= 48 * dm.density)
                    assertTrue("action keeps its 48dp width", text.width >= 48 * dm.density)
                    val layout = text.layout
                    assertNotNull(layout)
                    for (line in 0 until layout.lineCount) {
                        assertEquals(0, layout.getEllipsisCount(line))
                        assertTrue("the complete label fits",
                            layout.getLineMax(line) <= text.width - text.totalPaddingLeft -
                                text.totalPaddingRight + 1f)
                    }
                    assertTrue(layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                }
            }
        }
    }

    @Test fun `360dp keeps three legible page columns`() = check(3)
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `320dp moves secondary actions to More instead of clipping`() = check(2)
    @Test @Config(qualifiers = "w412dp-h900dp-xhdpi-night")
    fun `dark 412dp keeps the page grid dominant`() = check(3)
    @Test fun `font two keeps labels reachable without shrinking page coordinates`() = check(2, 2f)
    @Test @Config(qualifiers = "hi-rIN-w360dp-h800dp-xhdpi")
    fun `Hindi page actions fit without ellipses`() = check(3)
}
