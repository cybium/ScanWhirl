package com.scanwhirl.app.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 01_home_A — gallery auto-crop: the shared ML Kit helper, its manual-crop
 * fallback, the Home "Add photos" choice and the unchanged camera options.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryCropTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setGalleryAutoCrop(app, true)
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
        GalleryCrop.resetForTest()
    }

    @After
    fun tearDown() {
        GalleryCrop.startOverride = null
        GalleryCrop.pickOverride = null
        Prefs.setGalleryAutoCrop(app, true)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun host(activity: ShellActivity): HomeHost = object : HomeHost {
        override fun refreshList() = Unit
        override fun showProgress(titleRes: Int): com.scanwhirl.app.ui.kit.SwProgress =
            com.scanwhirl.app.ui.kit.SwProgress.show(activity, "test")
        override fun toast(res: Int, duration: Int) = Unit
        override fun toast(message: CharSequence, duration: Int) = Unit
    }

    @Test
    fun `camera scans keep gallery import off and the gallery helper turns it on`() {
        launch { activity ->
            val launcher = ScanLauncher(activity, repo, host(activity), {})
            val camera = launcher.cameraConfig()
            assertFalse("camera scans stay camera-only", camera.galleryImport)
            assertEquals(ScanLauncher.CAMERA_PAGE_LIMIT, camera.pageLimit)
            assertTrue("camera asks for JPEGs", camera.jpeg)

            val gallery = GalleryCrop.galleryConfig(activity, 200)
            assertTrue("gallery auto-crop enables gallery import", gallery.galleryImport)
            assertEquals("the page cap is clamped", 100, gallery.pageLimit)
            val one = GalleryCrop.galleryConfig(activity, 0)
            assertEquals(1, one.pageLimit)
            // The real builder does not throw (option names checked by the config above).
            assertNotNull(launcher.cameraOptions())
            assertNotNull(GalleryCrop.galleryOptions(activity, 30))
        }
    }

    @Test
    fun `the scanner's cropped pages reach onResult in order`() {
        var got: List<Uri> = emptyList()
        GalleryCrop.startOverride = { true }
        launch { activity ->
            launchGalleryCrop(activity, 30, onResult = { _, _, uris -> got = uris })
            GalleryCrop.deliver(
                activity,
                listOf(Uri.parse("content://crop/page_1.jpg"), Uri.parse("content://crop/page_2.jpg"))
            )
            idle()
            assertEquals(2, got.size)
            assertEquals("content://crop/page_2.jpg", got[1].toString())
        }
    }

    @Test
    fun `an unavailable scanner falls back to the picker for a manual crop`() {
        var cropped: List<Uri> = emptyList()
        var fallback: List<Uri> = emptyList()
        GalleryCrop.startOverride = { false }
        GalleryCrop.pickOverride = { activity ->
            GalleryCrop.deliverFallback(activity, listOf(Uri.parse("content://raw/photo.jpg")))
        }
        launch { activity ->
            launchGalleryCrop(
                activity, 30,
                onResult = { _, _, uris -> cropped = uris },
                onFallback = { _, _, uris -> fallback = uris }
            )
            idle()
            assertTrue("nothing was auto-cropped", cropped.isEmpty())
            assertEquals("the raw photo goes to the manual crop path", 1, fallback.size)
        }
    }

    @Test
    fun `the three-argument API sends raw photos to onResult when there is no fallback`() {
        var got: List<Uri> = emptyList()
        GalleryCrop.startOverride = { false }
        GalleryCrop.pickOverride = { activity ->
            GalleryCrop.deliverFallback(activity, listOf(Uri.parse("content://raw/only.jpg")))
        }
        launch { activity ->
            launchGalleryCrop(activity, 30, onResult = { _, _, uris -> got = uris })
            idle()
            assertEquals(1, got.size)
        }
    }

    @Test
    fun `Home Photos offers auto-crop and keep-as-is and remembers the choice`() {
        var scannerStarted = false
        GalleryCrop.startOverride = { scannerStarted = true; true }
        launch { activity ->
            // UI-B1 (#2): photos start from the Create sheet now, not a Home tile.
            // W7: the Create sheet's photos row opens the OS photo picker directly (never the
            // scanner), so this auto-crop/keep choice is reached through the Library's Import
            // sheet instead. The feature and the remembered choice are unchanged.
            fun openPhotos() {
                activity.findViewById<View>(R.id.swEmptyImport).performClick()
                idle()
                val importSheet = ShadowDialog.getLatestDialog()
                assertNotNull("the Library import sheet opens", importSheet)
                importSheet!!.findViewById<View>(R.id.importPhotos).performClick()
                idle()
            }
            // Auto-crop (remembered default) -> the gallery scanner.
            openPhotos()
            var sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the Add photos sheet opens", sheet)
            var rows = sheet!!.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals(2, rows.childCount)
            rows.getChildAt(0).performClick()
            idle()
            assertTrue("Auto-crop opens the gallery scanner", scannerStarted)
            assertTrue(Prefs.galleryAutoCrop(app))

            // Keep as they are -> the existing picker.
            openPhotos()
            sheet = ShadowDialog.getLatestDialog()
            rows = sheet!!.findViewById<ViewGroup>(R.id.swSheetItems)
            rows.getChildAt(1).performClick()
            idle()
            val picker = shadowOf(activity).nextStartedActivityForResult
            assertNotNull("Keep as they are opens the photo picker", picker)
            assertEquals(Intent.ACTION_GET_CONTENT, picker.intent.action)
            assertFalse("the last choice is remembered", Prefs.galleryAutoCrop(app))
        }
    }
}
