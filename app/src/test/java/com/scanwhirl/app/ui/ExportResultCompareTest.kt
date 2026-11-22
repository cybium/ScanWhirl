package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.CompareView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lane C: the export result (Resize/Compress) now shows a real Before | After pair on the dark
 * canvas. The old stats line and the preview id are kept for compatibility; Protect PDF's
 * state-only summary must never gain a plaintext preview.
 */
abstract class ExportResultCompareBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "cc_export_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }

    protected fun intent(
        before: File?,
        after: File?,
        summary: String? = null
    ) = ExportResultActivity.intent(
        app,
        outputPath = after?.path ?: jpeg("out", 100, 140).path,
        previewPath = after?.path,
        mime = "image/jpeg",
        displayName = "photo.jpg",
        beforeBytes = 290_000L,
        afterBytes = 113_000L,
        width = 900, height = 1200,
        title = "photo.jpg", warning = null,
        summary = summary,
        beforePreviewPath = before?.path,
        beforeWidth = 1600, beforeHeight = 2100
    )

    protected fun compareView(activity: ExportResultActivity): CompareView =
        activity.findViewById(R.id.ccCompare)

    @Test
    fun `resize shows real before and after numbers on the compare surface`() {
        val before = jpeg("before", 1600, 2100)
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(before, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compareView(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue("after caption has the after size", view.captionText().contains("113 KB"))
                assertTrue("after caption has the after pixels", view.captionText().contains("900 × 1200"))
                view.showBefore()
                assertTrue("before caption has the before size", view.captionText().contains("290 KB"))
                assertTrue("before caption has the before pixels", view.captionText().contains("1600 × 2100"))
            }
        }
    }

    @Test
    fun `a missing original is honest and the legacy stats still carry the sizes`() {
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(null, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compareView(activity)
                view.showBefore()
                assertEquals(
                    app.getString(R.string.cc_no_before_image), view.emptyLabel().text.toString()
                )
                assertEquals(View.GONE, view.pageImageView().visibility)
                val stats = activity.findViewById<TextView>(R.id.resultStats).text.toString()
                assertTrue("before size still in stats: $stats", stats.contains("290 KB"))
                assertTrue("after size still in stats: $stats", stats.contains("113 KB"))
                assertTrue("dimensions still in stats: $stats", stats.contains("900 × 1200"))
                assertNotNull("legacy preview id still exists", activity.findViewById<View>(R.id.resultPreview))
            }
        }
    }

    @Test
    fun `Protect state summary keeps the compare surface hidden`() {
        ActivityScenario.launch<ExportResultActivity>(
            intent(null, null, summary = "Protected copy; original kept.")
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(View.GONE, compareView(activity).visibility)
                assertEquals(
                    "Protected copy; original kept.",
                    activity.findViewById<TextView>(R.id.resultStats).text.toString()
                )
            }
        }
    }

    @Test
    fun `no white page host on the export result`() {
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(null, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val legacy = activity.findViewById<View>(R.id.resultPreview)
                assertFalse("legacy preview is not a white host", legacy.background != null)
                assertEquals(View.GONE, legacy.visibility)
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ExportResultCompareEnTest : ExportResultCompareBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class ExportResultCompareHiTest : ExportResultCompareBase() {

    @Test
    fun `Hindi result uses the localized after label`() {
        assertEquals("बाद में", app.getString(R.string.cc_after))
        val after = jpegH("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(null, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals("बाद में", compareView(activity).afterButton().text.toString())
            }
        }
    }

    private fun jpegH(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "cc_export_hi_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }
}
