package com.scanwhirl.app.ux130.l2
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * L2-04: an unfinished scan stays discoverable until the owner resumes or
 * discards it, and a failed or empty scanner always offers a real way out
 * without repeating a numeric error code.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScannerFallbackResumeTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var pending: File? = null

    @Before fun reset() {
        File(app.filesDir, PendingScans.ROOT).deleteRecursively()
        ScanLauncher.resetColdStartOfferForTest()
        ShadowDialog.reset()
    }

    @After fun clean() {
        File(app.filesDir, PendingScans.ROOT).deleteRecursively()
        ShadowDialog.reset()
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

    private fun button(id: Int): String? =
        (ShadowDialog.getLatestDialog()?.findViewById<View>(id) as? TextView)?.text?.toString()

    private fun sheetText(): String =
        listOf(R.id.swSheetTitle, R.id.swSheetMessage, R.id.swSheetPrimary, R.id.swSheetSecondary)
            .mapNotNull { button(it) }.joinToString(" ")

    private fun pendingScan(): File {
        val dir = PendingScans.create(app)
        File(dir, "page_001.jpg").writeBytes(ImportFixtures.image(width = 120))
        pending = dir
        return dir
    }

    @Test
    fun `scanner unavailable sheet is truthful and has no numeric code`() {
        ActivityScenario.launch<ShellActivity>(android.content.Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> ScanLauncher(a, com.scanwhirl.app.ScanWhirlApp.from(a).repository, a) {}.launchScanner() }
            await { button(R.id.swSheetPrimary) == app.getString(R.string.ux130_l2_pick_photos) }
            assertEquals(app.getString(R.string.ux130_l2_scanner_unavailable_title), button(R.id.swSheetTitle))
            val text = sheetText()
            assertFalse("no user-facing status code: $text", Regex("\\(\\s*code\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse("no raw reason token: $text", text.contains("%1"))
        }
    }

    @Test
    fun `an empty successful scan offers the same photo fallback, not an error`() {
        ActivityScenario.launch<ShellActivity>(android.content.Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> ScanLauncher(a, com.scanwhirl.app.ScanWhirlApp.from(a).repository, a) {}.saveScanned(emptyList()) }
            await { button(R.id.swSheetPrimary) == app.getString(R.string.ux130_l2_pick_photos) }
            assertEquals(app.getString(R.string.ux130_l2_empty_scan_title), button(R.id.swSheetTitle))
            assertEquals(app.getString(R.string.ux130_l2_empty_scan_msg), button(R.id.swSheetMessage))
        }
    }

    @Test
    fun `the unfinished scan is offered again from Scan until it is discarded`() {
        val dir = pendingScan()
        ActivityScenario.launch<ShellActivity>(android.content.Intent(app, ShellActivity::class.java)).use { scenario ->
            fun fromScanEntry() = scenario.onActivity { a ->
                ScanLauncher(a, com.scanwhirl.app.ScanWhirlApp.from(a).repository, a) {}.launchScanner()
            }
            fromScanEntry()
            await { button(R.id.swSheetPrimary) == app.getString(R.string.ux130_l2_resume_scan) }
            assertTrue("staging survives the offer", dir.isDirectory)

            // Dismiss without a decision: the offer must come back next time.
            ShadowDialog.getLatestDialog()?.dismiss()
            shadowOf(app.mainLooper).idle()
            fromScanEntry()
            await { button(R.id.swSheetPrimary) == app.getString(R.string.ux130_l2_resume_scan) }

            (ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetSecondary) as? TextView)?.performClick()
            shadowOf(app.mainLooper).idle()
            assertFalse("explicit discard only removes this staging", dir.exists())
        }
    }
}
