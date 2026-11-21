package com.scanwhirl.app.ux130.l3

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * L3-02 P2 — every result producer must do its identity hashing off the main
 * thread. Two real producers are exercised end to end with the probe attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_02_ProducerThreadProbeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var threads: MutableList<Thread>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
        threads = mutableListOf()
        ToolResultActivity.identityThreadProbe = { threads += it }
    }

    @After
    fun tearDown() {
        ToolResultActivity.identityThreadProbe = null
        Faults.resetAll()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(25L)
        }
        idle()
    }

    private fun assertOffMain(label: String) {
        assertTrue("$label: no identity hash was captured", threads.isNotEmpty())
        val main = Looper.getMainLooper().thread
        assertTrue("$label hashed on main: $threads", threads.none { it === main })
    }

    @Test
    fun `ToolSetupActivity producer hashes off main`() {
        DocFixtures.saved(repo, app, pages = 1, name = "producer doc")
        val controller = org.robolectric.Robolectric.buildActivity(
            ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "compress")
        ).setup()
        val activity = controller.get()
        try {
            waitUntil {
                val host = activity.findViewById<LinearLayout>(R.id.toolSetupSource)
                host != null && host.childCount > 0
            }
            val host = activity.findViewById<LinearLayout>(R.id.toolSetupSource)
            val row = (0 until host.childCount).map { host.getChildAt(it) }
                .first { it is LinearLayout && it.id == R.id.toolSetupDocRow }
            row.performClick()
            idle()
            activity.findViewById<View>(R.id.toolSetupRun).performClick()
            waitUntil {
                shadowOf(app).nextStartedActivity?.component?.className ==
                    ToolResultActivity::class.java.name
            }
            assertOffMain("ToolSetupActivity compress")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `CompressFlow producer hashes off main`() {
        CompressFlow.estimateForTest = { _, _ -> 120_000L }
        try {
            val docId = DocFixtures.saved(repo, app, pages = 1, name = "compress producer")
            val document = requireNotNull(repo.getDocument(docId))
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    CompressFlow(activity, repo, activity, {}, { listOf(document) })
                        .startWith(document)
                }
                waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetPrimary) != null }
                scenario.onActivity { _ ->
                    val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView as ViewGroup
                    decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                }
                waitUntil {
                    shadowOf(app).nextStartedActivity?.component?.className ==
                        ToolResultActivity::class.java.name
                }
            }
            assertOffMain("CompressFlow")
        } finally {
            CompressFlow.estimateForTest = null
        }
    }
}
