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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * H3-4 (bug hunt 2026-11-20): the Tools tab runs every copy tool on the document's SAVED pages,
 * and its picker has no draft bar, so an unsaved crop/rotation/page delete was exported silently
 * — the person believed they compressed or shared what they had just edited. The document screen
 * already asks ("Finish editing first / Use saved version / Cancel"); the Tools tab now asks the
 * same question through the same dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolCopyDraftTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        ScanRepository.readProbe = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    /** An unsaved page edit, exactly what the viewer's crop/rotate writes. */
    private fun rotateDraft(id: String) {
        runBlocking { repo.draftRotate(id, 0) }
        assertNotNull("fixture needs a draft", runBlocking { repo.draftOf(id) })
    }

    @Test
    fun `Compress in the Tools tab asks before it runs on the pre-edit pages`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Edited receipt")
        rotateDraft(id)

        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress"))
            .use { scenario ->
                assertTrue(
                    "the setup screen is ready",
                    await {
                        var shown = false
                        scenario.onActivity { a ->
                            shown = a.findViewById<View>(R.id.toolSetupRun)?.isShown == true
                        }
                        shown
                    }
                )
                scenario.onActivity { a -> a.findViewById<View>(R.id.toolSetupRun).performClick() }
                assertTrue(
                    "the draft question must appear",
                    await { ShadowDialog.getLatestDialog()?.isShowing == true }
                )
                val dialog = ShadowDialog.getLatestDialog()
                val texts = buildString {
                    dialog.window?.decorView?.let { decor -> collectText(decor, this) }
                }
                assertTrue(
                    "the question is the document screen's own: $texts",
                    texts.contains(app.getString(R.string.w1_draft_finish_first)) &&
                        texts.contains(app.getString(R.string.w1_use_saved_version))
                )

                // Nothing was published while the question is open, however long we wait.
                repeat(20) {
                    idle()
                    Thread.sleep(10)
                }
                assertEquals(
                    "no copy may be built from the pre-edit pages",
                    listOf(id),
                    repo.listDocuments().map { it.id }
                )
            }
    }

    @Test
    fun `the same run proceeds when no draft is open`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Plain receipt")

        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress"))
            .use { scenario ->
                assertTrue(
                    "the setup screen is ready",
                    await {
                        var shown = false
                        scenario.onActivity { a ->
                            shown = a.findViewById<View>(R.id.toolSetupRun)?.isShown == true
                        }
                        shown
                    }
                )
                scenario.onActivity { a -> a.findViewById<View>(R.id.toolSetupRun).performClick() }
                assertTrue(
                    "a document without a draft runs the tool as before",
                    await { repo.listDocuments().size == 2 }
                )
                assertTrue("the source is untouched", repo.getDocument(id) != null)
                val latest = ShadowDialog.getLatestDialog()
                val texts = buildString {
                    latest?.window?.decorView?.let { decor -> collectText(decor, this) }
                }
                assertFalse(
                    "no draft question for a document without a draft: $texts",
                    texts.contains(app.getString(R.string.w1_draft_finish_first))
                )
            }
    }

    private fun collectText(view: View, out: StringBuilder) {
        if (view is android.widget.TextView) out.append(view.text).append('\n')
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectText(view.getChildAt(i), out)
        }
    }
}
