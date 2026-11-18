package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.PdfAFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * DATA-LOSS P0 (T10-FIX-PDFA-UNDO): "Save as PDF/A" makes a *copy* of an existing scan. The
 * result screen therefore describes the generated PDF/A file and Undo removes only that file —
 * it must never permanently purge the scanned source document.
 *
 * Before the fix [PdfAFlow.create] passed the SOURCE document id as `docIds` together with the
 * generated file, so Undo called `repo.deleteDocument(sourceId)` and
 * [ScanRepository.deleteDocument] permanently purges (it does not use Trash). Open and Share also
 * acted on the source instead of the PDF/A file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfAUndoKeepsSourceTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        // androidx FileProvider caches its path roots per authority in a static map; under
        // Robolectric every test class gets a fresh cacheDir (same guard as ToolsUiTest).
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    /** The extras the fixed PdfAFlow builds: the generated file only, no source document id. */
    private fun resultIntent(pdfa: File): Intent = PdfAFlow.resultIntent(app, pdfa)

    /**
     * The pre-fix extras that caused the P0: the SOURCE document id plus the generated file.
     * Kept to pin the defensive guard in ToolResultActivity.undo().
     */
    private fun legacyMisuseIntent(sourceId: String, pdfa: File): Intent =
        ToolResultActivity.intent(
            app,
            toolId = "pdfa",
            docIds = listOf(sourceId),
            files = listOf(pdfa),
            mime = "application/pdf"
        )

    @Test
    fun `undo on a PDF-A result removes the generated file and keeps the scanned source`() {
        val sourceId = DocFixtures.saved(repo, app, pages = 1, name = "Archive me")
        val pdfa = runBlocking { PdfAFlow.exportForResult(app, repo, sourceId) }
        assertTrue("the PDF/A copy must exist before Undo", pdfa.isFile)
        assertTrue("the PDF/A copy must be named after the source", pdfa.name.endsWith("-pdfa.pdf"))

        ActivityScenario.launch<ToolResultActivity>(resultIntent(pdfa)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "an export-only result has no library document to show",
                    View.GONE,
                    activity.findViewById<View>(R.id.toolResultLibrary).visibility
                )
                activity.findViewById<View>(R.id.toolResultUndo).performClick()
            }
            assertTrue("Undo must delete the generated PDF/A file", waitUntil { !pdfa.exists() })
        }

        assertNotNull(
            "P0: Undo must never permanently delete the scanned source document",
            repo.getDocument(sourceId)
        )
    }

    @Test
    fun `undo never purges a document even when a caller passes it as a result doc id`() {
        val sourceId = DocFixtures.saved(repo, app, pages = 1, name = "Archive me")
        val pdfa = runBlocking { PdfAFlow.exportForResult(app, repo, sourceId) }

        ActivityScenario.launch<ToolResultActivity>(legacyMisuseIntent(sourceId, pdfa)).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
            assertTrue("Undo must still remove the generated file", waitUntil { !pdfa.exists() })
        }

        assertNotNull(
            "P0 guard: undo must not purge a document that a mixed result names as its input",
            repo.getDocument(sourceId)
        )
    }

    @Test
    fun `a PDF-A result opens the generated file, not the source document`() {
        val sourceId = DocFixtures.saved(repo, app, pages = 1, name = "Archive me")
        val pdfa = runBlocking { PdfAFlow.exportForResult(app, repo, sourceId) }

        ActivityScenario.launch<ToolResultActivity>(resultIntent(pdfa)).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultOpen).performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                assertNotNull("Open must start something", started)
                assertEquals("Open must view the PDF/A file", Intent.ACTION_VIEW, started!!.action)
                assertEquals(
                    "Open must hand over the generated PDF/A file, not the source",
                    pdfa.name,
                    started.data?.lastPathSegment
                )
            }
        }
        assertNotNull("Open must not touch the source", repo.getDocument(sourceId))
    }

    @Test
    fun `a PDF-A result shares the generated file, not the source document`() {
        val sourceId = DocFixtures.saved(repo, app, pages = 1, name = "Archive me")
        val pdfa = runBlocking { PdfAFlow.exportForResult(app, repo, sourceId) }

        ActivityScenario.launch<ToolResultActivity>(resultIntent(pdfa)).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultShare).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share must open the sharesheet", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
                val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                assertNotNull("the sharesheet must carry the share intent", target)
                val uri = target!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                assertNotNull("Share must attach one file", uri)
                assertEquals(
                    "Share must attach the generated PDF/A file, not the source",
                    pdfa.name,
                    uri!!.lastPathSegment
                )
            }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }
}
