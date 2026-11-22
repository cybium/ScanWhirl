package com.scanwhirl.app.v2c

import android.app.Application
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.V2cReceiptStore
import com.scanwhirl.app.ui.ScanReviewActivity
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
import java.io.File

/** Review follow-up: no orphaned .retake backup and no unbounded receipt log. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2cCleanupTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dirs = mutableListOf<File>()

    @Before fun reset() {
        V2cReceiptStore.resetForTest(app)
    }

    @After fun clean() {
        V2cReceiptStore.resetForTest(app)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun stage(): File {
        val dir = PendingScans.create(app)
        repeat(2) { File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 120 + it)) }
        dirs += dir
        return dir
    }

    private fun review(dir: File) = Robolectric.buildActivity(
        ScanReviewActivity::class.java,
        ScanReviewActivity.intent(app, dir.path, null, "Docs")
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(app.mainLooper).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        shadowOf(app.mainLooper).idle()
        assertTrue("timed out waiting for condition", condition())
    }

    @Test(timeout = 30_000)
    fun `undo timeout removes the retake backup`() {
        val dir = stage()
        var controller: ActivityController<ScanReviewActivity>? = null
        try {
            controller = review(dir).setup()
            val activity = controller.get()
            activity.replaceStagedPage(0, ImportFixtures.image(width = 175))
            await {
                com.scanwhirl.app.util.ImageTools.imageSize(
                    File(dir, "page_000.jpg").readBytes()
                )?.first == 175
            }
            assertTrue("backup exists while Undo is offered", File(dir, "page_000.jpg.retake").exists())
            val bar = activity.lastSnackbarForTest()
            assertNotNull("retake Undo snackbar exists", bar)
            bar!!.dismiss() // timeout / outside dismissal, not the Undo action
            shadowOf(app.mainLooper).idle()
            assertFalse("no backup may outlive the Undo window", File(dir, "page_000.jpg.retake").exists())
        } finally {
            controller?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `process death leftover backup is removed with the review flow`() {
        val dir = stage()
        val leftover = File(dir, "page_000.jpg.retake").apply { writeBytes(ImportFixtures.image(width = 90)) }
        var controller: ActivityController<ScanReviewActivity>? = null
        try {
            controller = review(dir).setup()
            controller.get().finish()
            controller.pause().stop().destroy()
            assertFalse("stale backup removed when review finishes", leftover.exists())
        } finally {
            controller?.close()
        }
    }

    @Test(timeout = 30_000)
    fun `review receipts live in staging and the global log is capped`() {
        val dir = stage()
        repeat(25) { V2cReceiptStore.record(app, "book", "Book pages", "Outcome") }
        V2cReceiptStore.record(app, "scan", "Scan", "Saved report name")
        val global = File(app.filesDir, "v2c_receipts_v1.txt")
        assertTrue("global log exists", global.isFile)
        assertTrue("global log is capped", global.readLines().size <= 20)

        var controller: ActivityController<ScanReviewActivity>? = null
        try {
            controller = review(dir).setup()
            controller.get().recordReceipt(
                controller.get().getString(R.string.v2c_receipt_saved), "Secret doc name"
            )
            assertTrue("receipt stored in staging", File(dir, "v2c_receipts_v1.txt").isFile)
            assertEquals(
                controller.get().getString(R.string.v2c_receipt_saved),
                V2cReceiptStore.latestIn(dir)!!.name
            )
            assertFalse("no document name in the global log", global.readText().contains("Secret doc name"))
            assertTrue(
                "receipt visible on screen",
                controller.get().findViewById<TextView>(R.id.v2cReceipt).text.toString()
                    .contains(controller.get().getString(R.string.v2c_receipt_saved))
            )
        } finally {
            controller?.close()
        }
    }
}
