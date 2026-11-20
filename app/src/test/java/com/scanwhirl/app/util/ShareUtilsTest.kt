package com.scanwhirl.app.util

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G2 — the single-file share intent must carry a ClipData for the FileProvider URI
 * (the Android 12+ sharesheet reads the grant from it; EXTRA_STREAM alone produced
 * "Permission Denial ... call Intent#setClipData()" on API 37) and that ClipData plus
 * the read grant must survive onto the chooser intent that is actually started.
 *
 * Host test: it asserts the intent contract and that the URI is readable through the
 * app's own provider. It cannot prove how a real sharesheet / target app behaves —
 * that stays an on-device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShareUtilsTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * androidx FileProvider caches its path roots per authority in a static map; under
     * Robolectric every test gets a fresh cacheDir, so a stale entry from an earlier test
     * makes getUriForFile fail with "Failed to find configured root". Reset it.
     */
    @Before fun resetFileProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    private fun jpeg(): ByteArray = ImportFixtures.image()

    private fun shared(file: File, mime: String): Pair<Intent, Intent> {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.shareFile(controller.get(), file, mime)
            val chooser = shadowOf(controller.get()).nextStartedActivity
            assertNotNull("share must start the chooser", chooser)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            return chooser to send
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun cacheFile(name: String, bytes: ByteArray): File {
        val dir = File(app.cacheDir, "documents/share").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    private fun assertSingleShare(name: String, mime: String) {
        val bytes = ByteArray(2048) { (it * 31).toByte() }
        val (chooser, send) = shared(cacheFile(name, bytes), mime)

        // The chooser wraps a SEND intent for exactly this MIME.
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(mime, send.type)

        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.fileprovider", uri.authority)

        // ClipData: one item, the same URI, MIME advertised.
        val clip: ClipData = send.clipData!!
        assertEquals(1, clip.itemCount)
        assertEquals(uri, clip.getItemAt(0).uri)
        assertTrue("clip must advertise $mime", clip.description.hasMimeType(mime))

        // Read grant (never write) on the target intent AND on the chooser, which
        // must also carry the same ClipData so the grant survives the chooser hop.
        for (i in listOf(send, chooser)) {
            assertTrue("read grant on ${i.action}", i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("no write grant on ${i.action}", 0, i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        assertNotNull("chooser carries ClipData", chooser.clipData)
        assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)

        // The URI actually resolves, with the complete bytes, via the app's provider.
        assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun `single PDF share has ClipData uri mime and read grant`() =
        assertSingleShare("Document.pdf", "application/pdf")

    @Test fun `single ZIP share has ClipData uri mime and read grant`() =
        assertSingleShare("Export.zip", "application/zip")

    @Test fun `single image share has ClipData uri mime and read grant`() =
        assertSingleShare("page.jpg", "image/jpeg")

    @Test fun `share of a document with a PDF goes through the single-file path`() {
        val doc = runBlocking {
            val uri = Uri.parse("content://share_test/${System.nanoTime()}.jpg")
            shadowOf(app.contentResolver).registerInputStream(uri, jpeg().inputStream())
            ScanRepository(app).saveScan(listOf(uri), null, "Share doc")
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.share(controller.get(), doc)
            val chooser = shadowOf(controller.get()).nextStartedActivity
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            @Suppress("DEPRECATION")
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("application/pdf", send.type)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            // Shared under the document's own name, with the complete PDF.
            assertEquals("Share doc.pdf", uri.lastPathSegment)
            assertArrayEquals(
                doc.pdfFile.readBytes(),
                app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** sharePages already had ClipData — pin it so it is never regressed. */
    @Test fun `multi page share keeps ClipData for every page`() {
        val doc = runBlocking {
            val uris = (0 until 2).map {
                Uri.parse("content://share_test/pages_${System.nanoTime()}_$it.jpg").also { u ->
                    shadowOf(app.contentResolver).registerInputStream(u, jpeg().inputStream())
                }
            }
            ScanRepository(app).saveScan(uris, null, "Pages doc")
        }
        doc.pdfFile.delete() // no PDF -> share() falls back to sharePages()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.share(controller.get(), doc)
            val chooser = shadowOf(controller.get()).nextStartedActivity
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
            assertEquals(2, send.clipData!!.itemCount)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // ---- X3-SHARECOPY: two documents with the same name must never share bytes ----

    /** The URI the chooser hands to the target app, in share order. */
    private fun sentUri(activity: Activity): Uri {
        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("share must start the chooser", chooser)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        return send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
    }

    private fun fakeDoc(id: String, name: String, bytes: ByteArray): ScanDocument {
        val dir = File(app.cacheDir, "x3_$id").apply { mkdirs() }
        val pdf = File(dir, "scan.pdf").apply { writeBytes(bytes) }
        return ScanDocument(
            id = id,
            name = name,
            createdAt = 1_700_000_000_000L,
            pageCount = 1,
            pdfFile = pdf,
            pageFiles = emptyList()
        )
    }

    private fun read(uri: Uri): ByteArray =
        app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    /**
     * The W1-found bug: both documents are named exactly "Aadhaar front - FAKE", so a
     * cache file named after the display name made the second share overwrite the first.
     * A slow recipient still reading URI #1 then received document #2's bytes.
     */
    @Test fun `two documents with the same display name each share their own bytes`() {
        val firstBytes = ByteArray(4096) { 0x11 }
        val secondBytes = ByteArray(4096) { 0x22 }
        val first = fakeDoc("doc-one", "Aadhaar front - FAKE", firstBytes)
        val second = fakeDoc("doc-two", "Aadhaar front - FAKE", secondBytes)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.share(controller.get(), first)
            val firstUri = sentUri(controller.get())
            ShareUtils.share(controller.get(), second)
            val secondUri = sentUri(controller.get())

            assertNotEquals("each share needs its own file", firstUri, secondUri)
            assertEquals("Aadhaar front - FAKE.pdf", firstUri.lastPathSegment)
            assertEquals("Aadhaar front - FAKE.pdf", secondUri.lastPathSegment)
            assertArrayEquals(
                "the first recipient still reads the first document",
                firstBytes,
                read(firstUri)
            )
            assertArrayEquals(
                "the second recipient reads the second document",
                secondBytes,
                read(secondUri)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** Every fresh copy stays readable through the provider's nested cache root. */
    @Test fun `per share files are valid FileProvider paths under documents share`() {
        val bytes = ByteArray(1024) { (it * 17).toByte() }
        val first = ShareUtils.newShareTarget(app, "Report - FAKE.pdf")
        first.writeBytes(bytes)
        val second = ShareUtils.newShareTarget(app, "Report - FAKE.pdf")
        second.writeBytes(bytes.reversedArray())

        assertEquals("Report - FAKE.pdf", first.name)
        assertEquals("Report - FAKE.pdf", second.name)
        assertNotEquals("same display name, different folder", first.parentFile, second.parentFile)
        val shareRoot = File(app.cacheDir, "documents/share")
        assertTrue(
            "both folders live under the provider's cache root",
            first.canonicalPath.startsWith(shareRoot.canonicalPath + File.separator) &&
                second.canonicalPath.startsWith(shareRoot.canonicalPath + File.separator)
        )
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", first)
        assertArrayEquals(bytes, read(uri))
    }

    @Test fun `a long display name keeps its pdf extension`() {
        val target = ShareUtils.newShareTarget(app, "Z".repeat(80) + ".pdf")
        assertEquals("sixty-character stem plus the extension", 64, target.name.length)
        assertTrue("the recipient still sees a PDF name", target.name.endsWith(".pdf"))
        assertEquals(
            "the same human name is used for every share",
            ShareUtils.newShareTarget(app, "Z".repeat(80) + ".pdf").name,
            target.name
        )
    }

    // ---- cleanup: age-based, never a copy a recipient may still read ----

    @Test fun `cleanup never deletes a copy younger than the keep window`() {
        val fresh = ShareUtils.newShareTarget(app, "Aadhaar front - FAKE.pdf")
        fresh.writeBytes(byteArrayOf(1, 2, 3))
        val removed = ShareUtils.pruneShareCopies(app)
        assertEquals("nothing recent is removed", 0, removed)
        assertTrue("the young copy is still there", fresh.exists())
    }

    @Test fun `cleanup removes every copy older than the keep window`() {
        val now = System.currentTimeMillis()
        val old = now - ShareUtils.SHARE_KEEP_MS - 60_000L
        // Create all copies first: creating a share prunes, and these are still young then.
        val written = (0 until 4).map { i ->
            ShareUtils.newShareTarget(app, "Same name - FAKE.pdf").apply {
                writeBytes(byteArrayOf(i.toByte()))
            }
        }
        val targets = written.mapIndexed { i, file ->
            val folder = file.parentFile!!
            val aged = File(folder.parentFile, "x3-${old - i * 1000L}-test$i")
            assertTrue("test folder renamed", folder.renameTo(aged))
            aged.setLastModified(old - i * 1000L)
            File(aged, file.name).apply { setLastModified(old - i * 1000L) }
        }
        val removed = ShareUtils.pruneShareCopies(app, now)
        assertEquals("every stale copy is reclaimed", targets.size, removed)
        targets.forEach { assertFalse("still there: ${it.absolutePath}", it.exists()) }
    }

    @Test fun `cleanup also reclaims legacy copies written straight into documents share`() {
        val legacy = cacheFile("Old shared report.pdf", byteArrayOf(9, 9))
        legacy.setLastModified(System.currentTimeMillis() - ShareUtils.SHARE_KEEP_MS - 1)
        val removed = ShareUtils.pruneShareCopies(app)
        assertEquals(1, removed)
        assertFalse(legacy.exists())
    }
}
