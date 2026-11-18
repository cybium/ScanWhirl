package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/**
 * T05-RESULT-SLICE — "Prepare a file -> the result tells the truth -> keep a copy".
 *
 * The result screen used to have only the system Save-as picker ("Save"). A person who prepares
 * a file on this phone therefore had no one-tap way to keep a copy in Documents, and a compress
 * that could not reach the size asked for said nothing at all.
 *
 * These tests pin the promise:
 *  - every non-ID-card result offers "Also save in Documents", for a library document and for an
 *    exported file (Word/Excel/PowerPoint/ZIP/image);
 *  - the save reuses the M06 export transaction: one verified copy, never an overwrite, an
 *    identical second save is reported as already saved and writes nothing;
 *  - the confirmation's Undo removes only the copy that save created and leaves the library
 *    document (and the document's own PDF) alone;
 *  - a compress result says "Couldn't reach …" only when the produced size is still bigger than
 *    the size the person asked for, and never for another tool;
 *  - the ID card result keeps exactly Keep and Discard.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ResultAlsoSaveTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        // Same guard as ToolsUiTest: FileProvider caches path roots per authority across classes.
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        media.close()
    }

    // ---- 1. the action is offered, in the agreed words ---------------------

    @Test
    fun `a document result offers Also save in Documents`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<TextView>(R.id.toolResultAlsoSave)
                assertNotNull("a document result must offer Also save", button)
                assertEquals(
                    "the agreed English wording",
                    "Also save in Documents",
                    button.text.toString()
                )
                assertEquals(
                    "the wording comes from the string resource",
                    activity.getString(R.string.tools_result_also_save),
                    button.text.toString()
                )
                assertTrue("it is a real, enabled action", button.isEnabled && button.isShown)
            }
        }
    }

    @Test
    fun `a file result offers Also save in Documents too`() {
        val docx = docxResult()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "to_word", files = listOf(docx), mime = DOCX_MIME)
        ).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<TextView>(R.id.toolResultAlsoSave)
                assertNotNull("an exported file must offer Also save", button)
                assertEquals(
                    "Also save in Documents",
                    button.text.toString()
                )
            }
        }
    }

    // ---- 2. exactly one verified copy -------------------------------------

    @Test
    fun `Also save writes exactly one verified copy of the document PDF`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        val source = pdf.readBytes()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultAlsoSave).performClick()
                assertTrue(
                    "the save must publish exactly one Documents row",
                    pollUntil { media.scanwhirlPdfs().size == 1 }
                )
                val row = media.scanwhirlPdfs().single()
                assertEquals("the copy keeps the document's name", "Rent receipt.pdf", row.name)
                assertArrayEquals("the copy holds the document's own PDF bytes", source, row.bytes())
                assertEquals("written once, never re-opened", 1, row.writes)
                assertTrue("no hidden staging rows are left behind", media.pendingRows().isEmpty())
                assertTrue(
                    "the confirmation names the file and the folder: ${snackMessage(activity)}",
                    pollUntil {
                        snackMessage(activity) ==
                            app.getString(R.string.result_saved_documents, "Rent receipt.pdf")
                    }
                )
            }
        }
    }

    // ---- 3. Undo removes only its own copy --------------------------------

    @Test
    fun `Undo removes only the new copy and keeps the library document`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        // A file the person put in Documents themselves: Undo must never touch it.
        val other = media.addRow(
            "Earlier.pdf", "earlier".toByteArray(), owner = null,
            relPath = android.os.Environment.DIRECTORY_DOCUMENTS
        )
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultAlsoSave).performClick() }
            assertTrue("one copy is saved", pollUntil { media.scanwhirlPdfs().size == 1 })
            val copy = media.scanwhirlPdfs().single()

            var undo: View? = null
            assertTrue("the confirmation offers Undo", pollUntil {
                scenario.onActivity { activity -> undo = activity.findViewById(R.id.snackUndo) }
                undo != null
            })
            scenario.onActivity { undo!!.performClick() }
            assertTrue("Undo removes the copy this save created", pollUntil { media.rows[copy.id] == null })
            assertNotNull("Undo never touches a file the person already had", media.rows[other.id])
            assertTrue(
                "Undo says the copy was removed",
                pollUntil {
                    ShadowToast.getTextOfLatestToast().orEmpty()
                        .contains(app.getString(R.string.fixresult_export_removed_docs, copy.name))
                }
            )
        }
        assertNotNull("the library document survives", repo.getDocument(id))
        assertFalse(
            "the removed copy is no longer recorded as an export of the document",
            repo.exportsOf(id).any { it.toString().contains("media/external") }
        )
    }

    // ---- 4. saving twice makes no duplicate -------------------------------

    @Test
    fun `saving the same document twice reports already saved and keeps one copy`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultAlsoSave).performClick() }
            assertTrue("the first save publishes a row", pollUntil { media.scanwhirlPdfs().size == 1 })
            val writes = media.totalWrites()

            // Close the first confirmation so the second one can be read (Material queues them).
            var open: View? = null
            assertTrue("the first confirmation appears", pollUntil {
                scenario.onActivity { activity -> open = activity.findViewById(R.id.snackOpen) }
                open != null
            })
            scenario.onActivity { activity ->
                open!!.performClick()
                idleFor(1_500)
                drainStarted(activity)
                activity.findViewById<View>(R.id.toolResultAlsoSave).performClick()
            }
            assertTrue(
                "the second save must say it was already saved: ${snackMessageOf(scenario)}",
                pollUntil {
                    snackMessageOf(scenario) ==
                        app.getString(R.string.export_already_saved, "Rent receipt.pdf")
                }
            )
            assertEquals("still exactly one copy", 1, media.scanwhirlPdfs().size)
            assertEquals("nothing was written again", writes, media.totalWrites())
        }
    }

    // ---- 5. an exported file saves its own bytes --------------------------

    @Test
    fun `a Word result saves its own bytes`() {
        val docx = docxResult()
        val bytes = docx.readBytes()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "to_word", files = listOf(docx), mime = DOCX_MIME)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultAlsoSave).performClick()
                assertTrue("the Word file is saved", pollUntil { media.scanwhirlPdfs().size == 1 })
                val row = media.scanwhirlPdfs().single()
                assertEquals("the exported file keeps its name", docx.name, row.name)
                assertArrayEquals("the copy is the Word file itself", bytes, row.bytes())
            }
        }
    }

    // ---- 6-8. the compress result tells the truth -------------------------

    @Test
    fun `a compress result reports the size it could not reach`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Compressed receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = 1L)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertTrue(
                    "the honest target-miss line is shown: $summary",
                    summary.contains(app.getString(R.string.compress_target_missed, "1 B"))
                )
            }
        }
    }

    @Test
    fun `a compress result that met the size says nothing about a miss`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Compressed receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = 100_000_000L)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertFalse("no miss when the size was met: $summary", summary.contains(missSuffix()))
            }
        }
    }

    @Test
    fun `another tool never claims a target miss`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Split receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id), targetBytes = 1L)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertFalse("only compress has a target: $summary", summary.contains(missSuffix()))
            }
        }
    }

    @Test
    fun `a compress run hands the size the person asked for to the result`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Compress me")
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                // Pick the seeded document, type an unreachable size, run.
                val rows = rows(activity)
                val row = rows.firstOrNull { r ->
                    walk(r).any { (it as? TextView)?.text?.toString() == "Compress me" }
                }
                assertNotNull("the seeded document is listed: ${rows.size} rows", row)
                row!!.performClick()
                idle()
                activity.findViewById<EditText>(R.id.toolSetupTarget).setText("1 KB")
                idle()
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
            }
            val started = waitForStartedResult(scenario)
            assertNotNull("compress opens its result", started)
            ActivityScenario.launch<ToolResultActivity>(started!!).use { result ->
                result.onActivity { activity ->
                    val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                    assertTrue(
                        "the size the person typed reaches the result and is answered: $summary",
                        summary.contains(app.getString(R.string.compress_target_missed, "1 KB"))
                    )
                }
            }
        }
    }

    // ---- 9. the ID card keeps Keep and Discard only -----------------------

    @Test
    fun `the ID card result keeps Keep and Discard and no Also save`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "ID card")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "id_card", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(
                    "an already-saved card has nothing to save again",
                    activity.findViewById<View>(R.id.toolResultAlsoSave)
                )
                val labels = walk(activity.window!!.decorView)
                    .filterIsInstance<TextView>().map { it.text.toString() }
                assertFalse("no Also save label: $labels", labels.contains("Also save in Documents"))
                assertTrue(
                    "Keep is still offered",
                    walk(activity.window!!.decorView).any { it.tag == ToolResultActivity.TAG_KEEP }
                )
                assertTrue(
                    "Discard is still offered",
                    walk(activity.window!!.decorView).any { it.tag == ToolResultActivity.TAG_DISCARD }
                )
            }
        }
    }

    // ---- 10. the words, in both languages ---------------------------------

    @Test
    fun `Save as and Also save have the agreed English words`() {
        assertEquals("Save as…", app.getString(R.string.tools_result_save))
        assertEquals("Also save in Documents", app.getString(R.string.tools_result_also_save))
        for (res in listOf(R.string.tools_result_save, R.string.tools_result_also_save)) {
            assertFalse(
                "a result action takes no arguments, so no placeholder may slip in",
                app.getString(res).contains("%")
            )
        }
    }

    @Test
    @Config(qualifiers = "hi-w360dp-h800dp-xhdpi")
    fun `Save as and Also save have the agreed Hindi words`() {
        assertEquals("इस रूप में सहेजें…", app.getString(R.string.tools_result_save))
        assertEquals("Documents में भी सहेजें", app.getString(R.string.tools_result_also_save))
        for (res in listOf(R.string.tools_result_save, R.string.tools_result_also_save)) {
            assertFalse(
                "the Hindi action takes the same (empty) argument list as English",
                app.getString(res).contains("%")
            )
        }
    }

    // ---- helpers ---------------------------------------------------------

    private val DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** A small Word result file. Its bytes matter; it is never opened as a real package. */
    private fun docxResult(): File =
        File(app.cacheDir, "documents/Offer letter.docx").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(512) { (it % 31 + 3).toByte() })
        }

    /**
     * The tail of the existing target-miss sentence, so a test can look for the line without
     * depending on the size formatting of the same sentence.
     */
    private fun missSuffix(): String =
        app.getString(R.string.compress_target_missed, "SENTINEL").substringAfter("SENTINEL")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun snackMessage(activity: android.app.Activity): String? =
        activity.findViewById<TextView>(R.id.snackMessage)?.text?.toString()

    private fun snackMessageOf(scenario: ActivityScenario<ToolResultActivity>): String? {
        var message: String? = null
        scenario.onActivity { message = snackMessage(it) }
        return message
    }

    private fun drainStarted(activity: android.app.Activity) {
        val shadow = shadowOf(activity)
        while (shadow.peekNextStartedActivity() != null) shadow.getNextStartedActivity()
    }

    private fun waitForStartedResult(
        scenario: ActivityScenario<ToolSetupActivity>
    ): android.content.Intent? {
        var started: android.content.Intent? = null
        pollUntil {
            scenario.onActivity { activity ->
                started = shadowOf(activity).peekNextStartedActivity()
                    ?.takeIf { it.component?.className == ToolResultActivity::class.java.name }
            }
            started != null
        }
        return started
    }

    private fun rows(activity: ToolSetupActivity): List<View> {
        val host = activity.findViewById<ViewGroup>(R.id.toolSetupSource) ?: return emptyList()
        return (0 until host.childCount).map { host.getChildAt(it) }
            .filter { it.id == R.id.toolSetupDocRow }
    }

    private fun walk(view: View): List<View> {
        val out = mutableListOf<View>()
        fun visit(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(view)
        return out
    }
}
