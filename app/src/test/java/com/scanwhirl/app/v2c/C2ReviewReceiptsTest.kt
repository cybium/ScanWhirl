package com.scanwhirl.app.v2c
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.V2cFailureMap
import com.scanwhirl.app.tools.V2cReceiptStore
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** V2C C2: durable named receipts, localized failures and one-page retake. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class C2ReviewReceiptsTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dirs = mutableListOf<File>()

    @Before fun reset() {
        V2cReceiptStore.resetForTest(app)
        ShadowDialog.reset()
    }

    @After fun clean() {
        V2cReceiptStore.resetForTest(app)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun stage(count: Int = 3): File {
        val dir = PendingScans.create(app)
        repeat(count) {
            File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 120 + it))
        }
        dirs += dir
        return dir
    }

    private fun review(dir: File): ActivityController<ScanReviewActivity> =
        Robolectric.buildActivity(
            ScanReviewActivity::class.java,
            ScanReviewActivity.intent(app, dir.path, null, "Docs")
        )

    private fun idle() = shadowOf(app.mainLooper).idle()

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(20)
        }
        idle()
        assertTrue("timed out waiting for condition", condition())
    }

    @Test
    fun `receipts survive a fresh read and are keyed by name`() {
        V2cReceiptStore.record(app, "scan", "Pages added", "3 pages")
        V2cReceiptStore.record(app, "staging-1", "Document saved", "Rent")
        val scan = V2cReceiptStore.latest(app, "scan")
        assertNotNull(scan)
        assertEquals("Pages added", scan!!.name)
        assertEquals("Document saved", V2cReceiptStore.latest(app, "staging-1")!!.name)
        assertEquals("Rent", V2cReceiptStore.latest(app)!!.detail)
    }

    @Test
    fun `failure mapping never returns raw exception text`() {
        val raw = "fatal com.example.SecretException at /private/path"
        assertEquals(
            app.getString(R.string.v2c_error_unreadable),
            V2cFailureMap.message(app, IllegalArgumentException(raw))
        )
        assertEquals(
            app.getString(R.string.v2c_error_too_large),
            V2cFailureMap.message(app, OutOfMemoryError(raw))
        )
        assertEquals(
            app.getString(R.string.v2c_error_no_space),
            V2cFailureMap.message(app, IllegalStateException("No space left on device"))
        )
        val generic = V2cFailureMap.message(app, Exception(raw))
        assertFalse("raw text leaked: $generic", generic.contains(raw))
    }

    @Test(timeout = 30_000)
    fun `retake replacement keeps page count order filters and a named receipt`() {
        val dir = stage()
        var controller: ActivityController<ScanReviewActivity>? = null
        try {
            controller = review(dir).setup()
            val activity = controller.get()
            val replacement = ImportFixtures.image(width = 200)
            activity.replaceStagedPage(1, replacement)
            await { ImageTools.imageSize(File(dir, "page_001.jpg").readBytes())?.first == 200 }
            assertEquals(3, PendingScans.pages(dir).size)
            assertEquals(200, ImageTools.imageSize(File(dir, "page_001.jpg").readBytes())!!.first)
            assertEquals(122, ImageTools.imageSize(File(dir, "page_002.jpg").readBytes())!!.first)
            assertEquals(
                app.getString(R.string.v2c_receipt_retake),
                V2cReceiptStore.latestIn(dir)!!.name
            )
            val shown = activity.findViewById<TextView>(R.id.v2cReceipt).text.toString()
            assertTrue("named receipt shown: $shown", shown.contains(app.getString(R.string.v2c_receipt_retake)))
        } finally {
            controller?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `cancelling the retake sheet keeps the old page and records nothing`() {
        val dir = stage()
        var controller: ActivityController<ScanReviewActivity>? = null
        try {
            controller = review(dir).setup()
            val activity = controller.get()
            activity.showRetakeSheet()
            idle()
            assertNotNull(ShadowDialog.getLatestDialog())
            ShadowDialog.getLatestDialog()!!.dismiss()
            idle()
            assertEquals(120, ImageTools.imageSize(File(dir, "page_000.jpg").readBytes())!!.first)
            assertEquals(null, V2cReceiptStore.latestIn(dir))
        } finally {
            controller?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `a replaced page and its receipt survive rotation`() {
        val dir = stage()
        var first: ActivityController<ScanReviewActivity>? = null
        var recreated: ActivityController<ScanReviewActivity>? = null
        try {
            first = review(dir).setup()
            first.get().replaceStagedPage(0, ImportFixtures.image(width = 175))
            await { ImageTools.imageSize(File(dir, "page_000.jpg").readBytes())?.first == 175 }
            val state = Bundle()
            first.saveInstanceState(state)
            first.pause().stop().destroy()

            recreated = Robolectric.buildActivity(
                ScanReviewActivity::class.java,
                ScanReviewActivity.intent(app, dir.path, null, "Docs")
            ).create(state).start().resume().visible()
            idle()
            assertEquals(175, ImageTools.imageSize(File(dir, "page_000.jpg").readBytes())!!.first)
            assertTrue(
                recreated.get().findViewById<TextView>(R.id.v2cReceipt).text.toString()
                    .contains(app.getString(R.string.v2c_receipt_retake))
            )
        } finally {
            recreated?.close()
            first?.close()
        }
    }
    // W8: the Book-pages failure test was deleted with the unreachable activity.
    // No raw exception text is still proven by the localized-reason assertion earlier in
    // this class and by V1LocalizedErrorsTest / ImportFailureWordingTest; the durable
    // receipt is proven by the receipt tests above and by V2cCleanupTest.
}
