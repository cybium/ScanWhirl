package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
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
import java.io.File
import java.util.UUID

/**
 * QA run 2 (252_discard): staging mode (scan review) Back must ask before dropping a scan, and
 * "Keep editing" must keep every staged page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanReviewBackTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `back from scan review asks to discard and keep editing keeps the scan`() {
        val dir = File(app.cacheDir, "review_back_${UUID.randomUUID()}").apply { mkdirs() }
        File(dir, "page_001.jpg").writeBytes(ImportFixtures.image(width = 200, height = 300))

        ActivityScenario.launch<ScanReviewActivity>(
            ScanReviewActivity.intent(app, dir.path, null, "Review me")
        ).use { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            idle()

            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "Back must ask first" }
            assertEquals(
                app.getString(R.string.review_discard_title),
                sheet.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )
            assertTrue("the staging folder must survive the question", dir.isDirectory)

            // Keep editing: the sheet closes and the scan is still there.
            sheet.findViewById<View>(R.id.swSheetSecondary)!!.performClick()
            idle()
            assertTrue(dir.isDirectory)

            // A second Back, then Discard: the staging folder goes.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            idle()
            ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
            idle()
            assertFalse("Discard removes the staged scan", dir.exists())
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            assertTrue("the review closes", finishing)
        }
    }
}
