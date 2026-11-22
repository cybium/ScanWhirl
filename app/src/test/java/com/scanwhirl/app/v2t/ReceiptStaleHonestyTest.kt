package com.scanwhirl.app.v2t

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.ui.tools.ToolResultActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/**
 * F3 — a durable last-result receipt can outlive the cache bytes it names. Reopening it must be
 * honest: any missing output means no Open / Share / Undo / preview and one short plain state.
 * Every lifecycle case uses ActivityController + destroy in finally (ActivityScenario.close()
 * hung CI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiptStaleHonestyTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        V2tResultReceiptStore(app).clear()
    }

    private fun cacheOutput(name: String): File =
        File(app.cacheDir, "documents/tools/f3/$name").apply {
            parentFile?.mkdirs()
            if (!isFile) writeBytes("f3 fake output".toByteArray())
        }

    private fun writeReceipt(
        toolId: String = "compress",
        docIds: List<String> = emptyList(),
        files: List<String> = emptyList(),
        identity: String? = null,
    ) {
        V2tResultReceiptStore(app).save(
            V2tResultReceiptStore.Receipt(
                toolId = toolId,
                docIds = docIds,
                files = files,
                mime = "application/pdf",
                note = null,
                targetBytes = null,
                beforeBytes = null,
                outputIdentity = identity,
                receiptSourceId = null,
                sourceKept = false,
                chosenLocationName = null,
                documentsExistingName = null,
                undone = false,
                savedAt = System.currentTimeMillis(),
            )
        )
    }

    /** GONE anywhere in the ancestor chain hides the control even if its own flag is VISIBLE. */
    private fun effectivelyVisible(activity: Activity, id: Int): Boolean {
        var view = activity.findViewById<View>(id) ?: return false
        while (view.visibility == View.VISIBLE && view.parent is View) {
            view = view.parent as View
        }
        return view.visibility == View.VISIBLE
    }

    private val actionIds = listOf(
        R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultMore,
        R.id.toolResultUndo, R.id.toolResultSave, R.id.toolResultLibrary,
        R.id.toolResultAlsoSave, R.id.toolResultPreviewHost, R.id.ccCompare,
        R.id.toolResultName, R.id.rsBottomBar,
    )

    private fun assertStale(activity: Activity, context: String) {
        assertEquals(
            "$context: one honest short state",
            app.getString(R.string.rs_receipt_stale),
            activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
        )
        actionIds.forEach { id ->
            assertFalse("$context: action/preview $id is hidden", effectivelyVisible(activity, id))
        }
    }

    private fun assertNormal(activity: Activity, context: String) {
        val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
        assertFalse("$context: no stale claim: $summary", summary == app.getString(R.string.rs_receipt_stale))
        listOf(R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultMore).forEach { id ->
            assertTrue("$context: action $id stays available", effectivelyVisible(activity, id))
        }
    }

    private fun reopen(): ActivityController<ToolResultActivity> {
        val controller = Robolectric.buildActivity(
            ToolResultActivity::class.java, ToolResultActivity.intentLastResult(app)
        )
        controller.setup()
        idle()
        return controller
    }

    @Test(timeout = 30_000)
    fun `a receipt whose file output was cleared reopens honestly with actions hidden`() {
        val missing = File(app.cacheDir, "documents/tools/f3/cleared.pdf")
        writeReceipt(files = listOf(missing.path))
        val controller = reopen()
        try {
            assertStale(controller.get(), "cleared cache file")
        } finally {
            controller.pause().stop().destroy()
        }
        assertFalse("nothing was recreated in place of the missing bytes", missing.isFile)
    }

    @Test(timeout = 30_000)
    fun `a receipt with a live file output keeps the normal actions`() {
        val live = cacheOutput("live.pdf")
        writeReceipt(files = listOf(live.path))
        val controller = reopen()
        try {
            assertNormal(controller.get(), "live cache file")
        } finally {
            controller.pause().stop().destroy()
        }
        assertTrue("the live output was not touched", live.isFile)
    }

    @Test(timeout = 30_000)
    fun `a docId result whose document exists keeps the normal actions`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "live document")
        writeReceipt(docIds = listOf(id))
        val controller = reopen()
        try {
            val activity = controller.get()
            assertNormal(activity, "live docId")
            assertTrue(
                "the document name is still shown",
                activity.findViewById<TextView>(R.id.toolResultName).text.toString().contains("live document")
            )
        } finally {
            controller.pause().stop().destroy()
        }
        assertEquals("live document", repo.getDocument(id)?.name)
    }

    @Test(timeout = 30_000)
    fun `a docId result whose document is gone reopens honestly`() {
        writeReceipt(docIds = listOf("f3-missing-document"))
        val controller = reopen()
        try {
            assertStale(controller.get(), "missing document")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a mixed result never claims a missing file is present`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "surviving document")
        val missing = File(app.cacheDir, "documents/tools/f3/gone.pdf")
        writeReceipt(docIds = listOf(id), files = listOf(missing.path))
        val controller = reopen()
        try {
            assertStale(controller.get(), "mixed missing file")
        } finally {
            controller.pause().stop().destroy()
        }
        assertNotNullLiveDocument(id)
    }

    @Test(timeout = 30_000)
    fun `a partly missing multi-file result does not claim every output is present`() {
        val live = cacheOutput("kept.pdf")
        val missing = File(app.cacheDir, "documents/tools/f3/removed.pdf")
        writeReceipt(files = listOf(live.path, missing.path))
        val controller = reopen()
        try {
            assertStale(controller.get(), "partly missing files")
        } finally {
            controller.pause().stop().destroy()
        }
        assertTrue("the surviving output was not deleted", live.isFile)
    }

    @Test(timeout = 30_000)
    fun `the honest state survives recreation`() {
        val missing = File(app.cacheDir, "documents/tools/f3/rotated.pdf")
        writeReceipt(files = listOf(missing.path))
        val controller = reopen()
        try {
            assertStale(controller.get(), "before rotation")
            controller.recreate()
            idle()
            assertStale(controller.get(), "after rotation")
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `EN and HI carry the same short honest line`() {
        val en = File("src/main/res/values/strings_receipt_stale.xml").readText(Charsets.UTF_8)
        val hi = File("src/main/res/values-hi/strings_receipt_stale.xml").readText(Charsets.UTF_8)
        assertTrue("EN name/value", en.contains("name=\"rs_receipt_stale\"") &&
            en.contains("This result is no longer on this phone"))
        assertTrue("HI name/value", hi.contains("name=\"rs_receipt_stale\"") &&
            hi.contains("यह परिणाम अब इस फ़ोन पर नहीं है"))
        assertTrue(
            "the Hindi line is Hindi",
            hi.any { it.code in 0x0900..0x097F }
        )
        val words = "This result is no longer on this phone".split(Regex("\\s+")).size
        assertTrue("short copy, under the 40-word budget: $words", words <= 40)
    }

    private fun assertNotNullLiveDocument(id: String) {
        assertTrue("the surviving document was not deleted", repo.getDocument(id) != null)
    }
}
