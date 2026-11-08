package com.scanwhirl.app.util

import android.app.Application
import android.content.ContentUris
import android.content.pm.ProviderInfo
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
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
import java.io.File

/**
 * T4.8 delete removes exported copies (complaint #18, N11, Q8): deleting a
 * document also removes the PDF copies it saved into Documents/ScanWhirl —
 * recorded in `meta.txt` (`exports=` line) or matched by exact legacy name,
 * never anything the app cannot prove it wrote.
 *
 * Shares [FakeMediaStore] with [ExportHygieneTest]; foreign-owned rows refuse
 * deletion with a SecurityException, like the real MediaStore.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DeleteExportsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(
                app,
                ProviderInfo().apply { authority = "media"; exported = true }
            )
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
    }

    @After
    fun tearDown() {
        media.close()
    }

    // ------------------------------------------------------------------
    // T4.8 tests
    // ------------------------------------------------------------------

    @Test
    fun `delete document removes the recorded exported copy`() {
        val id = seedDoc("DelEx")
        // A copy in Documents/ScanWhirl, exactly like the download button makes.
        DownloadSaver.savePdf(app, "pdf".toByteArray(), DownloadSaver.pdfNameFor("DelEx"))
        recordExport(id)

        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnDelete).performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                val text = activity.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.snackbar_text
                )?.text?.toString()
                assertTrue(
                    "result message must report the removal — got $text",
                    text != null && text.contains("1 copy removed")
                )
            }
        }

        assertTrue("document must be deleted", repo.getDocument(id) == null)
        assertTrue(
            "T4.8: deleting the document must also remove its recorded export — " +
                "got ${media.scanwhirlPdfs().names()}",
            media.scanwhirlPdfs().isEmpty()
        )
    }

    @Test
    fun `leaving the box unchecked keeps the exported copy`() {
        val id = seedDoc("Unchecked")
        DownloadSaver.savePdf(app, "pdf".toByteArray(), DownloadSaver.pdfNameFor("Unchecked"))
        recordExport(id)

        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnDelete).performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val box = dialog.window!!.decorView
                    .findViewById<android.widget.CheckBox>(R.id.deleteCopiesBox)
                assertNotNull(
                    "the checkbox row must appear when copies exist (Q8)", box
                )
                box!!.isChecked = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
        }

        assertTrue("document must be deleted either way", repo.getDocument(id) == null)
        assertEquals(
            "unchecked box → the exported copy stays (T4.8 rule 4)",
            1,
            media.scanwhirlPdfs().size
        )
    }

    @Test
    fun `a foreign-owned copy is skipped but the document is still deleted`() {
        val id = seedDoc("Foreign")
        DownloadSaver.savePdf(app, "pdf".toByteArray(), DownloadSaver.pdfNameFor("Foreign"))
        // e.g. the same file written by the Play build / an older install.
        media.scanwhirlPdfs().single().owner = "com.some.other.app"
        recordExport(id)

        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnDelete).performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                val text = activity.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.snackbar_text
                )?.text?.toString()
                assertTrue(
                    "message must report the skipped copy — got $text",
                    text != null && text.contains("could not be removed")
                )
            }
        }

        assertTrue(
            "a refused copy must never block the document delete",
            repo.getDocument(id) == null
        )
        assertEquals(
            "foreign-owned row must be skipped, no permission prompt",
            1,
            media.scanwhirlPdfs().size
        )
    }

    @Test
    fun `export twice records a single replace-not-duplicate uri`() {
        val id = seedDoc("Twice")
        val first = DownloadSaver.savePdfFile(
            app, "v1".toByteArray(), DownloadSaver.pdfNameFor("Twice")
        )
        repo.addExport(id, first.uri)
        val second = DownloadSaver.savePdfFile(
            app, "v2".toByteArray(), DownloadSaver.pdfNameFor("Twice")
        )
        repo.addExport(id, second.uri)

        assertEquals("replace-not-duplicate: one row", 1, media.scanwhirlPdfs().size)
        assertEquals("the replacement rewrites the same uri", first.uri, second.uri)
        assertEquals(
            "exportsOf lists the uri exactly once",
            listOf(first.uri),
            repo.exportsOf(id)
        )
        assertEquals(
            "the row now holds the second write",
            "v2",
            String(media.scanwhirlPdfs().single().file.readBytes())
        )
    }

    @Test
    fun `legacy match only hits exact names owned by this package`() {
        DownloadSaver.savePdf(app, "1".toByteArray(), "Doc.pdf")
        DownloadSaver.savePdf(app, "2".toByteArray(), "Doc (2).pdf")
        DownloadSaver.savePdf(app, "3".toByteArray(), "Doc (3).pdf")
        DownloadSaver.savePdf(app, "4".toByteArray(), "Doc extra.pdf")
        DownloadSaver.savePdf(app, "5".toByteArray(), "Other.pdf")
        // "Doc (3).pdf" was written by someone else (e.g. the Play build).
        media.rows.values.first {
            it.values.getAsString("_display_name") == "Doc (3).pdf"
        }.owner = "com.some.other.app"

        val found = DownloadSaver.findCopies(app, "Doc")
        val names = found.map { uri ->
            media.rows[ContentUris.parseId(uri)]!!
                .values.getAsString("_display_name")
        }.sorted()
        assertEquals(
            "only <name>.pdf / <name> (n).pdf owned by this package — got $names",
            listOf("Doc (2).pdf", "Doc.pdf"),
            names
        )
    }

    @Test
    fun `old two-line meta reads and survives setCategory`() {
        val id = seedDoc("Legacy")
        // Pre-test23 scans: exactly two lines, no trailing newline (§8).
        val meta = File(app.filesDir, "documents/$id/meta.txt")
        meta.writeText("Legacy\n1700000000000")

        val doc = repo.getDocument(id)
        assertNotNull("a 2-line meta.txt must still read", doc)
        assertEquals("Legacy", doc!!.name)
        assertEquals(CategoryRules.OTHER, doc.category)

        val after = repo.setCategory(id, CategoryRules.RECEIPTS)
        assertNotNull("setCategory must work on an old doc", after)
        val lines = meta.readLines()
        assertEquals("Legacy", lines[0])
        assertEquals(CategoryRules.RECEIPTS, lines[2])
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun List<FakeMediaStore.Row>.names(): String =
        joinToString { it.values.getAsString("_display_name") }

    /** Appends the T4.8 `exports=` line for the single exported row. */
    private fun recordExport(id: String) {
        val row = media.scanwhirlPdfs().single()
        val uri = ContentUris.withAppendedId(
            MediaStore.Files.getContentUri("external"), row.id
        )
        val meta = File(app.filesDir, "documents/$id/meta.txt")
        meta.writeText(meta.readText().trimEnd('\n') + "\nexports=$uri")
    }

    private fun contentUri(bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://test/del_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            310, 444, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 1): String = runBlocking {
        val uris = (0 until pages).map { contentUri(jpegBytes()) }
        repo.saveScan(uris, null, name).id
    }
}
