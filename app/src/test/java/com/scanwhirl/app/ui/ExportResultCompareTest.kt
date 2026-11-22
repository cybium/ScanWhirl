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
 * Lane RS: the export result leads with one size hero line, shows a stacked zoom pair, and pins
 * Open / Share / More. Protect's state-only summary keeps no plaintext preview.
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
    fun `resize shows the hero line and real before and after numbers on stacked panes`() {
        val before = jpeg("before", 1600, 2100)
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(before, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val hero = activity.findViewById<TextView>(R.id.resultStats).text.toString()
                assertTrue("one hero line: $hero", hero.contains("290 KB") && hero.contains("113 KB"))
                val view = compareView(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue("after caption has the after size", view.captionText().contains("113 KB"))
                assertTrue("after caption has the after pixels", view.captionText().contains("900 × 1200"))
                assertTrue("before caption has the before size", view.beforeCaptionText().contains("290 KB"))
                assertTrue("before caption has the before pixels", view.beforeCaptionText().contains("1600 × 2100"))
                assertEquals(app.getString(R.string.tool_resize),
                    activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title.toString())
            }
        }
    }

    @Test
    fun `a missing original is honest and the hero still carries the sizes`() {
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(null, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compareView(activity)
                assertEquals(
                    app.getString(R.string.cc_no_before_image), view.emptyLabel().text.toString()
                )
                assertEquals(View.GONE, view.beforeImageView().visibility)
                val hero = activity.findViewById<TextView>(R.id.resultStats).text.toString()
                assertTrue("before size still in hero: $hero", hero.contains("290 KB"))
                assertTrue("after size still in hero: $hero", hero.contains("113 KB"))
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
    fun `an unmeasured export never prints a size hero`() {
        val file = jpeg("unknown", 100, 140)
        ActivityScenario.launch<ExportResultActivity>(
            ExportResultActivity.intent(
                app,
                outputPath = file.path,
                previewPath = file.path,
                mime = "image/jpeg",
                displayName = file.name,
                beforeBytes = 0L,
                afterBytes = 0L,
                width = 0,
                height = 0,
                title = file.name,
                warning = null
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val hero = activity.findViewById<TextView>(R.id.resultStats).text.toString()
                assertEquals(app.getString(R.string.cc_outcome_unknown), hero)
                assertFalse("no dangling size arrow: $hero", hero.contains("→"))
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

    @Test
    fun `Open Share and More are in the pinned dock`() {
        val after = jpeg("after", 900, 1200)
        ActivityScenario.launch<ExportResultActivity>(intent(null, after)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                listOf(R.id.btnOpenResult, R.id.btnShareResult, R.id.btnMoreResult).forEach { id ->
                    assertNotNull("action $id", activity.findViewById<View>(id))
                }
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
                assertTrue("Hindi after label", compareView(activity).captionText().startsWith("बाद में"))
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
