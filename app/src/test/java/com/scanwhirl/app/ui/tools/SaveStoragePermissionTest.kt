package com.scanwhirl.app.ui.tools

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.AutoExport
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.time.Duration

/**
 * F7-SAVE, hunt H2-4. Android 7-9 (minSdk 24) writes into the public Documents folder through a
 * plain file, which needs the runtime WRITE_EXTERNAL_STORAGE grant. The manifest declares it with
 * maxSdkVersion="28", but three save paths never asked for it: the tool result's "Also save in
 * Documents", Batch "Export as PDF", and the automatic copy after a scan. The first two ended in a
 * raw EACCES line, the third in silence: the copy the person had switched on never appeared and
 * nothing said so.
 *
 * These tests pin the promise on API 28 and API 35:
 *  - on API 28 a Documents save asks first, writes nothing before the answer and says why;
 *  - on API 28 with the grant it saves exactly as before;
 *  - on API 30+ no storage prompt exists at all and the save needs none;
 *  - the automatic copy after a scan cannot ask (it has no screen), so it is skipped and the
 *    person is told plainly that it was not copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SaveStoragePermissionTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Suppress("DEPRECATION")
    private val documentsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        DownloadSaver.SUBDIR
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        documentsDir.deleteRecursively()
        documentsDir.mkdirs()
        DownloadSaver.resetForTests()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        media.close()
    }

    // ---- API 28: ask before writing ---------------------------------------

    @Test
    fun `a tool result asks for storage before copying into documents on android 9`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultAlsoSave).performClick() }
            idleFor(800)
            assertTrue(
                "H2-4: nothing is written before access is granted: ${legacyFiles().map { it.name }}",
                legacyFiles().isEmpty()
            )
            assertEquals(
                "H2-4: the screen says why Android is asking",
                app.getString(R.string.f7save_perm_why_documents),
                errorTextOf(scenario, R.id.toolResultError)
            )
            assertFalse(
                "the button is not left in a failed state with raw system text",
                errorTextOf(scenario, R.id.toolResultError).contains("EACCES")
            )
        }
        assertTrue("the library document is untouched", repo.getDocument(id) != null)
    }

    @Test
    fun `with the grant a tool result copies into documents on android 9`() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val source = requireNotNull(repo.getDocument(id)).pdfFile.readBytes()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultAlsoSave).performClick() }
            assertTrue("the copy is written", pollUntil { legacyFiles().isNotEmpty() })
            val copy = legacyFiles().single()
            assertEquals("the copy keeps the document's name", "Rent receipt.pdf", copy.name)
            assertArrayEquals("the copy holds the document's own PDF bytes", source, copy.readBytes())
            assertTrue(
                "no rationale is left on a save that worked: ${errorTextOf(scenario, R.id.toolResultError)}",
                errorTextOf(scenario, R.id.toolResultError).isBlank()
            )
        }
    }

    @Test
    fun `batch export asks for storage before writing pdfs on android 9`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Gas bill"),
            DocFixtures.saved(repo, app, pages = 1, name = "Water bill")
        )
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue("the action sheet opens", pollUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionExport) != null
            })
            scenario.onActivity {
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionExport).performClick()
                idle()
            }
            idleFor(1_000)
            assertTrue(
                "H2-4: no PDF is written before access is granted: ${legacyFiles().map { it.name }}",
                legacyFiles().isEmpty()
            )
            assertEquals(
                "H2-4: the screen says why Android is asking",
                app.getString(R.string.f7save_perm_why_documents),
                errorTextOf(scenario, R.id.batchError)
            )
        }
    }

    @Test
    fun `the automatic documents copy is skipped and said out loud on android 9`() {
        Prefs.setAutoCopyToDocuments(app, true)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val doc = requireNotNull(repo.getDocument(id))
        val message = AutoExport.afterSave(app, repo, doc)
        assertEquals(
            "H2-4: a copy that cannot be made is said out loud, never silent",
            app.getString(R.string.f7save_autocopy_no_permission),
            message
        )
        assertTrue(
            "H2-4: and nothing was half written: ${legacyFiles().map { it.name }}",
            legacyFiles().isEmpty()
        )
        assertTrue("the document itself is untouched", doc.pdfFile.exists())
    }

    // ---- API 30+: no storage permission exists ----------------------------

    @Test
    @Config(sdk = [35])
    fun `android 10 and newer copies into documents without asking`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultAlsoSave).performClick() }
            assertTrue("the copy is published", pollUntil { media.scanwhirlPdfs().size == 1 })
            assertTrue(
                "no storage rationale on Android 10+: ${errorTextOf(scenario, R.id.toolResultError)}",
                errorTextOf(scenario, R.id.toolResultError).isBlank()
            )
        }
    }

    @Test
    @Config(sdk = [35])
    fun `the automatic documents copy works without any storage permission`() {
        Prefs.setAutoCopyToDocuments(app, true)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val doc = requireNotNull(repo.getDocument(id))
        val message = AutoExport.afterSave(app, repo, doc)
        assertEquals(
            "the copy is reported by name",
            app.getString(R.string.pdf_saved_docs, "Rent receipt.pdf"),
            message
        )
        assertEquals("the copy is published", 1, media.scanwhirlPdfs().size)
    }

    // ---- the words --------------------------------------------------------

    @Test
    fun `the storage lines carry no placeholder and no raw system words`() {
        for (res in STORAGE_STRINGS) {
            val text = app.getString(res)
            assertFalse("no placeholder in $text", text.contains("%"))
            assertFalse("no exception class in $text", text.contains("Exception"))
            assertFalse("no file-system jargon in $text", text.contains("EACCES"))
        }
    }

    @Test
    @Config(qualifiers = "hi")
    fun `the storage lines exist in Hindi and are really translated`() {
        for (res in STORAGE_STRINGS) {
            val text = app.getString(res)
            assertTrue(
                "a Hindi line must be Devanagari: $text",
                text.any { it in '\u0900'..'\u097F' }
            )
            assertFalse("no English sentence left: $text", text.contains("Not saved"))
            assertFalse("no English sentence left: $text", text.contains("Allow access"))
        }
    }

    // ---- helpers ----------------------------------------------------------

    private val STORAGE_STRINGS = listOf(
        R.string.f7save_perm_why_documents,
        R.string.f7save_perm_denied_documents,
        R.string.f7save_perm_denied_batch,
        R.string.f7save_autocopy_no_permission,
        R.string.f7save_picker_write_failed,
        R.string.f7save_picker_write_failed_left
    )

    private fun legacyFiles(): List<File> =
        documentsDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

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

    private fun errorTextOf(activity: android.app.Activity, id: Int): String =
        activity.findViewById<TextView>(id)?.text?.toString().orEmpty()

    private fun <A : android.app.Activity> errorTextOf(scenario: ActivityScenario<A>, id: Int): String {
        var text = ""
        scenario.onActivity { activity -> text = errorTextOf(activity, id) }
        return text
    }
}
