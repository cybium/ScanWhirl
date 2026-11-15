package com.scanwhirl.app.ui.shell

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * v2 UI: 01_home_A — the gallery import path: 1 or 3 picked photos must open
 * Review with those pages; an empty pick is never a silent return.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoImportTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setTestOverride(app, null)
    }

    private fun uri(name: String): Uri {
        val uri = Uri.parse("content://photo_import/${name}_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        return uri
    }

    private fun import(count: Int) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val uris = (1..count).map { uri("p$it") }
                activity.importPhotos(uris)
                shadowOf(Looper.getMainLooper()).idle()
                val deadline = System.currentTimeMillis() + 8000
                var started: Intent? = null
                while (started == null && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    started = shadowOf(activity).nextStartedActivity
                    if (started == null) Thread.sleep(20)
                }
                assertNotNull("$count photos open Review", started)
                assertEquals(
                    ScanReviewActivity::class.java.name,
                    started!!.component?.className
                )
                val dir = File(started.getStringExtra("staging_dir")!!)
                assertEquals("every picked photo is a page", count, PendingScans.pageCount(dir))
                assertTrue("the staged pages are readable", PendingScans.pages(dir).all { it.length() > 0 })
            }
        }
    }

    @Test
    fun `one picked photo opens Review with one page`() = import(1)

    @Test
    fun `three picked photos open Review with three pages`() = import(3)

    @Test
    fun `an empty pick is never silent`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ShadowToast.reset()
                activity.importPhotos(emptyList())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(
                    activity.getString(R.string.photo_import_none),
                    ShadowToast.getTextOfLatestToast()
                )
            }
        }
    }
}
