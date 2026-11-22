package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.util.ImportFixtures
import org.robolectric.Robolectric
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
import java.io.File

/**
 * S1 defect 1: the launcher (ShellActivity) never called ScanLauncher.offerUnfinishedScan —
 * only the legacy MainActivity did. Pages left in filesDir/pending_scans after process death
 * were therefore never offered. The offer must run once per cold start, after storage recovery,
 * and must not appear a second time after a rotation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1StartupResumeTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun resetColdStartGuard() {
        ScanLauncher.resetColdStartOfferForTest()
        ShadowDialog.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(25)
        }
        idle()
        assertTrue("timed out waiting for the resume offer", condition())
    }

    private fun stagePending(): File {
        val dir = File(app.filesDir, PendingScans.ROOT).let { root ->
            root.mkdirs()
            File(root, "pending_${System.nanoTime()}").apply { mkdirs() }
        }
        File(dir, "page_001.jpg").writeBytes(ImportFixtures.image(width = 120))
        return dir
    }

    private fun resumeOfferShown(): Boolean {
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog?.isShowing != true) return false
        val primary = dialog.findViewById<View>(R.id.swSheetPrimary) as? TextView ?: return false
        return primary.text.toString() == app.getString(R.string.resume_scan_resume)
    }

    @Test
    fun `the launcher offers a pending scan after recovery and never repeats it on rotation`() {
        val staged = stagePending()
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            await { resumeOfferShown() }
            assertTrue("the unfinished pages are still on disk while the offer is up", staged.isDirectory)

            // Rotation: the sheet is recreated by the framework, but a *new* offer must not run.
            scenario.recreate()
            idle()
            ShadowDialog.getLatestDialog()?.dismiss()
            idle()
            var repeated = false
            for (i in 0 until 20) {
                idle()
                if (resumeOfferShown()) repeated = true
            }
            assertFalse("rotation must not offer the same scan a second time", repeated)
        }
    }

    @Test
    fun `rotation while recovery is still pending still shows the resume offer`() {
        val staged = stagePending()
        // Create only (no setup/resume): the first instance's offer coroutine has not been
        // dispatched yet, so rotation cancels it before the sheet is shown — the P1 case.
        val first = Robolectric.buildActivity(ShellActivity::class.java).create()
        val state = android.os.Bundle()
        first.saveInstanceState(state)
        first.destroy()
        ShadowDialog.reset()
        val guardAfterA = ScanLauncher.coldStartOfferConsumedForTest()
        Robolectric.buildActivity(ShellActivity::class.java).setup(state)
        idle()
        assertTrue("no offer was shown by the first instance before rotation", !guardAfterA)
        await { resumeOfferShown() }
        assertTrue("the unfinished pages are kept until the owner chooses", staged.isDirectory)
    }

    @Test
    fun `a fresh start with no pending scan shows no resume offer`() {
        val root = File(app.filesDir, PendingScans.ROOT)
        root.deleteRecursively()
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            for (i in 0 until 20) idle()
            val shown = ShadowDialog.getLatestDialog()?.isShowing == true &&
                (ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetPrimary) as? TextView)
                    ?.text?.toString() == app.getString(R.string.resume_scan_resume)
            assertFalse("nothing to resume means no offer", shown)
        }
    }
}
