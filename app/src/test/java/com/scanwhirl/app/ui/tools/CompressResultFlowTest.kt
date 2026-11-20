package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.StatusLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * B2-M2 (real-phone sweep 2026-11-19, build 2 = 1485874c): Compress started from a document's
 * More row went to the old export screen. It showed only "Save to Documents" — no "Save as…",
 * no "Also save in Documents", no Undo, and the compressed copy could not be taken back — while
 * the same Compress from the Tools hub ended on [ToolResultActivity] with all of them.
 *
 * These tests pin the one result both routes now share, driven through the real path the sweep
 * used (document More -> Compress -> level sheet -> OK):
 *  - the result is [ToolResultActivity] with `compress`, docIds = the NEW compressed document
 *    only (the source is never named, so Undo can never purge it);
 *  - the before -> after size line survives on the shared result;
 *  - the result offers "Also save in Documents" and "Save as…";
 *  - Undo removes only the compressed copy; the source keeps its pages and PDF bytes;
 *  - a target size the compression could not reach is still said out loud.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompressResultFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    /** What the source document looked like before the run, for the untouched-source checks. */
    private class Source(val id: String, val pdf: ByteArray, val pages: List<ByteArray>)

    @Before
    fun setUp() {
        // androidx FileProvider caches path roots per authority across classes (same guard as
        // ToolsUiTest / ResultAlsoSaveTest).
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    // ---- 1. the route and what it names -----------------------------------

    @Test
    fun `document menu compress ends on the shared tool result with the new document only`() {
        val (started, source) = compressFromDocumentMore(name = "Compress me")

        assertEquals(
            "B2-M2: a document-menu Compress must end on the same result as the Tools hub",
            ToolResultActivity::class.java.name,
            started.component?.className
        )
        assertEquals("compress", started.getStringExtra(EXTRA_TOOL_ID))
        val ids = started.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
        assertEquals("the result names exactly one document: $ids", 1, ids.size)
        val newId = ids.single()
        assertNotEquals("the source document is never named in the result", source.id, newId)
        assertTrue(
            "no target was asked for on a level compress",
            started.getLongExtra(EXTRA_TARGET, 0L) == 0L
        )

        val copy = requireNotNull(repo.getDocument(newId)) { "the compressed copy is in the library" }
        assertTrue("the copy is named after the source: ${copy.name}", copy.name.contains("Compress me"))
        assertTrue("the source keeps its pages", requireNotNull(repo.getDocument(source.id)).pageFiles.size == 2)
        assertArrayEquals(
            "the source PDF is byte-for-byte unchanged",
            source.pdf,
            requireNotNull(repo.getDocument(source.id)).pdfFile.readBytes()
        )

        // The shared result itself: the actions the old screen lacked, and the size line.
        ActivityScenario.launch<ToolResultActivity>(started).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val also = activity.findViewById<TextView>(R.id.toolResultAlsoSave)
                assertNotNull("B2-M2: the result must offer Also save in Documents", also)
                assertEquals("Also save in Documents", also.text.toString())
                assertNotNull(
                    "B2-M2: the result must offer Save as… (system picker)",
                    activity.findViewById<View>(R.id.toolResultSave)
                )
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                val beforeLine = app.getString(
                    R.string.compress_result_before,
                    StatusLine.formatBytes(source.pdf.size.toLong())
                )
                assertTrue(
                    "B2-M2/B3: the before size must survive as its own line: '$summary'",
                    summary.contains(beforeLine)
                )
                assertEquals(
                    "B3: the result leads with the measured size",
                    app.getString(
                        R.string.compress_result_size,
                        StatusLine.formatBytes(copy.pdfFile.length())
                    ),
                    summary.substringBefore("\n")
                )
            }
        }
    }

    // ---- 2. Undo removes only the new copy --------------------------------

    @Test
    fun `undo on the compress result removes only the compressed copy and keeps the source`() {
        val (started, source) = compressFromDocumentMore(name = "Undo me")
        assertEquals(
            "the compress result is the shared tool result",
            ToolResultActivity::class.java.name,
            started.component?.className
        )
        val newId = started.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty().single()

        ActivityScenario.launch<ToolResultActivity>(started).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
            assertTrue(
                "Undo removes the compressed copy",
                pollUntil { repo.getDocument(newId) == null }
            )
        }

        val kept = requireNotNull(repo.getDocument(source.id)) {
            "DATA: Undo must never touch the document the person compressed"
        }
        assertEquals("the source still has both pages", 2, kept.pageFiles.size)
        assertArrayEquals("the source pages are untouched", source.pages[0], kept.pageFiles[0].readBytes())
        assertArrayEquals("the source PDF is untouched", source.pdf, kept.pdfFile.readBytes())
    }

    // ---- 3. an unreachable target is still said out loud -------------------

    @Test
    fun `compress still ends on the result when no estimate can be measured`() {
        // W2: level estimates are a convenience, never a gate.
        CompressFlow.estimateForTest = { _, _ ->
            throw IllegalStateException("that page could not be re-encoded")
        }
        try {
            val (started, source) = compressFromDocumentMore(name = "No estimate")
            assertEquals(
                "W2: a failed estimate must not block the compress",
                ToolResultActivity::class.java.name,
                started.component?.className
            )
            val ids = started.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
            assertEquals("still one new document", 1, ids.size)
            assertNotEquals("never the source", source.id, ids.single())
        } finally {
            CompressFlow.estimateForTest = null
        }
    }

    @Test
    fun `a compress that cannot reach the typed size says so on the shared result`() {
        val (started, source) = compressFromDocumentMore(name = "Tiny target", target = "1 KB")
        assertEquals(
            "the compress result is the shared tool result",
            ToolResultActivity::class.java.name,
            started.component?.className
        )
        assertEquals(
            "the size the person typed reaches the result",
            1024L,
            started.getLongExtra(EXTRA_TARGET, 0L)
        )
        val ids = started.getStringArrayListExtra(EXTRA_DOC_IDS).orEmpty()
        assertEquals("still only the new document", listOf(ids.single()), ids)
        assertNotEquals("never the source", source.id, ids.single())

        ActivityScenario.launch<ToolResultActivity>(started).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                val bytes = requireNotNull(repo.getDocument(ids.single())).pdfFile.length()
                assertTrue(
                    "B2-M2/B3: the honest target-miss line must appear: '$summary'",
                    summary.contains(
                        app.getString(
                            R.string.compress_result_over_limit,
                            StatusLine.formatBytes(bytes),
                            "1 KB"
                        )
                    )
                )
                assertNotNull(
                    "the same result still offers Also save in Documents",
                    activity.findViewById<View>(R.id.toolResultAlsoSave)
                )
                assertNotNull("and Undo", activity.findViewById<View>(R.id.toolResultUndo))
            }
        }
        assertNotNull("the source survives a target compress too", repo.getDocument(source.id))
    }

    // ---- the real phone path ----------------------------------------------

    /**
     * Document More -> Compress -> level sheet -> OK, exactly as the sweep did it. Returns the
     * intent the screen started (the result) and a snapshot of the source before the run.
     */
    private fun compressFromDocumentMore(
        name: String,
        target: String? = null
    ): Pair<Intent, Source> {        val sourceId = DocFixtures.saved(repo, app, pages = 2, name = name)
        val doc = requireNotNull(repo.getDocument(sourceId))
        val source = Source(
            id = sourceId,
            pdf = doc.pdfFile.readBytes(),
            pages = doc.pageFiles.map { it.readBytes() }
        )

        var started: Intent? = null
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, sourceId)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue(
                    "the document screen loads",
                    pollUntil {
                        activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                )
                activity.findViewById<View>(R.id.btnMore).performClick()
                idle()
                val more = requireNotNull(ShadowDialog.getLatestDialog()) { "the More sheet opens" }
                val compressRow = requireNotNull(more.findViewById<View>(R.id.rowMoreCompress)) {
                    "the Compress row is in the More sheet"
                }
                compressRow.performClick()
                assertTrue(
                    "the compress level sheet opens (estimates run off the main thread)",
                    pollUntil {
                        ShadowDialog.getLatestDialog()?.window?.decorView
                            ?.findViewById<View>(R.id.editCompressTarget) != null
                    }
                )
                val sheet = requireNotNull(ShadowDialog.getLatestDialog())
                val decor = requireNotNull(sheet.window).decorView
                if (target != null) {
                    // B3: a typed size runs only when the "Aim for a file size" aim is chosen.
                    decor.findViewById<View>(R.id.compressModeTarget).performClick()
                    idle()
                    decor.findViewById<EditText>(R.id.editCompressTarget).setText(target)
                    idle()
                }
                // The sheet's own primary ("OK"): a chosen level, or the typed exact size.
                decor.findViewById<View>(R.id.swSheetPrimary).performClick()
                started = pollStartedResult(activity)
            }
        }
        return requireNotNull(started) { "B2-M2: the compress result must open" } to source
    }

    private fun pollStartedResult(
        activity: android.app.Activity,
        timeoutMs: Long = 30_000
    ): Intent? {
        var started: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            started = shadowOf(activity).peekNextStartedActivity()
            if (started != null) break
            Thread.sleep(20)
        }
        idle()
        if (started == null) started = shadowOf(activity).peekNextStartedActivity()
        return started
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private companion object {
        // The result screen's wire extras (ToolResultActivity's own keys).
        const val EXTRA_TOOL_ID = "tool_id"
        const val EXTRA_DOC_IDS = "doc_ids"
        const val EXTRA_TARGET = "target_bytes"
    }
}
