package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * TYPE12 item 4: the Batch Protect sheet takes a password, so it needs the same FLAG_SECURE
 * as every other password sheet in the app (screenshots, recents and casting must not show it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BatchProtectSheetSecureTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        ProGate.setTestOverride(app, true)
        DocFixtures.saved(repo, app, pages = 1, name = "One")
        DocFixtures.saved(repo, app, pages = 1, name = "Two")
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

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

    @Test(timeout = 30_000)
    fun `the batch protect password window blocks screenshots`() {
        val ids = repo.listDocuments().map { it.id }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue(
                "the action sheet opens",
                waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionProtect) != null }
            )
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionProtect).performClick()
                idle()
            }
            assertTrue(
                "the password sheet opens",
                waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchPassword) != null }
            )
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(
                "the batch password window needs FLAG_SECURE like every other password sheet",
                SecureView.isSecure(dialog.window)
            )
        }
    }
}
