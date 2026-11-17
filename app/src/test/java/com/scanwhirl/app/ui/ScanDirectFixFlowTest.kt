package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Owner bug F22: when Google's ML Kit scanner returns pages, the app's second review screen
 * (ScanReviewActivity) must not open. Done saves straight through saveScanned() and the new
 * document opens in the viewer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanDirectFixFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private var n = 0

    private fun pages(count: Int): List<Uri> = (0 until count).map {
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.JPEG, width = 140 + it)
        val uri = Uri.parse("content://scanner/fixflow_${n++}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        uri
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $what")
    }

    @Test
    fun `scanner Done saves and opens the document, never the review screen`() {
        val scanned = pages(2)
        val started = mutableListOf<Intent>()
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            fun drain() = scenario.onActivity { activity ->
                while (true) {
                    val next = shadowOf(activity).nextStartedActivity ?: break
                    started += next
                }
            }
            scenario.onActivity { activity -> activity.onScannerPages(scanned) }
            await("the saved document") { repo.listDocuments().isNotEmpty() }
            await("the new document to open") {
                drain()
                started.any { it.component?.className == DocumentViewerActivity::class.java.name }
            }
        }
        assertTrue(
            "the scan opens its new document: ${started.map { it.component?.className }}",
            started.any { it.component?.className == DocumentViewerActivity::class.java.name }
        )
        assertFalse(
            "Google's Done must not open the app's second review screen",
            started.any { it.component?.className == ScanReviewActivity::class.java.name }
        )
        val doc = repo.listDocuments().single()
        assertEquals(2, doc.pageCount)
        assertTrue("nothing is left staged for a review", PendingScans.all(app).isEmpty())
    }
}
