package com.scanwhirl.app.v2t

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.ui.tools.ToolResultActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1 — durable result receipt and honest reopen across process death.
 * RED on base: no lane strings and no receipt store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V2t_T1_DurableReceiptTest {

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

    @Test
    fun `EN and HI carry the new copy and failure wording with parity`() {
        val en = V2tResources.en()
        val hi = V2tResources.hi()
        assertEquals("lane names match", en.keys.toSet(), hi.keys.toSet())
        assertEquals("New copy; original kept.", en["v2t_new_copy_original_kept"])
        assertEquals("नई प्रति; मूल फ़ाइल रखी गई।", hi["v2t_new_copy_original_kept"])
        assertEquals("Original is no longer in the library.", en["v2t_original_missing"])
        assertEquals("Could not merge these PDFs. Choose different files.", en["v2t_merge_failed"])
        assertEquals("इन PDF को मिलाया नहीं जा सका। दूसरी फ़ाइलें चुनें।", hi["v2t_merge_failed"])
    }

    @Test
    fun `a result writes a durable receipt and reopens with the same output`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "receipt output")
        val source = DocFixtures.saved(repo, app, pages = 1, name = "receipt source")
        val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(output),
            receiptSourceId = source)
        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { it.finish() }
        }
        val store = V2tResultReceiptStore(app)
        val receipt = store.load()
        assertNotNull("receipt was written", receipt)
        assertEquals(listOf(output), receipt!!.docIds)
        assertEquals("compress", receipt.toolId)
        assertEquals(source, receipt.receiptSourceId)

        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                scenario.onActivity { activity ->
                    val summary = activity.findViewById<android.widget.TextView>(R.id.toolResultSummary)
                    val status = activity.findViewById<android.widget.TextView>(R.id.v2t_status)
                    assertNotNull("reopened result has a status line", status)
                    val name = activity.findViewById<android.widget.TextView>(R.id.toolResultName)
                    assertTrue("reopened output name is shown: ${name.text}",
                        name.text.toString().contains("receipt output"))
                    assertEquals(app.getString(R.string.v2t_new_copy_original_kept), status.text.toString())
                }
            }
    }

    @Test
    fun `reopening never claims the original is kept when it is gone`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "orphan output")
        val source = DocFixtures.saved(repo, app, pages = 1, name = "gone source")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output), receiptSourceId = source)
        ).use { scenario -> scenario.onActivity { it.finish() } }
        // Simulate the source being removed before the durable result is reopened.
        repo.moveToTrash(source)
        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app))
            .use { scenario ->
                scenario.onActivity { activity ->
                    val status = activity.findViewById<android.widget.TextView>(R.id.v2t_status)
                    assertEquals(app.getString(R.string.v2t_original_missing), status.text.toString())
                    val summary = activity.findViewById<android.widget.TextView>(R.id.toolResultSummary).text.toString()
                    assertFalse("no stale original-kept claim: $summary",
                        summary.contains(app.getString(R.string.compress_result_source_kept)))
                }
            }
    }

    @Test
    fun `status line exposes a live accessible status role`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "a11y output")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val status = activity.findViewById<android.view.View>(R.id.v2t_status)
                assertNotNull(status)
                assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    status.accessibilityLiveRegion)
                assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                    status.importantForAccessibility)
            }
        }
    }
}
