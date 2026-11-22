package com.scanwhirl.app.tools

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.kit.SwProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Regression proofs for RZ2 review, 2026-11-22. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChatgptResizeRegressionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        File(app.cacheDir, "documents").deleteRecursively()
    }

    private fun host(activity: AppCompatActivity) = object : HomeHost {
        override fun refreshList() = Unit
        override fun showProgress(titleRes: Int) = SwProgress.show(activity, "test")
        override fun toast(res: Int, duration: Int) = Unit
        override fun toast(message: CharSequence, duration: Int) = Unit
    }

    @Test(timeout = 30_000)
    fun `a collision with an existing resize folder does not reuse or overwrite it`() {
        val prior = File(app.cacheDir, "documents/resize/taken")
        assertTrue(prior.mkdirs())
        File(prior, "original.jpg").writeText("keep")
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        try {
            val activity = controller.get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            var tries = 0
            val result = flow.resizeOutputDirNamed(app.cacheDir) {
                if (tries++ == 0) "taken" else "fresh"
            }
            assertEquals(2, tries)
            assertEquals("fresh", result.name)
            assertTrue(result.isDirectory)
            assertEquals("keep", File(prior, "original.jpg").readText())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `eight repeated name collisions fail without reusing a prior output`() {
        val prior = File(app.cacheDir, "documents/resize/taken")
        assertTrue(prior.mkdirs())
        File(prior, "keep.jpg").writeText("untouched")
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        try {
            val activity = controller.get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            var attempts = 0
            var failedClosed = false
            try {
                flow.resizeOutputDirNamed(app.cacheDir) {
                    attempts++
                    "taken"
                }
            } catch (_: java.io.IOException) {
                failedClosed = true
            }
            assertTrue("persistent collisions must fail closed", failedClosed)
            assertEquals("bounded attempt count", 8, attempts)
            assertEquals("untouched", File(prior, "keep.jpg").readText())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `not-smaller result hides Save behind an inaccessible More panel`() {
        val intent = ExportResultActivity.intent(
            app, "", null, "image/jpeg", "exam.jpg",
            100L, 200L, 20, 20, null, null,
            outcome = "NOT_SMALLER", toolId = "resize"
        )
        val controller = Robolectric.buildActivity(ExportResultActivity::class.java, intent)
        try {
            val activity = controller.setup().get()
            assertEquals(
                "NOT_SMALLER has no accessible Save panel",
                android.view.View.GONE,
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.rsExportMorePanel).visibility
            )
            assertTrue("Save cannot be reached in a rejected result",
                !activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSaveResult).isShown)
            assertEquals(android.view.View.GONE,
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnShareResult).visibility)
            assertEquals(android.view.View.GONE,
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnMoreResult).visibility)
            activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnOpenResult).performClick()
            assertTrue("Done ends the rejection screen", activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }
}
