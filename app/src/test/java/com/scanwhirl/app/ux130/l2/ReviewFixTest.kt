package com.scanwhirl.app.ux130.l2

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.customview.widget.ExploreByTouchHelper
import com.scanwhirl.app.R
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Follow-up to the independent review of f8f9f605: explore-by-touch hit testing,
 * virtual-view invalidation/events, keyboard focus leaving the crop, cancellation
 * safety of the resume guard and honest partial gallery imports.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReviewFixTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dirs = mutableListOf<File>()

    @Before fun reset() {
        ScanLauncher.resetColdStartOfferForTest()
        ScanLauncher.resetResumeOfferStateForTest()
        ScanLauncher.beforeResumeLookupForTest = null
        ShadowDialog.reset()
        ShadowToast.reset()
    }

    @After fun clean() {
        ScanLauncher.beforeResumeLookupForTest = null
        ScanLauncher.resetResumeOfferStateForTest()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun cropView() = com.scanwhirl.app.ui.CropView(app).apply {
        setImage(Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888))
        measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, 1080, 1920)
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

    private fun sheetPrimary(): String? =
        (ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetPrimary) as? TextView)
            ?.text?.toString()

    private fun staging(): File {
        val dir = File(app.cacheDir, "ux130_l2_fix_${System.nanoTime()}").apply { mkdirs() }
        File(dir, "page_001.jpg").writeBytes(ImportFixtures.image(width = 120))
        dirs += dir
        return dir
    }

    @Test
    fun `explore by touch hit-tests the corner under the finger`() {
        val view = cropView()
        val points = view.handlePoints()
        assertEquals(1, view.virtualViewAt(points[0], points[1]))  // TL corner
        assertEquals(3, view.virtualViewAt(points[4], points[5]))  // BR corner
        assertEquals(5, view.virtualViewAt(points[8], points[9]))  // top edge
        assertEquals(ExploreByTouchHelper.INVALID_ID, view.virtualViewAt(4f, 4f))
    }

    @Test
    fun `a move invalidates and sends an event for the moved virtual view`() {
        val view = cropView()
        assertTrue("ExploreByTouchHelper must be attached", ViewCompat.getAccessibilityNodeProvider(view) != null)
        assertTrue(view.performVirtualActionForTest(1, 0x01000002)) // Move right on TL corner
        assertTrue("invalidateVirtualView for id 1", view.invalidatedVirtualViewsForTest().contains(1))
        assertTrue("sendEventForVirtualView for id 1", view.virtualEventsForTest().any { it.first == 1 })
    }

    @Test
    fun `Tab from the last handle is not consumed so Save and Apply stay reachable`() {
        val view = cropView()
        assertTrue("ExploreByTouchHelper is attached", ViewCompat.getAccessibilityNodeProvider(view) != null)
        view.selectHandleForTest(7) // last handle
        assertFalse("Tab must leave the view from the last handle", view.onKeyDown(KeyEvent.KEYCODE_TAB, null))
    }

    @Test
    fun `cancelling the resume lookup clears the guard and the next Scan offers again`() {
        val pending = File(app.filesDir, com.scanwhirl.app.store.PendingScans.ROOT).let { root ->
            root.mkdirs()
            File(root, "pending_${System.nanoTime()}").apply { mkdirs() }
        }
        File(pending, "page_001.jpg").writeBytes(ImportFixtures.image(width = 120))
        dirs += pending
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScanLauncher.beforeResumeLookupForTest = {
            entered.countDown()
            release.await()
            // Simulate the lookup being cancelled by a rotation while it is on IO.
            throw kotlinx.coroutines.CancellationException("simulated rotation")
        }

        ActivityScenario.launch<ShellActivity>(android.content.Intent(app, ShellActivity::class.java)).use { scenario ->
            fun scanEntry() = scenario.onActivity { a ->
                com.scanwhirl.app.tools.ScanLauncher(
                    a, com.scanwhirl.app.ScanWhirlApp.from(a).repository, a
                ) {}.launchScanner()
            }
            scanEntry()
            assertTrue("resume lookup started", entered.await(3, TimeUnit.SECONDS))
            var job: kotlinx.coroutines.Job? = null
            scenario.onActivity { job = ScanLauncher.resumeOfferJobForTest }
            assertNotNullJob(job)
            job!!.cancel() // rotation/cancellation while findUnfinished() is on IO
            release.countDown()
            await { !ScanLauncher.resumeOfferShowingForTest() }
            assertFalse("no offer was shown", sheetPrimary() == app.getString(R.string.ux130_l2_resume_scan))

            ShadowDialog.reset()
            ScanLauncher.beforeResumeLookupForTest = null
            scanEntry()
            await { sheetPrimary() == app.getString(R.string.ux130_l2_resume_scan) }
        }
    }

    @Test
    fun `a partial gallery import says the earlier pages were kept`() {
        val dir = staging()
        val good = File(app.cacheDir, "fix_good_${System.nanoTime()}.jpg").apply {
            writeBytes(ImportFixtures.image(width = 90))
        }
        val bad = File(app.cacheDir, "fix_bad_${System.nanoTime()}.jpg").apply {
            writeBytes("not an image".toByteArray())
        }
        dirs += good; dirs += bad
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Partial test")
        ).use { scenario ->
            scenario.onActivity { a ->
                a.appendStagedPages(listOf(Uri.fromFile(good), Uri.fromFile(bad)))
            }
            await {
                ShadowToast.getTextOfLatestToast() ==
                    app.getString(R.string.ux130_l2_gallery_partial_failure)
            }
            assertTrue("the first page was appended", File(dir, "page_002.jpg").exists())
        }
    }

    @Test
    fun `the gallery fallback path uses the HEIC-aware normalizer`() {
        val dir = staging()
        val photo = File(app.cacheDir, "fix_photo_${System.nanoTime()}.jpg").apply {
            writeBytes(ImportFixtures.image(width = 100))
        }
        dirs += photo
        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Fallback test")
        ).use { scenario ->
            scenario.onActivity { a ->
                com.scanwhirl.app.tools.appendGalleryPages(a, listOf(Uri.fromFile(photo)))
            }
            await { File(dir, "page_002.jpg").exists() }
            assertTrue(
                "HEIC-aware gallery normalizer used",
                scenario.get { it.lastAppendUsedHeicAwareForTest }
            )
        }
    }

    private fun assertNotNullJob(job: kotlinx.coroutines.Job?) {
        assertTrue("resume lookup job exists", job != null)
    }

    private fun <T> ActivityScenario<ScanReviewActivity>.get(block: (ScanReviewActivity) -> T): T {
        var out: T? = null
        onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }
}
