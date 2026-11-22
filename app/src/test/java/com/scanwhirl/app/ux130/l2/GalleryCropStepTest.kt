package com.scanwhirl.app.ux130.l2
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.scanwhirl.app.R
import com.scanwhirl.app.tools.GalleryCrop
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * L2-02: Add from gallery must offer an explicit corner-adjust step and only
 * append corrected URIs to staging. Cancelling or delivering twice must never
 * change the page list or overwrite a source photo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryCropStepTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dirs = mutableListOf<File>()

    @Before fun resetBridge() {
        GalleryCrop.resetForTest()
        GalleryCrop.startOverride = { true }
        ShadowDialog.reset()
    }

    @After fun clean() {
        GalleryCrop.startOverride = null
        GalleryCrop.resetForTest()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun staging(pages: Int = 1): File {
        val dir = File(app.cacheDir, "ux130_l2_gallery_${System.nanoTime()}").apply { mkdirs() }
        repeat(pages) { File(dir, String.format("page_%03d.jpg", it + 1)).writeBytes(ImportFixtures.image(width = 120)) }
        dirs += dir
        return dir
    }

    private fun openGallerySheet(scenario: ActivityScenario<ScanReviewActivity>) {
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
            val item = toolbar.menu.findItem(R.id.action_add_gallery)
            assertNotNull("Add from gallery must exist", item)
            // AppCompat Toolbar keeps its item click listener in a private field; invoke
            // the same listener a real menu tap invokes, without depending on overflow layout.
            var type: Class<*>? = toolbar.javaClass
            var clicked = false
            while (type != null && !clicked) {
                val current = type
                try {
                    val field = current.getDeclaredField("mOnMenuItemClickListener")
                    field.isAccessible = true
                    val listener = field.get(toolbar) as? androidx.appcompat.widget.Toolbar.OnMenuItemClickListener
                    if (listener != null) {
                        listener.onMenuItemClick(item)
                        clicked = true
                    }
                } catch (_: NoSuchFieldException) {
                    // keep walking the class hierarchy
                }
                type = current.superclass
            }
            assertTrue("toolbar menu listener must be reachable", clicked)
        }
        await { sheetButton(R.id.swSheetPrimary) == app.getString(R.string.ux130_l2_add_gallery_adjust) }
    }

    private fun sheetButton(id: Int): String? =
        (ShadowDialog.getLatestDialog()?.findViewById<View>(id) as? TextView)?.text?.toString()

    private fun clickPrimary() {
        val dialog = ShadowDialog.getLatestDialog()
        (dialog?.findViewById<View>(R.id.swSheetPrimary) as? TextView)?.performClick()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(app.mainLooper).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        shadowOf(app.mainLooper).idle()
        assertTrue("timed out waiting for condition", condition())
    }

    private fun pageCount(dir: File) = dir.listFiles { it.extension.equals("jpg", true) }?.size ?: 0

    @Test
    fun `adjust corners offers an explicit step and appends corrected bytes once`() {
        val dir = staging()
        val source = File(app.cacheDir, "corrected_${System.nanoTime()}.jpg").apply {
            writeBytes(ImportFixtures.image(width = 90, height = 60))
        }
        dirs += source
        val originalBytes = source.readBytes()

        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Gallery test")
        ).use { scenario ->
            openGallerySheet(scenario)
            assertEquals(app.getString(R.string.ux130_l2_add_gallery_title),
                sheetText(R.id.swSheetTitle))
            clickPrimary()

            scenario.onActivity { a ->
                GalleryCrop.deliver(a, listOf(Uri.fromFile(source)))
            }
            await { pageCount(dir) == 2 }

            val appended = File(dir, "page_002.jpg")
            val decoded = android.graphics.BitmapFactory.decodeFile(appended.path)
            assertNotNull("corrected page appended to staging", decoded)
            assertEquals(90, decoded!!.width)
            assertEquals(60, decoded.height)
            decoded.recycle()
            assertTrue("source photo is untouched", source.readBytes().contentEquals(originalBytes))

            // A second delivery has no pending token: no duplicate page.
            scenario.onActivity { a -> GalleryCrop.deliver(a, listOf(Uri.fromFile(source))) }
            shadowOf(app.mainLooper).idle()
            assertEquals("no double append", 2, pageCount(dir))
        }
    }

    @Test
    fun `cancelling the crop sheet leaves the page list intact`() {
        val dir = staging()
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Gallery test")
        ).use { scenario ->
            openGallerySheet(scenario)
            ShadowDialog.getLatestDialog()?.dismiss()
            shadowOf(app.mainLooper).idle()
            assertEquals(1, pageCount(dir))
        }
    }

    @Test
    fun `plain add pages remains available as an explicit opt out`() {
        val dir = staging()
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Gallery test")
        ).use { scenario ->
            openGallerySheet(scenario)
            assertEquals(app.getString(R.string.ux130_l2_add_gallery_plain), sheetButton(R.id.swSheetSecondary))
        }
    }

    private fun sheetText(id: Int): String? =
        (ShadowDialog.getLatestDialog()?.findViewById<View>(id) as? TextView)?.text?.toString()
}
