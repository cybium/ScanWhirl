package com.scanwhirl.app.v2t

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * V2T review fix: a durable receipt whose output id was reused must never
 * authorize Compress / Protect / Undo on another document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V2t_T2_ChainIdentityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
    }

    @After
    fun tearDown() = Faults.resetAll()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20L)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) idle()
        idle()
    }

    private fun recordResult(source: String?, output: String) {
        // The real producer captures D2 on IO at publish time. Store that strong identity in
        // the durable receipt so a matching output still authorises chaining; a reused id or
        // changed bytes still fail the same re-check and refuse.
        val identity = requireNotNull(
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(output), emptyList())
        )
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output),
                receiptSourceId = source, outputIdentity = identity)
        ).use { scenario -> scenario.onActivity { it.finish() } }
        idle()
    }

    private fun reuseId(output: String, name: String) {
        val other = DocFixtures.saved(repo, app, pages = 1, name = name)
        File(repo.docsDir, output).deleteRecursively()
        File(repo.docsDir, other).copyRecursively(File(repo.docsDir, output))
    }

    private fun refusalVisible(scenario: ActivityScenario<ToolResultActivity>): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            val error = activity.findViewById<TextView>(R.id.toolResultError)
            shown = error.isVisible &&
                error.text.toString() == app.getString(R.string.ux130_l3_copy_changed)
        }
        return shown
    }

    private fun drainStarted() {
        shadowOf(app).nextStartedActivity
    }

    @Test
    fun `a reused output id refuses Compress this copy and launches nothing`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "chain output")
        val source = DocFixtures.saved(repo, app, pages = 1, name = "chain source")
        recordResult(source, output)
        reuseId(output, "new owner")

        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                drainStarted()
                scenario.onActivity {
                    it.findViewById<View>(R.id.toolResultMore).performClick()
                    idle()
                    it.findViewById<View>(R.id.v2t_compress_copy).performClick()
                }
                waitUntil { refusalVisible(scenario) }
                assertTrue("the honest refusal is visible", refusalVisible(scenario))
                val started = shadowOf(app).nextStartedActivity
                assertNull("nothing was launched: ${started?.component?.className}", started)
            }
        assertEquals("new owner", repo.getDocument(output)?.name)
    }

    @Test
    fun `a reused output id refuses Protect this copy and launches nothing`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "protect output")
        recordResult(null, output)
        reuseId(output, "protect owner")

        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                drainStarted()
                scenario.onActivity {
                    it.findViewById<View>(R.id.toolResultMore).performClick()
                    idle()
                    it.findViewById<View>(R.id.v2t_protect_copy).performClick()
                }
                waitUntil { refusalVisible(scenario) }
                assertTrue("the honest refusal is visible", refusalVisible(scenario))
                val started = shadowOf(app).nextStartedActivity
                assertNull("nothing was launched: ${started?.component?.className}", started)
            }
        assertEquals("protect owner", repo.getDocument(output)?.name)
    }

    @Test
    fun `a matching output still chains Compress with the verified id`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "matching output")
        recordResult(null, output)
        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                drainStarted()
                scenario.onActivity {
                    it.findViewById<View>(R.id.toolResultMore).performClick()
                    idle()
                    it.findViewById<View>(R.id.v2t_compress_copy).performClick()
                }
                var started: android.content.Intent? = null
                waitUntil {
                    val s = shadowOf(app).nextStartedActivity
                    if (s != null) started = s
                    started?.component?.className == ToolSetupActivity::class.java.name
                }
                assertNotNull("Compress setup was launched", started)
                assertEquals(output, started?.getStringExtra("v2t_selected_doc_id"))
            }
    }

    @Test
    fun `a changed output refuses Undo on a reopened receipt`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "changed output")
        recordResult(null, output)
        requireNotNull(repo.getDocument(output)).pdfFile.writeBytes("different bytes".toByteArray())
        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
                waitUntil { refusalVisible(scenario) }
                assertTrue("the honest refusal is visible", refusalVisible(scenario))
            }
        assertNotNull("changed output survives the refused Undo", repo.getDocument(output))
    }
}
