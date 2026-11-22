package com.scanwhirl.app.v2v

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** The phone found that a protected document opens its pages without a password
 *  prompt. Protection is on the exported/shared PDF; the in-app library copy is
 *  intentionally readable. This test pins the HONEST banner, not an in-app lock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2vProtectedHonestyTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun bannerText(): String {
        val res = app.resources.getIdentifier("v3_protected_banner", "string", app.packageName)
        return if (res == 0) "" else app.getString(res)
    }

    @Test(timeout = 30_000)
    fun `document viewer explains that protection applies when shared`() {
        val id = DocFixtures.locked(repo, app, pages = 2)
        val controller = Robolectric.buildActivity(DocumentViewerActivity::class.java,
            DocumentViewerActivity.intent(app, id))
        try {
            val activity = controller.setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val banner = activity.bannerView()
            assertNotNull("the protected-doc banner must exist", banner)
            assertTrue("the banner must be visible", banner!!.isShown)
            assertTrue("the banner must be honest: ${(banner as TextView).text}",
                (banner.text ?: "").toString() == bannerText())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `pdf reader explains that protection applies when shared`() {
        val id = DocFixtures.locked(repo, app, pages = 2)
        val controller = Robolectric.buildActivity(PdfViewerActivity::class.java,
            PdfViewerActivity.intent(app, id, "Protected reader"))
        try {
            val activity = controller.setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            val banner = activity.bannerView()
            assertNotNull("the reader protected-doc banner must exist", banner)
            assertTrue("the reader banner must be visible", banner!!.isShown)
            assertTrue("the reader banner must be honest: ${(banner as TextView).text}",
                (banner.text ?: "").toString() == bannerText())
        } finally {
            controller.destroy()
        }
    }

    private fun android.app.Activity.bannerView(): View? {
        val id = resources.getIdentifier("v2v_protected_banner", "id", packageName)
        if (id == 0) return null
        return findViewById(id)
    }
}
