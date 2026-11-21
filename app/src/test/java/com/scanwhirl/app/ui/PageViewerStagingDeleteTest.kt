package com.scanwhirl.app.ui

import android.app.Application
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper
import java.io.File
import java.util.UUID

/**
 * E1 defect 3: an unsaved-scan page is hidden as `page_XXX.jpg.del` while the Undo snackbar is
 * up. If the process dies before Undo or commit, neither onDestroy path runs and the stale
 * `.del` orphan makes the page silently disappear on reload. Reopening the staging viewer must
 * restore every orphan that has no live Undo window, in the original order.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageViewerStagingDeleteTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun stagingWith(vararg names: String): File {
        val dir = File(context.cacheDir, "e1_staging_${UUID.randomUUID()}").apply { mkdirs() }
        names.forEach { File(dir, it).writeBytes(ImportFixtures.image(width = 120, height = 160)) }
        return dir
    }

    private fun launch(dir: File) = ActivityScenario.launch<PageViewerActivity>(
        PageViewerActivity.stagingIntent(context, dir.path, 0)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    @Test
    fun `a stale del orphan is restored on a fresh load and page order is preserved`() {
        // Simulates process death mid-delete: page_002 was hidden, no live snackbar remains.
        val dir = stagingWith("page_001.jpg", "page_003.jpg")
        File(dir, "page_002.jpg.del").writeBytes(ImportFixtures.image(width = 120, height = 160))
        // Sanity: the live page is gone from the plain *.jpg listing until we restore.
        assertFalse(File(dir, "page_002.jpg").exists())

        launch(dir).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { screen ->
                assertEquals(
                    "1 / 3",
                    screen.findViewById<TextView>(R.id.pageIndicator).text.toString()
                )
            }
        }
        assertTrue("the orphan is renamed back to a live page", File(dir, "page_002.jpg").exists())
        assertFalse("the .del marker is gone", File(dir, "page_002.jpg.del").exists())
    }

    @Test
    fun `a live undo window is not mistaken for an orphan`() {
        // A normal open with no .del must be untouched (guard against over-restoring).
        val dir = stagingWith("page_001.jpg", "page_002.jpg")
        launch(dir).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { screen ->
                assertEquals(
                    "1 / 2",
                    screen.findViewById<TextView>(R.id.pageIndicator).text.toString()
                )
            }
        }
    }
}
