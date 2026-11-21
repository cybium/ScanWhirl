package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.util.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageImportPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before @After fun resetProviderRoots() {
        // AndroidX's static cache survives Robolectric's per-test dataDir change.
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
        ShadowToast.reset()
    }

    /** Wrong extension AND MIME, backed by provider-owned files whose bytes we retain. */
    class MislabelledProvider : ContentProvider() {
        val files = mutableMapOf<Uri, File>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/jpeg"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r") { "Import must never modify provider input" }
            return ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }

    private fun providerUris(bytes: List<ByteArray>): List<Uri> {
        val provider = MislabelledProvider()
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "wp03" })
        ShadowContentResolver.registerProviderInternal("wp03", provider)
        return bytes.mapIndexed { i, data ->
            val file = File(tmp.root, "provider-$i.jpg").apply { writeBytes(data) }
            Uri.parse("content://wp03/$i.jpg").also { provider.files[it] = file }
        }
    }

    @Suppress("DEPRECATION")
    private fun sources(count: Int) = (0 until count).map { i ->
        val format = listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)[i % 3]
        ImportFixtures.image(format, width = 120 + i, transparent = i % 3 == 0)
    }

    private fun assertOriginals(bytes: List<ByteArray>) {
        bytes.forEachIndexed { i, data -> assertArrayEquals(data, File(tmp.root, "provider-$i.jpg").readBytes()) }
    }

    private fun assertSaved(doc: ScanDocument, count: Int, label: String) {
        val reopened = ScanRepository(app).getDocument(doc.id)!!
        assertEquals(count, reopened.pageCount)
        reopened.pageFiles.forEachIndexed { i, file ->
            assertEquals("page_%03d.jpg".format(java.util.Locale.ROOT, i), file.name)
            val bitmap = ImportFixtures.assertJpeg(file.readBytes())
            try {
                assertEquals(120 + i, bitmap.width)
                assertEquals(80, bitmap.height)
                ImportFixtures.assertColor(Color.RED, bitmap.getPixel(15, 15))
                ImportFixtures.assertColor(Color.WHITE, bitmap.getPixel(bitmap.width - 1, 79))
            } finally { bitmap.recycle() }
        }
        // Verify each DCT stream, in order, against the saved page. Independent
        // PDF parser/rendering is run on the opt-in artifacts below; Robolectric
        // 4.14/API 35 PdfRenderer hits FileDescriptor.getOwnerId$ on this host JDK.
        val pdfBytes = reopened.pdfFile.readBytes()
        val pdfText = String(pdfBytes, Charsets.ISO_8859_1)
        assertTrue(pdfText.startsWith("%PDF-"))
        assertTrue(pdfText.endsWith("%%EOF\n"))
        assertTrue("/Count $count" in pdfText)
        val streams = Regex("/Subtype /Image[^\\n]+/Filter /DCTDecode /Length (\\d+) >>\\nstream\\n").findAll(pdfText).toList()
        assertEquals(count, streams.size)
        streams.forEachIndexed { i, match ->
            val start = match.range.last + 1
            val bytes = pdfBytes.copyOfRange(start, start + match.groupValues[1].toInt())
            assertArrayEquals(reopened.pageFiles[i].readBytes(), bytes)
            ImportFixtures.assertJpeg(bytes).recycle()
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            ShareUtils.share(activity, reopened)
            val chooser = com.scanwhirl.app.util.awaitShareChooser(activity)
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/pdf", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertArrayEquals(reopened.pdfFile.readBytes(), app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            ShareUtils.shareFile(activity, reopened.pageFiles.first(), "image/jpeg")
            @Suppress("DEPRECATION") val pageSend = com.scanwhirl.app.util.awaitShareChooser(activity).getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("image/jpeg", pageSend.type)
            @Suppress("DEPRECATION") val pageUri = pageSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            ImportFixtures.assertJpeg(app.contentResolver.openInputStream(pageUri)!!.use { it.readBytes() }).recycle()
        } finally { controller.pause().stop().destroy() }
        // Opt-in synthetic artifacts for independent off-device PDF parsing/rendering.
        System.getenv("WP03_EVIDENCE_DIR")?.let { path ->
            val dir = File(path).apply { mkdirs() }
            reopened.pdfFile.copyTo(File(dir, "$label-$count.pdf"), overwrite = true)
        }
    }

    private fun direct(count: Int) = runBlocking {
        val bytes = sources(count)
        val uris = providerUris(bytes)
        assertEquals("image/jpeg", app.contentResolver.getType(uris[0]))
        val saved = repo.saveScan(uris, null, "Direct $count")
        assertSaved(saved, count, "direct")
        assertOriginals(bytes)
    }

    @Test fun `direct 1 page save reopen share and PDF streams`() = direct(1)
    @Test fun `direct 5 pages save reopen share and PDF streams`() = direct(5)
    @Test fun `direct 30 pages save reopen share and PDF streams`() = direct(30)

    private fun staged(count: Int) {
        Prefs.setAutoCopyToDocuments(app, false)
        val bytes = sources(count)
        val uris = providerUris(bytes)
        val initial = maxOf(1, count / 2)
        var reviewIntent: Intent? = null
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> ScanLauncher(activity, repo, activity) {}.stageAndReview(uris.take(initial)) }
            await {
                scenario.onActivity { reviewIntent = shadowOf(it).nextStartedActivity }
                reviewIntent != null
            }
        }
        val staging = File(reviewIntent!!.getStringExtra("staging_dir")!!)
        assertEquals(initial, staging.listFiles { f -> f.extension == "jpg" }!!.size)
        staging.listFiles { f -> f.extension == "jpg" }!!.forEach { ImportFixtures.assertJpeg(it.readBytes()).recycle() }
        var viewerIntent: Intent? = null
        ActivityScenario.launch<ScanReviewActivity>(reviewIntent!!).use { scenario ->
            if (initial < count) {
                scenario.onActivity { it.appendStagedPages(uris.drop(initial)) }
                await { staging.listFiles { f -> f.extension == "jpg" }!!.size == count }
                // Wait for the append coroutine to publish its reloaded page list.
                await {
                    var loaded = false
                    scenario.onActivity { activity ->
                        val field = ScanReviewActivity::class.java.getDeclaredField("pages").apply { isAccessible = true }
                        loaded = (field.get(activity) as List<*>).size == count
                    }
                    loaded
                }
            }
            var activityShadow: org.robolectric.shadows.ShadowActivity? = null
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
                // Retain the shadow: onActivity cannot be used after finish().
                activityShadow = shadowOf(activity)
            }
            await {
                viewerIntent = activityShadow!!.nextStartedActivity
                    ?.takeIf { it.component?.className == DocumentViewerActivity::class.java.name }
                viewerIntent != null
            }
        }
        val doc = repo.getDocument(viewerIntent!!.getStringExtra("doc_id")!!)!!
        assertSaved(doc, count, "staged")
        assertOriginals(bytes)
    }

    @Test fun `staged 1 page save reopen share and PDF streams`() = staged(1)
    @Test fun `staged 5 pages with append save reopen share and PDF streams`() = staged(5)
    @Test fun `staged 30 pages with append save reopen share and PDF streams`() = staged(30)

    @Test fun `direct bad second image rejects before document creation and preserves all originals`() = runBlocking {
        val existing = repo.saveScan(listOf(Uri.fromFile(File(tmp.root, "existing.jpg").apply { writeBytes(ImportFixtures.image()) })), null, "Existing")
        val snapshot = existing.pdfFile.parentFile!!.listFiles()!!.associate { it.name to it.readBytes() }
        val dirsBefore = File(app.filesDir, "documents").list()!!.toSet()
        val bytes = listOf(ImportFixtures.image(Bitmap.CompressFormat.PNG), byteArrayOf(1, 2, 3))
        val uris = providerUris(bytes)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.saveScan(uris, null, "Bad input") }
        }
        assertTrue(failure.message!!.contains("Page 2"))
        assertTrue(failure.message!!.contains("Choose"))
        assertEquals(dirsBefore, File(app.filesDir, "documents").list()!!.toSet())
        snapshot.forEach { (name, data) -> assertArrayEquals(data, File(existing.pdfFile.parentFile, name).readBytes()) }
        assertOriginals(bytes)
        assertTrue(app.cacheDir.listFiles()!!.none { it.name.startsWith("normalized_page_") })
    }

    @Test fun `partial append rejects bad input keeps valid pages and invalidates old scanner PDF`() {
        val staging = File(tmp.root, "staging").apply { mkdirs() }
        val first = File(staging, "page_001.jpg").apply { writeBytes(ImportFixtures.image()) }
        val before = first.readBytes()
        val pdf = File(staging, "scanner.pdf")
        PdfGenerator.imagesToPdf(listOf(first), pdf)
        val pdfBefore = pdf.readBytes()
        val bytes = listOf(ImportFixtures.image(Bitmap.CompressFormat.PNG), byteArrayOf(1, 2, 3))
        val uris = providerUris(bytes)
        ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, staging.path, pdf.path, "Append")).use { scenario ->
            scenario.onActivity { it.appendStagedPages(uris) }
            await { ShadowToast.getTextOfLatestToast()?.contains("Choose") == true }
            scenario.onActivity { activity ->
                val pdfField = ScanReviewActivity::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }
                assertNull(pdfField.get(activity))
                val changed = ScanReviewActivity::class.java.getDeclaredField("structureChanged").apply { isAccessible = true }
                assertEquals(true, changed.get(activity))
            }
        }
        assertEquals(2, staging.listFiles { f -> f.extension == "jpg" }!!.size)
        ImportFixtures.assertJpeg(File(staging, "page_002.jpg").readBytes()).recycle()
        assertFalse(File(staging, "page_003.jpg").exists())
        assertArrayEquals(before, first.readBytes())
        assertArrayEquals(pdfBefore, pdf.readBytes())
        assertOriginals(bytes)
    }

    @Test fun `empty initial staged input shows an actionable error and preserves provider file`() {
        val bytes = listOf(byteArrayOf())
        val uris = providerUris(bytes)
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> ScanLauncher(activity, repo, activity) {}.stageAndReview(uris) }
            await { ShadowToast.getTextOfLatestToast()?.contains("Choose") == true }
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
        assertTrue(repo.listDocuments().isEmpty())
        assertOriginals(bytes)
        assertEquals(0, stagedPageCount())
    }

    /** Every review session, in the durable root AND the legacy cache location. */
    private fun pendingDirs() = listOf(File(app.filesDir, "pending_scans"), app.cacheDir)
        .flatMap { parent -> parent.listFiles { f -> f.isDirectory && f.name.startsWith("pending_") }?.toList().orEmpty() }
        .map { it.name }.toSet()

    private fun stagedPageCount() = listOf(File(app.filesDir, "pending_scans"), app.cacheDir)
        .sumOf { root -> root.walkTopDown().count { it.name.startsWith("page_") && it.extension == "jpg" } }

    @Test fun `failed second image removes its new staging folder reports Page 2 and leaves no resume offer`() {
        Prefs.setAutoCopyToDocuments(app, false)
        val bytes = listOf(ImportFixtures.image(Bitmap.CompressFormat.PNG), byteArrayOf(1, 2, 3), ImportFixtures.image())
        val uris = providerUris(bytes)
        assertTrue(pendingDirs().isEmpty())
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> ScanLauncher(activity, repo, activity) {}.stageAndReview(uris) }
            await { ShadowToast.getTextOfLatestToast()?.contains("Page 2") == true }
            val message = ShadowToast.getTextOfLatestToast()!!
            assertTrue(message, message.contains("Page 2: "))
            assertTrue(message, message.contains("Choose"))
            scenario.onActivity { activity ->
                assertNull(shadowOf(activity).nextStartedActivity)
                // No ghost: nothing for the next launch to offer as an unfinished scan.
                assertTrue(pendingDirs().isEmpty())
                assertEquals(0, stagedPageCount())
                // Deterministic (not timing-dependent): the launcher finds nothing to offer.
                assertNull(ScanLauncher(activity, repo, activity) {}.findUnfinished())
            }
        }
        assertTrue(repo.listDocuments().isEmpty())
        assertOriginals(bytes)
    }

    @Test fun `failed staging deletes only its own folder and keeps an earlier unfinished scan`() {
        val earlier = File(app.cacheDir, "pending_1000").apply { mkdirs() }
        val earlierPage = File(earlier, "page_001.jpg").apply { writeBytes(ImportFixtures.image()) }
        val earlierBytes = earlierPage.readBytes()
        val bytes = listOf(ImportFixtures.image(), ImportFixtures.image(Bitmap.CompressFormat.PNG).copyOf(40))
        val uris = providerUris(bytes)
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> ScanLauncher(activity, repo, activity) {}.stageAndReview(uris) }
            await { ShadowToast.getTextOfLatestToast()?.contains("Page 2") == true }
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
        assertEquals(setOf("pending_1000"), pendingDirs())
        assertArrayEquals(earlierBytes, earlierPage.readBytes())
        assertOriginals(bytes)
    }

    @Test fun `supplied scanner PDF is never copied and the saved PDF is generated and verified`() = runBlocking {
        val source = File(tmp.root, "scanner.jpg").apply { writeBytes(ImportFixtures.image()) }
        val scannerPdf = File(tmp.root, "scanner.pdf")
        PdfGenerator.imagesToPdf(listOf(source), scannerPdf)
        scannerPdf.appendText("scanner sentinel")
        val pdfBefore = scannerPdf.readBytes()
        val png = File(tmp.root, "png.jpg").apply { writeBytes(ImportFixtures.image(Bitmap.CompressFormat.PNG)) }
        val rotated = File(tmp.root, "rotated.jpg")
        ImportFixtures.oriented(rotated, 6)
        for ((name, file, filter) in listOf(
            Triple("Unchanged", source, ScanFilter.ORIGINAL),
            Triple("Png", png, ScanFilter.ORIGINAL),
            Triple("Rotated", rotated, ScanFilter.ORIGINAL),
            Triple("Filtered", source, ScanFilter.ENHANCE)
        )) {
            val doc = repo.saveScan(listOf(Uri.fromFile(file)), Uri.fromFile(scannerPdf), name, filter)
            assertFalse(name, pdfBefore.contentEquals(doc.pdfFile.readBytes()))
            assertFalse(name, doc.pdfFile.readText(Charsets.ISO_8859_1).contains("scanner sentinel"))
            assertTrue(doc.pdfFile.readText(Charsets.ISO_8859_1).endsWith("%%EOF\n"))
            PdfVerifier.verify(doc.pdfFile, expectedImages = doc.pageFiles)
        }
        assertArrayEquals(pdfBefore, scannerPdf.readBytes())
    }

    @Test fun `direct per-page filters match staged normalized input and retain page order`() = runBlocking {
        val bytes = sources(4)
        val uris = providerUris(bytes)
        val filters = ScanFilter.entries.mapIndexed { i, f -> i to f }.toMap()
        val direct = repo.saveScan(uris, null, "Filtered", pageFilters = filters)
        val stage = File(tmp.root, "filtered-stage").apply { mkdirs() }
        val stagedUris = bytes.mapIndexed { i, data ->
            Uri.fromFile(File(stage, "$i.jpg").apply { writeBytes(ImageTools.normalizePage(data)) })
        }
        val staged = repo.saveScan(stagedUris, null, "Filtered staged", pageFilters = filters)
        for (i in bytes.indices) {
            val a = ImportFixtures.assertJpeg(direct.pageFiles[i].readBytes())
            val b = ImportFixtures.assertJpeg(staged.pageFiles[i].readBytes())
            try {
                assertEquals(120 + i, a.width)
                assertEquals(a.width, b.width)
                ImportFixtures.assertColor(Color.WHITE, a.getPixel(a.width - 1, 79))
                ImportFixtures.assertColor(Color.WHITE, b.getPixel(b.width - 1, 79))
            } finally { a.recycle(); b.recycle() }
        }
        assertOriginals(bytes)
    }

    @Test fun `1 5 and 30 large pages are sampled and processed sequentially`() = runBlocking {
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.PNG, 4000, 2500, transparent = true)
        val source = File(tmp.root, "large-input.dat").apply { writeBytes(bytes) }
        for (count in listOf(1, 5, 30)) {
            val uris = List(count) { Uri.fromFile(source) }
            val filters = uris.indices.associateWith { if (it % 2 == 0) ScanFilter.ORIGINAL else ScanFilter.ENHANCE }
            val doc = repo.saveScan(uris, null, "Large $count", pageFilters = filters)
            assertEquals(count, repo.getDocument(doc.id)!!.pageCount)
            doc.pageFiles.forEach { file ->
                val bitmap = ImportFixtures.assertJpeg(file.readBytes())
                try {
                    assertEquals(2000, bitmap.width)
                    assertEquals(1250, bitmap.height)
                    ImportFixtures.assertColor(Color.WHITE, bitmap.getPixel(1999, 1249))
                } finally { bitmap.recycle() }
            }
            assertTrue(doc.pdfFile.length() > 0)
            System.getenv("WP03_EVIDENCE_DIR")?.let { path ->
                doc.pdfFile.copyTo(File(File(path).apply { mkdirs() }, "large-$count.pdf"), overwrite = true)
            }
        }
        assertArrayEquals(bytes, source.readBytes())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for import UI")
    }
}
