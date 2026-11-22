package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.RectF
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.kit.SwProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun `not-smaller output does not reserve a folder or report a storage error`() {
        File(app.cacheDir, "documents/resize").apply {
            parentFile!!.mkdirs()
            writeText("blocked output root")
        }
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        try {
            val activity = controller.get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity),
                compute = { _, opts ->
                    ExactResize.Result(
                        bytes = ByteArray(1024), mime = "image/jpeg",
                        width = opts.width, height = opts.height, bytesMet = true,
                        plan = ExactResize.Plan(
                            10, 10, RectF(0f, 0f, 10f, 10f),
                            opts.width, opts.height, opts.mode
                        )
                    )
                })
            val job = flow.doResize(
                byteArrayOf(1, 2, 3), "exam.jpg",
                ExactResize.Options(20, 20, ExactResize.Mode.CROP)
            )
            val deadline = System.currentTimeMillis() + 10_000
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("not-smaller result must complete", job.isCompleted)
            val started = shadowOf(activity).getNextStartedActivity()
            assertNotNull("honest NOT_SMALLER result must open", started)
            assertEquals(ExportResultActivity::class.java.name, started.component?.className)
            assertEquals("blocked output root", File(app.cacheDir, "documents/resize").readText())
        } finally {
            controller.destroy()
        }
    }
}
