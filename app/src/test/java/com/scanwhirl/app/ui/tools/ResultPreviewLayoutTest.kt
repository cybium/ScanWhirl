package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Owner defect: on a 360x640dp phone the After pane was clipped behind the pinned
 * Open/Share/More dock. The pane height is now derived from the available screen height, so
 * both panes stay above the primary action at rest and only a genuinely short screen scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ResultPreviewLayoutTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun page(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (y in (h * 0.2f).toInt() until (h * 0.7f).toInt()) {
            for (x in (w * 0.2f).toInt() until (w * 0.7f).toInt()) {
                if ((x + y) % 6 == 0) bmp.setPixel(x, y, Color.BLACK)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "lay_${name}_${System.nanoTime()}.jpg").apply {
            writeBytes(out.toByteArray())
        }
    }

    @Test
    fun `both panes and the Compare block sit above the pinned dock at 360x640dp`() {
        val before = page("before", 1200, 1600)
        val after = page("after", 800, 920)
        val intent = ExportResultActivity.intent(
            app,
            outputPath = after.path,
            previewPath = after.path,
            mime = "image/jpeg",
            displayName = "photo.jpg",
            beforeBytes = 1_100_000L,
            afterBytes = after.length(),
            width = 800, height = 920,
            title = "photo.jpg", warning = null,
            beforePreviewPath = before.path,
            beforeWidth = 1200, beforeHeight = 1600,
            toolId = "resize"
        )
        ActivityScenario.launch<ExportResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView.findViewById<View>(android.R.id.content)
                val width = 360 * 2
                val height = 640 * 2
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, width, height)
                val compare = activity.findViewById<com.scanwhirl.app.ui.kit.CompareView>(R.id.ccCompare)
                val dock = activity.findViewById<View>(R.id.exportDock)
                assertNotNull("compare surface", compare)
                assertNotNull("pinned dock", dock)
                val c = IntArray(2)
                val d = IntArray(2)
                compare.getLocationOnScreen(c)
                dock.getLocationOnScreen(d)
                val compareBottom = c[1] + compare.height
                val dockTop = d[1]
                assertTrue(
                    "compare bottom $compareBottom must be <= dock top $dockTop (px)",
                    compareBottom <= dockTop
                )
                val density = activity.resources.displayMetrics.density
                assertTrue(
                    "pane cap keeps panes <= 170dp",
                    compare.paneHeightPx() <= (170f * density).toInt()
                )
                assertTrue("panes have real height", compare.paneHeightPx() >= (40f * density).toInt())
                assertEquals(View.VISIBLE, compare.visibility)
                assertEquals(View.VISIBLE, dock.visibility)
                assertNotNull("primary Open stays in the dock",
                    activity.findViewById<View>(R.id.btnOpenResult))
            }
        }
    }
}
