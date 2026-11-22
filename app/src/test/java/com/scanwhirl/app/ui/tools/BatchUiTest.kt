package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 03_tools_hub — Batch tools: registry entry, the L1 launcher signature, the multi-select
 * chooser, the action/progress/summary sheets, and Free landing on the Pro screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BatchUiTest {

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

    @Test
    fun `the registry entry is a Pro Ready tool`() {
        val tool = requireNotNull(ToolRegistry.byId("batch_tools"))
        assertEquals(ToolStatus.READY, tool.status)
        assertTrue(tool.pro)
    }

    @Test
    fun `the primary button carries its label for the screen and TalkBack`() {
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, emptyList())).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val go = activity.findViewById<TextView>(R.id.batchContinue)
                assertNotNull("the primary button exists", go)
                assertEquals(
                    "the blank cyan pill must show its label",
                    activity.getString(R.string.tools_batch_continue),
                    go.text.toString()
                )
                assertEquals(
                    "TalkBack must hear the same label",
                    activity.getString(R.string.tools_batch_continue),
                    go.contentDescription?.toString()
                )
            }
        }
    }

    @Test
    fun `free lands on the shell Pro offer with real billing`() {
        ProGate.setTestOverride(app, false)
        val controller = Robolectric.buildActivity(
            BatchActivity::class.java,
            BatchActivity.intent(app, emptyList())
        ).setup()
        val activity = controller.get()
        idle()

        assertTrue("a Free direct Batch intent closes itself", activity.isFinishing)
        assertNull("no local Pro screen with a dead Buy path", activity.findViewById<View>(R.id.swProScreen))
        val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
            "closing Batch must open the Pro offer"
        }
        assertEquals(ShellActivity::class.java.name, started.component?.className)
        assertTrue(
            "the shell shows its Pro screen",
            started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `the chooser selects all documents`() {
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, emptyList())).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val list = activity.findViewById<LinearLayout>(R.id.batchList)
                assertEquals(2, list.childCount)
                activity.findViewById<View>(R.id.batchSelectAll).performClick()
                idle()
                assertEquals(
                    activity.getString(R.string.tools_batch_count, 2, 2),
                    activity.findViewById<TextView>(R.id.batchCount).text.toString()
                )
            }
        }
    }

    @Test
    fun `the library launcher opens the batch with the selected ids`() {
        val ids = repo.listDocuments().map { it.id }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> BatchLauncher.start(activity, ids) }
            idle()
            val started = shadowOf(app).nextStartedActivity
            assertNotNull("batch opens", started)
            assertEquals(BatchActivity::class.java.name, started.component?.className)
            assertEquals(ids, started.getStringArrayListExtra(BatchActivity.EXTRA_DOC_IDS))
        }
    }

    @Test
    fun `running compress from the action sheet ends in a summary`() {
        val ids = repo.listDocuments().map { it.id }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue("the action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionCompress) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionCompress).performClick()
                idle()
                val options = ShadowDialog.getLatestDialog()
                assertNotNull("compress options", options.findViewById<View>(R.id.batchTarget))
                options.findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("two copies are published", waitUntil {
                repo.listDocuments().count { it.name.endsWith(" (2)") } == 2
            })
            assertTrue("the summary opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchSummary) != null
            })
            idle()
            val summary = ShadowDialog.getLatestDialog()
            val title = summary.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            assertTrue("summary title: $title", title.contains("2 done") && title.contains("0 failed"))
        }
    }
}
