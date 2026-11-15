package com.scanwhirl.app.ui.pagegrid

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * L5-1: the Organize grid binds from a submitted page-set revision, so a bind does **no** file
 * `stat` at all. (Before: `keyFor` asked `lastModified()` + `length()` twice per visible cell on
 * the main thread.) The revision is safe because every page edit writes a new `r_<n>` file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageThumbFastBindTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        PageThumbLoader.keyStatProbe = null
    }

    @Test
    fun `binding a submitted page set never stats a page file`() {
        val dir = File(app.cacheDir, "thumb_fast_${UUID.randomUUID()}").apply { mkdirs() }
        val pages = (0 until 6).map { i ->
            File(dir, "page_%03d.jpg".format(i)).apply {
                writeBytes(ImportFixtures.image(width = 300, height = 400))
            }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_ScanWhirl)
        val grid = PageGridView(activity)

        var stats = 0
        PageThumbLoader.keyStatProbe = { stats++ }
        grid.submit(pages)
        measure(grid)
        assertTrue("the grid must bind visible cells", grid.childCount > 0)
        assertEquals("no stat while binding", 0, stats)

        // A rebind (selection refresh / new submit of the same set) stays stat-free.
        grid.refresh()
        measure(grid)
        grid.submit(pages)
        measure(grid)
        assertEquals("still no stat", 0, stats)
    }

    @Test
    fun `callers without a revision still revalidate the file`() {
        val dir = File(app.cacheDir, "thumb_slow_${UUID.randomUUID()}").apply { mkdirs() }
        val page = File(dir, "page_000.jpg").apply {
            writeBytes(ImportFixtures.image(width = 300, height = 400))
        }
        var stats = 0
        PageThumbLoader.keyStatProbe = { stats++ }
        PageThumbLoader.keyFor(page, 220, com.scanwhirl.app.util.ScanFilter.ORIGINAL)
        assertEquals("mtime + length are revalidated", 2, stats)
    }

    private fun measure(grid: PageGridView) {
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        grid.layout(0, 0, 1080, 1920)
    }
}
