package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.RectF
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.util.FormCrop
import com.scanwhirl.app.util.ImageTools
import org.junit.Assert.assertEquals
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

/**
 * Real-phone defect from demo 79460c29: Resize "Crop to fit" on the fictional
 * exam_photo.jpg failed with "Resize failed: the file could not be read or
 * written", while Borders mode saved exact pixels. This reproduces the crop
 * path with the SAME crop rectangle calculation the dialog uses and asserts the
 * saved JPG is the exact requested canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ResizeCropDefectTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val targetW = 200
    private val targetH = 230

    @Before fun clean() {
        File(app.cacheDir, "documents").deleteRecursively()
        ShadowToast.reset()
    }

    private fun fixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("formfx/exam_photo.jpg")!!.use { it.readBytes() }

    /** The dialog's cropRect: CropView's aspect-locked default frame, scaled to the EXIF-normalised source. */
    private fun uiCropRect(bytes: ByteArray): RectF {
        val (origW, origH) = ExactResize.normalizedSize(bytes)
        val preview = ExactResize.decodeNormalized(bytes, 900)
        val frame = FormCrop.largestCentered(preview.width, preview.height, targetW.toFloat() / targetH)
        val sx = origW.toFloat() / preview.width
        val sy = origH.toFloat() / preview.height
        val rect = RectF(frame.left * sx, frame.top * sy, frame.right * sx, frame.bottom * sy)
        preview.recycle()
        return rect
    }

    @Test(timeout = 30_000)
    fun `dialog aspect updates keep a usable crop frame`() {
        val bytes = fixture()
        val (origW, origH) = ExactResize.normalizedSize(bytes)
        val preview = ExactResize.decodeNormalized(bytes, 900)
        val activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
            .setup().get()
        val view = CropView(activity)
        view.setImage(preview)
        // The dialog's refreshFrame() sets aspectRatio on EVERY text change.
        // Replacing "3000" with "200" and "4000" with "230" through keyevents
        // produces these intermediate widths/heights.
        view.aspectRatio = origW.toFloat() / origH
        for (w in listOf(300, 30, 3, origW, 2, 20, 200)) view.aspectRatio = w.toFloat() / origH
        for (h in listOf(400, 40, 4, origH, 2, 23, targetH)) view.aspectRatio = targetW.toFloat() / h
        val r = view.getCropRect()
        val sx = origW.toFloat() / preview.width
        val sy = origH.toFloat() / preview.height
        val scaled = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
        assertTrue("crop frame collapsed to ${scaled.width()}x${scaled.height()} source px: $scaled",
            scaled.width() >= ExactResize.MIN_CROP_SIDE && scaled.height() >= ExactResize.MIN_CROP_SIDE)
        preview.recycle()
    }

    @Test(timeout = 30_000)
    fun `crop to fit saves the exact requested pixels from the exam photo fixture`() {
        val bytes = fixture()
        val cropRect = uiCropRect(bytes)
        val out = ExactResize.process(
            bytes,
            ExactResize.Options(targetW, targetH, ExactResize.Mode.CROP, cropRect = cropRect)
        )
        assertEquals("saved pixels must be exact, got ${ImageTools.imageSize(out.bytes)}",
            targetW to targetH, ImageTools.imageSize(out.bytes))
    }

    @Test(timeout = 30_000)
    fun `doResize crop path writes an exact 200x230 jpg and opens no failure toast`() {
        val bytes = fixture()
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
                ExactResize.Options(targetW, targetH, ExactResize.Mode.CROP, cropRect = uiCropRect(bytes))
            )
            val deadline = System.currentTimeMillis() + 10_000L
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10L)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("resize worker must finish", true, job.isCompleted)

            val toast = ShadowToast.getTextOfLatestToast()?.toString().orEmpty()
            val unreadable = activity.getString(R.string.resize_failed,
                activity.getString(R.string.f8_reason_unreadable))
            org.junit.Assert.assertFalse("crop must not fail as unreadable: $toast", toast == unreadable)
            val intent = shadowOf(activity).nextStartedActivity
                ?: throw AssertionError("no result screen opened; toast=$toast")
            val path = intent.getStringExtra("output_path")
                ?: throw AssertionError("no output_path in result intent")
            val saved = File(path).readBytes()
            assertEquals("saved JPG must be exact, got ${ImageTools.imageSize(saved)}",
                targetW to targetH, ImageTools.imageSize(saved))
            assertNull("no failure toast expected", ShadowToast.getTextOfLatestToast())
        } finally {
            if (!destroyed) controller.destroy()
        }
    }
}
