package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.ExactResize
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.ResizeFlow
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.util.SizeUnits
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

/**
 * Owner defect: Resize's result showed a white Before block because no source image was
 * passed. ResizeFlow.start() now writes a run-unique private cache copy and sends its path
 * plus the source dimensions into ExportResultActivity; the saved JPG remains the After.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ResizeBeforePreviewTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        File(app.cacheDir, "documents").deleteRecursively()
    }

    private fun fixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("formfx/exam_photo.jpg")!!.use { it.readBytes() }

    @Test
    fun `start writes a real before preview and the result intent carries path and dimensions`() {
        val bytes = fixture()
        val (origW, origH) = ExactResize.normalizedSize(bytes)
        val preview = ExactResize.decodeNormalized(bytes, 900)
        val path = ResizeFlow.writeBeforePreview(app.cacheDir, preview)
        assertNotNull("a private before preview was written", path)
        val file = File(path!!)
        assertTrue("before preview is a real file", file.isFile)
        assertTrue("before preview has image bytes", file.length() > 0L)
        assertTrue("preview stays inside the private cache", file.path.startsWith(app.cacheDir.path))

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
            val flow = ResizeFlow(activity, ScanRepository(app), host)
            val job = flow.doResize(
                bytes, "exam_photo.jpg",
                ExactResize.Options(200, 230, ExactResize.Mode.CROP),
                beforePreviewPath = path,
                beforeWidth = origW,
                beforeHeight = origH
            )
            val deadline = System.currentTimeMillis() + 10_000L
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10L)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("resize worker finished", job.isCompleted)

            val intent = shadowOf(activity).nextStartedActivity
                ?: throw AssertionError("no result screen was opened")
            assertEquals("real before path", path, intent.getStringExtra("before_preview_path"))
            assertEquals("before width", origW, intent.getIntExtra("before_width", 0))
            assertEquals("before height", origH, intent.getIntExtra("before_height", 0))
            val after = File(intent.getStringExtra("output_path")!!)
            assertTrue("saved output exists", after.isFile && after.length() > 0L)
            assertEquals("source byte count stays authoritative",
                bytes.size.toLong(), intent.getLongExtra("before_bytes", 0L))

            val resultIntent = com.scanwhirl.app.ui.ExportResultActivity.intent(
                app,
                outputPath = after.path, previewPath = after.path, mime = "image/jpeg",
                displayName = after.name, beforeBytes = bytes.size.toLong(),
                afterBytes = after.length(), width = 200, height = 230,
                title = "exam_photo.jpg", warning = null,
                beforePreviewPath = path, beforeBytesAuthoritative = true,
                beforeWidth = origW, beforeHeight = origH, toolId = "resize"
            )
            val resultController = Robolectric.buildActivity(
                com.scanwhirl.app.ui.ExportResultActivity::class.java, resultIntent
            )
            try {
                val result = resultController.setup().get()
                val hero = result.findViewById<android.widget.TextView>(R.id.resultStats)
                    .text.toString()
                assertTrue("hero keeps the measured source size, not the cache preview: $hero",
                    hero.contains(SizeUnits.format(bytes.size.toLong())))
                assertEquals(
                    android.view.View.VISIBLE,
                    result.findViewById<android.view.View>(R.id.ccCompare).visibility
                )
            } finally {
                resultController.destroy()
            }
        } finally {
            if (!destroyed) controller.destroy()
        }
    }

    @Test
    fun `start is wired to the preview writer and forwards it to the result intent`() {
        val source = File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()
        assertTrue("start writes the before preview",
            source.contains("writeBeforePreview(activity.cacheDir, preview)"))
        assertTrue("the dialog forwards the before path",
            source.contains("beforePreviewPath = beforePreviewPath"))
        assertTrue("the dialog forwards the source dimensions",
            source.contains("beforeWidth = origW, beforeHeight = origH"))
        assertTrue("the result intent carries the extras",
            source.contains("beforePreviewPath = beforePreviewPath,") &&
                source.contains("beforeWidth = beforeWidth,"))
    }
}
