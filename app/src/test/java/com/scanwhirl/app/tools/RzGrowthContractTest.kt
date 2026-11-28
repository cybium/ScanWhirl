package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.SizeUnits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * RZ-GROW: Resize is exact-canvas image preparation, not compression. Valid
 * requested pixels are staged even when the resulting JPEG is larger than or
 * equal to the source. Every output below is a real, decodable JPEG produced by
 * the actual engine (or copied from its genuine output); no un-decodable byte
 * arrays are used to simulate a resize.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RzGrowthContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun clean() {
        File(app.cacheDir, "documents").deleteRecursively()
        ShadowToast.reset()
    }

    private fun host(activity: AppCompatActivity) = object : HomeHost {
        override fun refreshList() = Unit
        override fun showProgress(titleRes: Int) = SwProgress.show(activity, "test")
        override fun toast(res: Int, duration: Int) = Unit
        override fun toast(message: CharSequence, duration: Int) = Unit
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resultOf(bytes: ByteArray, opts: ExactResize.Options, bytesMet: Boolean = true) =
        ExactResize.Result(
            bytes = bytes, mime = "image/jpeg",
            width = opts.width, height = opts.height, bytesMet = bytesMet,
            plan = ExactResize.plan(opts.width, opts.height, opts)
        )

    /** Deterministic high-entropy source: its JPEG is much bigger than a small canvas. */
    private fun noisyJpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var seed = 0x1234_567B
        try {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    seed = (seed * 1_103_515_245 + 12_345) and 0x7FFF_FFFF
                    bmp.setPixel(x, y, 0xFF00_0000.toInt() or (seed and 0x00FF_FFFF))
                }
            }
            val out = ByteArrayOutputStream()
            assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out))
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    private fun drive(job: Job, activity: AppCompatActivity) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("resize worker must complete", job.isCompleted)
    }

    private fun startedResult(activity: AppCompatActivity) =
        shadowOf(activity).getNextStartedActivity().also {
            assertNotNull("a valid resize must open a result screen", it)
        }!!

    @Test(timeout = 30_000)
    fun `upscale grows bytes but stages the exact requested canvas`() {
        val source = ImportFixtures.image(width = 60, height = 40)
        val sourceHash = sha(source)
        val opts = ExactResize.Options(600, 400, ExactResize.Mode.CROP)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            val job = flow.doResize(source, "exam.jpg", opts)
            drive(job, activity)
            val started = startedResult(activity)
            assertEquals(com.scanwhirl.app.ui.tools.ToolResultActivity::class.java.name, started.component?.className)
            val spec = com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started)
            val outputPath = spec.files.single().path
            val written = File(outputPath).readBytes()
            assertTrue(
                "legitimate upscale output may grow: src=${source.size} out=${written.size}",
                written.size > source.size
            )
            assertEquals(Pair(600, 400), ImageTools.imageSize(written))
            assertEquals(source.size.toLong(), started.getLongExtra("before_bytes", -1L))
            assertEquals("receipt bytes match the published file", written.size.toLong(), spec.facts?.bytes)
            assertEquals("receipt width matches the actual JPEG", 600, spec.facts?.width)
            assertEquals("receipt height matches the actual JPEG", 400, spec.facts?.height)
            assertEquals(listOf(outputPath), spec.files.map { it.path })
            assertNull("byte growth is not a rejection", started.getStringExtra("compare_outcome"))
            assertNull("no byte target, no warning", spec.note)
            assertEquals("source bytes must be retained", sourceHash, sha(source))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `output equal in bytes to the source is still staged at exact pixels`() {
        val opts = ExactResize.Options(120, 80, ExactResize.Mode.CROP)
        // The source is itself a genuine engine JPEG at the requested canvas.
        val engineBytes = ExactResize.process(ImportFixtures.image(width = 120, height = 80), opts).bytes
        val source = engineBytes.copyOf()
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity),
                compute = { _, o -> resultOf(engineBytes.copyOf(), o) })
            val job = flow.doResize(source, "equal.jpg", opts)
            drive(job, activity)
            val started = startedResult(activity)
            val written = com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started).files.single().readBytes()
            assertEquals("equal-byte output must be kept", source.size, written.size)
            assertEquals(Pair(120, 80), ImageTools.imageSize(written))
            assertNull("equal bytes are not a rejection", started.getStringExtra("compare_outcome"))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `smaller output is staged at exact pixels`() {
        val source = noisyJpeg(160, 120)
        val opts = ExactResize.Options(40, 30, ExactResize.Mode.CROP)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            val job = flow.doResize(source, "small.jpg", opts)
            drive(job, activity)
            val started = startedResult(activity)
            val written = com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started).files.single().readBytes()
            assertTrue("downscale must reduce bytes: src=${source.size} out=${written.size}",
                written.size < source.size)
            assertEquals(Pair(40, 30), ImageTools.imageSize(written))
            assertNull(started.getStringExtra("compare_outcome"))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `maxBytes miss keeps exact pixels and shows an honest warning`() {
        val source = ImportFixtures.image(width = 160, height = 120)
        val opts = ExactResize.Options(160, 120, ExactResize.Mode.CROP, maxBytes = 1L)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            val job = flow.doResize(source, "target_miss.jpg", opts)
            drive(job, activity)
            val started = startedResult(activity)
            val written = com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started).files.single().readBytes()
            assertEquals("miss must not shrink or hide the image", Pair(160, 120), ImageTools.imageSize(written))
            val warning = com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started).note
            assertNotNull("a byte-target miss must be reported", warning)
            assertTrue("warning must name the target: $warning", warning!!.contains(SizeUnits.format(1L)))
            assertTrue("warning must name the exact pixels: $warning",
                warning.contains("160") && warning.contains("120"))
            assertNull("a maxBytes miss is not a no-gain rejection",
                started.getStringExtra("compare_outcome"))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a successful resize leaves the picked source file hash unchanged`() {
        val source = ImportFixtures.image(width = 80, height = 60)
        val sourceFile = File(app.cacheDir, "rz_growth_source.jpg").apply { writeBytes(source) }
        val before = sha(sourceFile.readBytes())
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            val job = flow.doResize(sourceFile.readBytes(), "rz_growth_source.jpg",
                ExactResize.Options(240, 180, ExactResize.Mode.CROP))
            drive(job, activity)
            val started = startedResult(activity)
            assertTrue(com.scanwhirl.app.ui.result.ResultSpec.fromIntent(started).files.single().isFile)
            assertEquals("the picked file must never be rewritten", before, sha(sourceFile.readBytes()))
        } finally {
            controller.destroy()
            sourceFile.delete()
        }
    }

    @Test(timeout = 30_000)
    fun `uncreatable cache parent gives a save error and no result`() {
        val root = File(app.cacheDir, "documents/resize").apply {
            parentFile!!.mkdirs()
            writeText("blocked output root")
        }
        val source = ImportFixtures.image(width = 60, height = 40)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity))
            val job = flow.doResize(source, "exam.jpg",
                ExactResize.Options(300, 200, ExactResize.Mode.CROP))
            drive(job, activity)
            assertNull("no result may open after a save failure",
                shadowOf(activity).getNextStartedActivity())
            assertEquals(app.getString(R.string.resize_save_failed),
                ShadowToast.getTextOfLatestToast()?.toString())
            assertEquals("blocked output root", root.readText())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `cancellation before compute publishes no result and no file`() {
        val source = ImportFixtures.image(width = 60, height = 40)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        try {
            val activity = controller.setup().get()
            val flow = ResizeFlow(activity, ScanRepository(app), host(activity),
                compute = { _, _ -> throw CancellationException("cancelled before compute") })
            val job = flow.doResize(source, "exam.jpg",
                ExactResize.Options(200, 150, ExactResize.Mode.CROP))
            drive(job, activity)
            assertNull("cancelled work must not open a result",
                shadowOf(activity).getNextStartedActivity())
            val outputs = File(app.cacheDir, "documents/resize")
            assertFalse("cancelled work must write no file",
                outputs.exists() && outputs.walkTopDown().any { it.isFile })
        } finally {
            controller.destroy()
        }
    }
}
