package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** TEMPORARY preview renderer (not committed): draws the setup page to a PNG for a look. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ToolSetupPreviewShot {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun seed() {
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt - October")
        DocFixtures.saved(repo, app, pages = 6, name = "Physics notes - Chapter 4")
        DocFixtures.saved(repo, app, pages = 1, name = "Offer letter")
        DocFixtures.saved(repo, app, pages = 2, name = "Bank statement - August 2026")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun shoot(tool: String, name: String) {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, tool)).use { scenario ->
            scenario.onActivity { activity ->
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    idle()
                    if (activity.findViewById<View>(R.id.toolSetupRun) != null) break
                    Thread.sleep(25)
                }
                Thread.sleep(600)
                idle()
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0) ?: content
                val d = activity.resources.displayMetrics.density
                val w = Math.round(360 * d)
                val h = Math.round(640 * d)
                repeat(2) {
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, w, h)
                }
                Thread.sleep(400)
                idle()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(0xFF0E1316.toInt())
                root.draw(canvas)
                val dir = File("/mnt/c/Users/Cybium/Desktop/ScanWhirl-HQ/lanes/uipicker/preview")
                dir.mkdirs()
                File(dir, name).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    @Test
    fun shots() {
        shoot("compress", "compress_en.png")
    }

    @Test
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun shotsHindi() {
        val repo = ScanRepository(app)
        repo.listDocuments().forEach { repo.deleteDocumentForever(it.id) }
        DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt - October")
        shoot("compress", "compress_hi.png")
    }
}
