package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.CompareView
import com.scanwhirl.app.ui.tools.ToolResultActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * Lane C lifecycle: ActivityController with an explicit destroy in finally (ActivityScenario.close()
 * hung CI). @Test(timeout=30_000) on every lifecycle case.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompareLifecycleTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    @Before
    fun clearProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    private fun jpeg(): File {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return File(app.cacheDir, "cc_life_${System.nanoTime()}.jpg").apply { writeBytes(out.toByteArray()) }
    }

    @Test(timeout = 30_000)
    fun `export result compare survives a full ActivityController lifecycle`() {
        val after = jpeg()
        val intent = ExportResultActivity.intent(
            app, after.path, after.path, "image/jpeg", "photo.jpg",
            290_000L, 113_000L, 900, 1200, "photo.jpg", null,
            beforePreviewPath = after.path, beforeWidth = 1600, beforeHeight = 2100
        )
        var controller: ActivityController<ExportResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ExportResultActivity::class.java, intent)
            controller.setup()
            val view: CompareView = controller.get().findViewById(R.id.ccCompare)
            assertNotNull("compare surface is on the export result", view)
            assertEquals(View.VISIBLE, view.visibility)
            controller.pause().stop()
            controller.resume()
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `tool result compare survives a full ActivityController lifecycle`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Life")
        val bytes = repo.getDocument(id)!!.pdfFile.length()
        val intent = ToolResultActivity.intent(
            app, "compress", docIds = listOf(id), beforeBytes = bytes + 2048,
            receiptSourceId = id
        )
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, intent)
            controller.setup()
            val view: CompareView = controller.get().findViewById(R.id.ccCompare)
            assertNotNull("compare surface is on the tool result", view)
            assertEquals(View.VISIBLE, view.visibility)
            controller.pause().stop()
            controller.resume()
        } finally {
            controller?.destroy()
        }
    }
}
