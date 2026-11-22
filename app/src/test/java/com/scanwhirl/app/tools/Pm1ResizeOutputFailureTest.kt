package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.RectF
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPT post-merge PM-1: resizeOutputDir used to retry mkdirs() forever, on the
 * main thread. A path that cannot be created (here: a regular FILE where the
 * output folder should be) must fail promptly, keep the looper responsive, and
 * show ONE short honest error instead of launching a result screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Pm1ResizeOutputFailureTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        File(app.cacheDir, "documents").deleteRecursively()
        ShadowToast.reset()
    }

    /** Compiles against the unfixed base: a named RED assertion, not a compile error. */
    @Test fun `output directory creation is off the main thread and has a bounded retry`() {
        val source = File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()
        assertTrue("the output dir must be created on Dispatchers.IO",
            source.contains("withContext(Dispatchers.IO) { resizeOutputDir"))
        assertFalse("an unbounded mkdirs() loop can hang the UI",
            source.contains("while (true)"))
        assertTrue("name-collision retries must be bounded",
            source.contains("until MAX_") || source.contains("MAX_OUTPUT_DIR_ATTEMPTS"))
    }

    @Test(timeout = 30_000)
    fun `an uncreatable output root fails promptly with one honest error and no result`() {
        // A regular file at cacheDir/documents/resize makes every mkdirs() fail;
        // the old loop would spin forever here, on the main thread.
        File(app.cacheDir, "documents/resize").apply {
            parentFile!!.mkdirs()
            writeText("not a directory")
        }

        val controller = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        var destroyed = false
        try {
            val activity = controller.setup().get()
            var progress: SwProgress? = null
            val host = object : HomeHost {
                override fun refreshList() = Unit
                override fun showProgress(titleRes: Int): SwProgress {
                    if (progress == null) progress = SwProgress.show(activity, "test")
                    return progress!!
                }
                override fun toast(res: Int, duration: Int) = Unit
                override fun toast(message: CharSequence, duration: Int) = Unit
            }
            val flow = ResizeFlow(activity, ScanRepository(app), host,
                compute = { _, opts ->
                    ExactResize.Result(
                        bytes = "x".toByteArray(), mime = "image/jpeg",
                        width = opts.width, height = opts.height, bytesMet = true,
                        plan = ExactResize.Plan(
                            10, 10, RectF(0f, 0f, 10f, 10f),
                            opts.width, opts.height, opts.mode
                        )
                    )
                })
            val job = flow.doResize(
                "source".toByteArray(), "source.jpg",
                ExactResize.Options(20, 20, ExactResize.Mode.CROP)
            )

            // Drive the main looper while the IO worker settles. A main-thread
            // mkdirs() loop never yields, so the @Test timeout would catch it.
            val deadline = System.currentTimeMillis() + 10_000L
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10L)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("the resize worker must finish, not loop forever", job.isCompleted)

            // A concrete responsiveness probe: fresh UI work still runs on the
            // main looper after the storage failure.
            val uiRan = AtomicBoolean(false)
            activity.runOnUiThread { uiRan.set(true) }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("the main looper must still be responsive", uiRan.get())

            assertNull("no result screen may open after a save failure",
                shadowOf(activity).getNextStartedActivity())
            val toast = ShadowToast.getTextOfLatestToast()?.toString().orEmpty()
            assertEquals(activity.getString(R.string.resize_save_failed), toast)
            assertFalse("no raw exception text in the UI",
                toast.contains("IOException") || toast.contains("documents/resize"))
        } finally {
            if (!destroyed) controller.destroy()
        }
    }
}
