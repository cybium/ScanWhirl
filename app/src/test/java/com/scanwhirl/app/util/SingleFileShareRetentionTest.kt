package com.scanwhirl.app.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SingleFileShareRetentionTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val bytes = ByteArray(128 * 1024) { (it * 31).toByte() }

    @Before fun resetProvider() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun source(name: String, root: String = "documents/tools/run"): File =
        File(app.cacheDir, "$root/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun send(file: File, mime: String): Intent {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.shareFile(controller.get(), file, mime)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (shadowOf(controller.get()).peekNextStartedActivity() == null &&
                System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val chooser = shadowOf(controller.get()).nextStartedActivity
            assertNotNull("completed share starts a chooser", chooser)
            @Suppress("DEPRECATION")
            return chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Suppress("DEPRECATION")
    private fun uri(send: Intent): Uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
    private fun read(uri: Uri): ByteArray =
        app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun assertFormat(name: String, mime: String) {
        val original = source(name)
        val intent = send(original, mime)
        val shared = uri(intent)
        assertTrue("single-file grant must use documents/share, not tools: $shared",
            shared.path!!.contains("/share/x3-"))
        assertEquals(name, shared.lastPathSegment)
        assertEquals(mime, intent.type)
        // FileProvider derives getType() from the saved extension via Android's MimeTypeMap;
        // Robolectric's shadow map is empty and returns application/octet-stream. The recipient
        // gets the explicit intent/ClipData MIME, and the production lookup keeps the extension.
        val provided = app.contentResolver.getType(shared)
        assertTrue("provider exposes a type (Robolectric falls back to octet-stream): $provided",
            provided == mime || provided == "application/octet-stream")
        assertTrue(intent.clipData!!.description.hasMimeType(mime))
        assertEquals(shared, intent.clipData!!.getItemAt(0).uri)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        app.contentResolver.query(shared, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(name, it.getString(0))
        }
        PrivacySweep.run(app)
        assertFalse("cold start still cleans tool scratch", original.exists())
        assertArrayEquals("delayed provider read survives cold start", bytes, read(shared))
    }

    @Test fun jpgSurvivesColdStart() = assertFormat("Fictional page.jpg", "image/jpeg")
    @Test fun docxSurvivesColdStart() = assertFormat("Fictional report.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    @Test fun xlsxSurvivesColdStart() = assertFormat("Fictional sheet.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Test fun pptxSurvivesColdStart() = assertFormat("Fictional slides.pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    @Test fun zipSurvivesColdStart() = assertFormat("Fictional pages.zip", "application/zip")
    @Test fun pdfSurvivesColdStart() = assertFormat("Fictional scan.pdf", "application/pdf")

    @Test fun documentSingleFilePathAlsoUsesIndependentNamedCopy() {
        val original = source("scan.pdf")
        val doc = com.scanwhirl.app.model.ScanDocument(
            id = "fictional", name = "Fictional document", createdAt = 1,
            pageCount = 1, pdfFile = original, pageFiles = emptyList()
        )
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.share(controller.get(), doc)
            val chooser = awaitShareChooser(controller.get())
            @Suppress("DEPRECATION")
            val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val shared = uri(intent)
            assertEquals("Fictional document.pdf", shared.lastPathSegment)
            assertEquals("application/pdf", intent.type)
            assertTrue(shared.path!!.contains("/share/x3-"))
            PrivacySweep.run(app)
            assertFalse(original.exists())
            assertArrayEquals(bytes, read(shared))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun undoAndAnotherShareCannotChangeIssuedBytes() {
        val original = source("Same.jpg")
        val first = uri(send(original, "image/jpeg"))
        original.delete() // Tool result Undo removes its scratch output.
        val replacement = source("Same.jpg").apply { writeBytes(byteArrayOf(9)) }
        val second = uri(send(replacement, "image/jpeg"))
        assertNotEquals("fresh token for every share", first, second)
        PrivacySweep.run(app)
        assertArrayEquals(bytes, read(first))
        assertArrayEquals(byteArrayOf(9), read(second))
    }

    @Test fun actualShareExpiresByTokenNotModifiedTime() {
        val shared = uri(send(source("Expired.zip"), "application/zip"))
        assertTrue("actual outgoing copy must have a retention token", shared.path!!.contains("/share/x3-"))
        val token = shared.pathSegments[shared.pathSegments.lastIndex - 1]
        val dir = File(app.cacheDir, "documents/share/$token")
        val created = token.removePrefix("x3-").substringBefore('-').toLong()
        dir.setLastModified(created + ShareUtils.SHARE_KEEP_MS * 10)
        assertEquals(0, ShareUtils.pruneShareCopies(app, created + ShareUtils.SHARE_KEEP_MS - 1))
        assertArrayEquals(bytes, read(shared))
        assertEquals(1, ShareUtils.pruneShareCopies(app, created + ShareUtils.SHARE_KEEP_MS + 1))
        assertFalse(dir.exists())
    }

    @Test fun vaultOpenShareRemainsExcludedAndMakesNoRetainedCopy() {
        val original = source("Private.pdf", "vault_open/fictional")
        assertRefused(original)
        assertArrayEquals("refusal does not alter Vault scratch", bytes, original.readBytes())
        assertTrue(File(app.cacheDir, "documents/share").listFiles().isNullOrEmpty())
        PrivacySweep.run(app)
        assertFalse("Vault scratch still follows cold-start deletion", original.exists())
    }

    private fun assertRefused(file: File) {
        ShadowToast.reset()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            ShareUtils.shareFile(controller.get(), file, "application/zip")
            val deadline = System.nanoTime() + 10_000_000_000L
            while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertEquals(app.getString(com.scanwhirl.app.R.string.nothing_to_share),
                ShadowToast.getTextOfLatestToast())
            assertNull("failed copy must never grant a URI", shadowOf(controller.get()).nextStartedActivity)
            val root = File(app.cacheDir, "documents/share")
            assertTrue("failed copy must leave no payload",
                root.walkTopDown().none { it.isFile && it != root })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun emptySourceDoesNotShareHalfCopy() {
        assertRefused(source("Empty.zip").apply { writeBytes(byteArrayOf()) })
    }

    @Test fun missingSourceDoesNotShareHalfCopy() {
        assertRefused(source("Missing.zip").apply { delete() })
    }

    @Test fun unavailableShareDirectoryShowsHonestErrorWithoutGrant() {
        val original = source("Complete.zip")
        File(app.cacheDir, "documents/share").writeText("blocked directory")
        assertRefused(original)
        assertArrayEquals("failed share must not modify the original", bytes, original.readBytes())
    }
}
