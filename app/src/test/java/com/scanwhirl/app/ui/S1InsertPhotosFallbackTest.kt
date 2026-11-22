package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ImportFixtures
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * S1 defect 4: "Add pages > From photos" ran normalizePage (no HEIC / other-container fallback)
 * and reported every decode failure as the generic "No pages were added". It must use the same
 * normalizeGalleryPhoto path as the other photo entries and name a localized reason.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1InsertPhotosFallbackTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun clickSheetOption(index: Int) {
        idle()
        val rows = requireNotNull(
            ShadowDialog.getLatestDialog()?.findViewById<LinearLayout>(R.id.swSheetItems)
        ) { "no option sheet is open" }
        requireNotNull(rows.getChildAt(index)) { "no option $index" }.performClick()
        idle()
    }

    private fun photosIntent(vararg photos: File): Intent {
        val intent = Intent()
        val clip = ClipData.newUri(app.contentResolver, "photos", Uri.fromFile(photos.first()))
        photos.drop(1).forEach { clip.addItem(ClipData.Item(Uri.fromFile(it))) }
        intent.clipData = clip
        return intent
    }

    private fun pickFromPhotos(viewer: DocumentViewerActivity, photo: File) {
        viewer.startInsertPages()
        idle()
        clickSheetOption(1) // "From photos"
        idle()
        val pick = requireNotNull(shadowOf(viewer).nextStartedActivityForResult) {
            "Photos must open the photo picker"
        }
        shadowOf(viewer).receiveResult(pick.intent, Activity.RESULT_OK, photosIntent(photo))
    }

    @Test
    fun `a container the normal page path rejects is still accepted from Add pages photos`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val photo = File(app.cacheDir, "s1_heic_like_${System.nanoTime()}.bmp").apply {
            writeBytes(ImportFixtures.bmp(60, 40))
        }
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, target)).use { scenario ->
            var viewer: DocumentViewerActivity? = null
            scenario.onActivity { viewer = it }
            assertTrue("the document loads", waitUntil {
                viewer?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList)
                    ?.adapter?.itemCount ?: 0 > 0
            })
            pickFromPhotos(requireNotNull(viewer), photo)
            assertTrue("the fallback photo reaches the position step", waitUntil {
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                    ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                    app.getString(R.string.insert_position_title)
            })
            clickSheetOption(2) // "At the end"
            assertTrue("the picked fallback photo opens the crop screen", waitUntil {
                shadowOf(requireNotNull(viewer)).nextStartedActivityForResult?.intent?.component?.className ==
                    "com.scanwhirl.app.ui.tools.PhotoCropFlowActivity"
            })
        }
    }

    @Test
    fun `an unreadable photo reports a localized reason instead of the generic no-pages text`() {
        val target = DocFixtures.saved(repo, app, pages = 1)
        val broken = File(app.cacheDir, "s1_broken_${System.nanoTime()}.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, target)).use { scenario ->
            var viewer: DocumentViewerActivity? = null
            scenario.onActivity { viewer = it }
            assertTrue("the document loads", waitUntil {
                viewer?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList)
                    ?.adapter?.itemCount ?: 0 > 0
            })
            ShadowToast.reset()
            pickFromPhotos(requireNotNull(viewer), broken)
            assertTrue("a toast is shown", waitUntil { ShadowToast.getTextOfLatestToast() != null })
            val toast = ShadowToast.getTextOfLatestToast().toString()
            assertFalse("the generic 'No pages were added' is replaced", toast == app.getString(R.string.insert_none))
            assertNotNull("a reason is present", toast)
            assertTrue("the reason is not raw JVM text", !toast.contains("java.") && !toast.contains("Exception"))
        }
    }
}
