package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.BatchActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ZipExporter
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * F5-FILENAMES (hunt H2-1 / H2-3 / H2-6 / H2-7): every file this app hands out is named after
 * its document. Each test drives the real entry point (Library multi-select Share, Batch
 * Share all, result Share / Save as, ZIP export, Documents copy, long-image and one-ZIP
 * exports) and was red on a2ea4fa6.
 *
 * Host tests: they prove the names the app produces. How a real share sheet / receiving app
 * shows them stays an on-device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportNamesWiringTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, true)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        // androidx FileProvider caches its path roots per authority in a static map.
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        DownloadSaver.resetForTests()
        media.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /** The file names a multi share hands to the receiver (EXTRA_STREAM), in order. */
    @Suppress("DEPRECATION")
    private fun sharedNames(chooser: Intent): List<String> {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
        // Every URI still resolves to real bytes.
        uris.forEach { uri ->
            val opened = app.contentResolver.openInputStream(uri)
            assertNotNull("the URI must resolve through the app's provider: $uri", opened)
            opened!!.close()
        }
        return uris.map { it.lastPathSegment!! }
    }

    // ---- H2-1: Library multi-select Share ----------------------------------

    @Test
    fun `library multi share hands out the documents own names, duplicates numbered`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        DocFixtures.saved(repo, app, pages = 1, name = "Electricity")
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                val cards = (0 until list.childCount).map { list.getChildAt(it) as DocCard }
                assertEquals("three documents", 3, cards.size)
                cards[0].performLongClick()
                idle()
                cards[1].performClick()
                idle()
                cards[2].performClick()
                idle()
                while (shadowOf(activity).nextStartedActivity != null) Unit
                activity.findViewById<View>(R.id.swSelShare).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share opens the sharesheet", chooser)
                assertEquals(
                    listOf("Electricity.pdf", "Rent receipt (2).pdf", "Rent receipt.pdf"),
                    sharedNames(chooser).sorted()
                )
            }
        }
    }

    // ---- H2-1: Batch "Share all" ------------------------------------------

    @Test
    fun `batch share all hands out named PDFs, not scan pdf`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Gas bill"),
            DocFixtures.saved(repo, app, pages = 1, name = "Water bill")
        )
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue("the action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionCompress) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionCompress).performClick()
                idle()
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("two copies are published", waitUntil {
                repo.listDocuments().count { it.name.endsWith(" (2)") } == 2
            })
            assertTrue("the summary opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchSummary) != null
            })
            idle()
            scenario.onActivity { activity ->
                while (shadowOf(activity).nextStartedActivity != null) Unit
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share all opens the sharesheet", chooser)
                assertEquals(
                    listOf("Gas bill (2).pdf", "Water bill (2).pdf"),
                    sharedNames(chooser).sorted()
                )
            }
        }
    }

    // ---- H2-1: result screen with several documents ------------------------

    @Test
    fun `result share of several documents hands out their names`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Part one"),
            DocFixtures.saved(repo, app, pages = 1, name = "Part two")
        )
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = ids)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultShare).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share opens the sharesheet", chooser)
                assertEquals(listOf("Part one.pdf", "Part two.pdf"), sharedNames(chooser))
            }
        }
    }

    // ---- H2-1: result screen "Save as" with several documents (the ZIP) -----

    private fun zipEntryNamesFromSaveAs(docNames: List<String>): List<String> {
        val ids = docNames.map { DocFixtures.saved(repo, app, pages = 1, name = it) }
        val target = Uri.parse("content://com.scanwhirl.test.documents/chosen.zip")
        val written = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(target, written)
        shadowOf(app.contentResolver).registerInputStreamSupplier(target) {
            ByteArrayInputStream(written.toByteArray())
        }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = ids)
        ).use { scenario ->
            var picker: Intent? = null
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                picker = shadowOf(activity).nextStartedActivityForResult?.intent
            }
            assertNotNull("the system picker opens", picker)
            scenario.onActivity { activity ->
                shadowOf(activity).receiveResult(picker!!, android.app.Activity.RESULT_OK, Intent().setData(target))
            }
            assertTrue("the ZIP bytes arrive", waitUntil { written.size() > 0 })
        }
        val zip = File(app.cacheDir, "documents/f5names_saved.zip").apply {
            parentFile?.mkdirs()
            writeBytes(written.toByteArray())
        }
        return ZipFile(zip).use { archive -> archive.entries().toList().map { it.name } }
    }

    @Test
    fun `result zip of several documents names its entries after the documents`() {
        assertEquals(
            listOf("Alpha.pdf", "Beta.pdf"),
            zipEntryNamesFromSaveAs(listOf("Alpha", "Beta"))
        )
    }

    @Test
    fun `result zip numbers two documents with the same name`() {
        assertEquals(
            listOf("Alpha.pdf", "Alpha (2).pdf"),
            zipEntryNamesFromSaveAs(listOf("Alpha", "Alpha"))
        )
    }

    // ---- H2-3: Documents copy keeps the extension of a long name ------------

    private fun savedName(requested: String, mime: String = "application/pdf"): String {
        val src = File(app.cacheDir, "documents/f5names_src.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { 7 })
        }
        return DownloadSaver.saveFile(app, src, requested, mime).name
    }

    @Test
    fun `a 58 character name keeps its pdf extension in Documents`() {
        val base = "a".repeat(58)
        assertEquals("$base.pdf", savedName("$base.pdf"))
    }

    @Test
    fun `a long Hindi name keeps its pdf extension and is cut on a letter boundary`() {
        // 70 consonant+matra pairs = 140 code points; only the base may be shortened.
        val hindi = "कि".repeat(70)
        val name = savedName("$hindi.pdf")
        assertTrue("extension survives: $name", name.endsWith(".pdf"))
        val base = name.removeSuffix(".pdf")
        assertTrue("base is at most 60 code points", base.codePointCount(0, base.length) <= 60)
        assertTrue("a kept prefix of the original", hindi.startsWith(base))
        // Whole syllables only: the next character in the original is a letter, not a matra.
        assertTrue("cut on a syllable boundary", hindi[base.length] == 'क')
    }

    @Test
    fun `a jpg zip name keeps both extensions in Documents`() {
        val base = "b".repeat(70)
        val name = savedName("$base.jpg.zip", "application/zip")
        assertTrue("both extensions survive: $name", name.endsWith(".jpg.zip"))
    }

    // ---- H2-7: a dotted document name is not an extension ------------------

    @Test
    fun `zip export keeps a dotted date in the document name`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "12.05.2025 Rent receipt")
        val out = File(app.cacheDir, "documents/f5names_dotted.zip").apply { parentFile?.mkdirs() }
        ZipExporter.export(listOf(requireNotNull(repo.getDocument(id))), out)
        val names = ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("12.05.2025 Rent receipt.pdf"), names)
    }

    @Test
    fun `long image export keeps a dotted date in the document name`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "12.05.2025 Rent receipt")
        val dir = File(app.cacheDir, "documents/f5names_long").apply { mkdirs() }
        val files = runBlocking { repo.exportLongImage(id, dir) }
        assertEquals(listOf("12.05.2025 Rent receipt - long.jpg"), files.map { it.name })
    }

    // ---- H2-6: one combined image ZIP, two documents with the same name -----

    @Test
    fun `one zip of images gives two same-named documents their own folders`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "Invoice"),
            DocFixtures.saved(repo, app, pages = 1, name = "Invoice")
        )
        val results = runBlocking {
            repo.runBatch(
                ids,
                ScanRepository.BatchAction.PdfToImages(ScanRepository.ImageExportFormat.JPG, oneZip = true)
            )
        }
        assertEquals("both documents succeed: ${results.map { it.reason }}", 2, results.count { it.ok })
        val zip = results.first().files.single()
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue("first folder: $names", names.any { it.startsWith("Invoice/") })
        assertTrue("second folder: $names", names.any { it.startsWith("Invoice (2)/") })
    }
}
