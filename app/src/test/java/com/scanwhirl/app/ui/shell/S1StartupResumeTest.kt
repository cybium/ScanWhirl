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
import org.robolectric.android.controller.ActivityController
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
        // These are process-wide seams; a test that failed mid-flight must not leak a queued
        // offer job or the "sheet showing" guard into the next test in the same fork.
        ScanLauncher.resetResumeOfferStateForTest()
        ShadowDialog.reset()
    }

    @org.junit.After
    fun resetResumeState() {
        ScanLauncher.resetColdStartOfferForTest()
        ScanLauncher.resetResumeOfferStateForTest()
        ShadowDialog.reset()
        kotlinx.coroutines.Dispatchers.resetMain()
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

    @Test(timeout = 30_000)
    fun `the launcher offers a pending scan after recovery and never repeats it on rotation`() {
        val staged = stagePending()
        try {
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
        } finally {
            staged.deleteRecursively()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test(timeout = 30_000)
    fun `rotation while the offer coroutine is queued still shows the resume offer`() {
        val staged = stagePending()
        // onCreate schedules the offer on lifecycleScope (Main). The old test only did create()
        // and *assumed* that coroutine had not run yet; on a loaded runner it could reach
        // show() and consume the once-per-process guard before rotation. Queue Main for this
        // window so the first instance is deterministically cancelled before it can show.
        // This pins the cancel-before-show window, not a pending storage-recovery state.
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        var first: ActivityController<ShellActivity>? = null
        var recreated: ActivityController<ShellActivity>? = null
        try {
            val firstController = Robolectric.buildActivity(ShellActivity::class.java).create()
            first = firstController
            val state = android.os.Bundle()
            firstController.saveInstanceState(state)
            // Cleared before the explicit destroy: if destroy itself throws, do not call it twice.
            // Any earlier exception leaves `first` set so the finally still destroys it.
            first = null
            firstController.destroy()
            ShadowDialog.reset()
            val guardAfterA = ScanLauncher.coldStartOfferConsumedForTest()
            assertTrue("no offer was shown by the first instance before rotation", !guardAfterA)
            // Drain the cancelled first-instance work; the recreated launcher then runs its own
            // offer on the real main dispatcher. No offer-state reset between the two instances:
            // the once-per-process cold-start guard must still be un-consumed after rotation.
            scheduler.advanceUntilIdle()
            Dispatchers.resetMain()
            recreated = Robolectric.buildActivity(ShellActivity::class.java).setup(state)
            idle()
            await { resumeOfferShown() }
            assertTrue("the unfinished pages are kept until the owner chooses", staged.isDirectory)
        } finally {
            first?.destroy()
            recreated?.pause()?.stop()?.destroy()
            Dispatchers.resetMain()
            staged.deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
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
