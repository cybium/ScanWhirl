package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwProgress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FF-R cancellation: a destroyed screen must not publish a resized file, and the
 * picked source is never rewritten. ActivityController lifecycle + destroy in
 * finally, bounded by @Test(timeout) so a stuck worker fails instead of hanging.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FFRResizeLifecycleTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clearOutputs() {
        File(app.cacheDir, "documents").deleteRecursively()
    }

    private fun sourceBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(30, 90, 160))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test(timeout = 30_000)
    fun `cancel writes nothing and leaves the source file hash unchanged`() {
        val source = File(app.cacheDir, "ffr_cancel_source.jpg").apply { writeBytes(sourceBytes()) }
        val before = sha(source.readBytes())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        val controller: ActivityController<androidx.appcompat.app.AppCompatActivity> =
            Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
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
            // Inject a compute step that blocks AFTER the sheet opened, so the
            // destroy below cancels a job that is genuinely in flight.
            val flow = ResizeFlow(activity, ScanRepository(app), host,
                compute = { _, _ ->
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("cancelled compute must never publish")
                })
            val job = flow.doResize(
                source.readBytes(), "ffr_cancel_source.jpg",
                ExactResize.Options(200, 230, ExactResize.Mode.CROP)
            )
            assertTrue("the worker never started", started.await(5, TimeUnit.SECONDS))
            controller.destroy()
            destroyed = true
        } finally {
            release.countDown()
            if (!destroyed) controller.destroy()
        }

        assertArrayEquals("the picked file must never be rewritten",
            before.toByteArray(), sha(source.readBytes()).toByteArray())
        val outputs = File(app.cacheDir, "documents/resize")
        assertFalse("cancel must write no result file",
            outputs.exists() && outputs.walkTopDown().any { it.isFile })
        source.delete()
    }
}
