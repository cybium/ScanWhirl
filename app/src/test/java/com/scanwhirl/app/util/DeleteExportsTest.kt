package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ProviderInfo
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DeleteExports
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.TrashActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * T4.8 / M06 / M07: exported copies and deletion, through the real screens.
 *
 *  - Moving a document to Trash (document screen → More → Move to Trash) never
 *    touches its exports.
 *  - Permanent deletion from Trash offers to remove only copies the document
 *    provably created (signed provenance), default checked (Q8), and says that
 *    unproven copies stay.
 *  - Foreign-owned rows are skipped without a prompt; unchecked keeps everything.
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
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
    }

    @After
    fun tearDown() {
        media.close()
    }

    @Test
    fun `move to Trash from the document screen keeps the exported copy`() {
        val id = seedDoc("DelEx")
        exportSigned(id, "DelEx")

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            ensureLoaded(scenario)
            scenario.onActivity { activity -> tapMoreDelete(activity) }
            // v2 UI: 09_unlock_sheet — moving to Trash now confirms in a sheet.
            scenario.onActivity { activity ->
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("the trash confirm sheet opens", sheet)
                sheet!!.findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("document leaves the library", poll { repo.getDocument(id) == null })
        }
        assertEquals("it is in Trash, whole", listOf(id), repo.listTrash().map { it.originalId })
        assertEquals("Trash never touches exports", 1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `permanent delete from Trash removes the proven copy`() {
        val id = seedDoc("DelEx")
        exportSigned(id, "DelEx")
        repo.moveToTrash(id)

        val text = deleteForeverFromTrash(uncheck = false)
        assertTrue("result must report the removal — got $text", text != null && text.contains("1 copy removed"))
        assertTrue("Trash entry gone", repo.listTrash().isEmpty())
        assertTrue("proven copy removed: ${media.scanwhirlPdfs().map { it.name }}", media.scanwhirlPdfs().isEmpty())
    }

    @Test
    fun `leaving the box unchecked keeps the exported copy`() {
        val id = seedDoc("Unchecked")
        exportSigned(id, "Unchecked")
        repo.moveToTrash(id)

        deleteForeverFromTrash(uncheck = true)
        assertTrue("document deleted either way", repo.listTrash().isEmpty())
        assertEquals("unchecked box → the copy stays", 1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `a foreign-owned copy is skipped but the document is still deleted`() {
        val id = seedDoc("Foreign")
        exportSigned(id, "Foreign")
        media.scanwhirlPdfs().single().owner = "com.some.other.app"
        repo.moveToTrash(id)

        val text = deleteForeverFromTrash(uncheck = false)
        assertTrue("message must report the kept copy — got $text", text != null && text.contains("kept"))
        assertTrue("a refused copy never blocks the delete", repo.listTrash().isEmpty())
        assertEquals("foreign-owned row kept, no prompt", 1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `an unsigned older record is not offered and the dialog says older copies stay`() {
        val id = seedDoc("Older")
        val saved = DownloadSaver.savePdfFile(app, "pdf".toByteArray(), DownloadSaver.pdfNameFor("Older"))
        repo.addExport(id, saved.uri, saved.fingerprint) // pre-M07 shape: recorded, not signed
        repo.moveToTrash(id)

        ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
            awaitRows(scenario, 1)
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnTrashDelete).performClick()
            }
            val dialog = awaitDialog()
            assertNull("no copy count for unproven copies", dialog.findViewById<android.view.View>(R.id.deleteCopiesBox))
            val note = dialog.findViewById<android.widget.TextView>(R.id.deleteCopiesNote)
            assertNotNull("the dialog says unproven copies stay", note)
            assertEquals(app.getString(R.string.delete_copies_unproven_note), note!!.text.toString())
            // v2 UI: 09_unlock_sheet — the confirmation is a SwSheet; find its primary by id.
            dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
            assertTrue(poll { repo.listTrash().isEmpty() })
        }
        assertEquals("the older copy is kept", 1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `export twice records identical once and a changed copy separately`() {
        val id = seedDoc("Twice")
        val first = DownloadSaver.savePdfFile(app, "v1".toByteArray(), DownloadSaver.pdfNameFor("Twice"))
        repo.addExport(id, first)
        val same = DownloadSaver.savePdfFile(app, "v1".toByteArray(), DownloadSaver.pdfNameFor("Twice"))
        repo.addExport(id, same)
        assertEquals("identical: same uri", first.uri, same.uri)
        assertEquals("exportsOf lists it exactly once", listOf(first.uri), repo.exportsOf(id))

        val second = DownloadSaver.savePdfFile(app, "v2".toByteArray(), DownloadSaver.pdfNameFor("Twice"))
        repo.addExport(id, second)
        assertEquals(2, media.scanwhirlPdfs().size)
        assertEquals("v1", String(media.rowNamed("Twice.pdf").file.readBytes()))
        assertEquals("v2", String(media.rowNamed("Twice (2).pdf").file.readBytes()))
        assertEquals(listOf(first.uri, second.uri), repo.exportsOf(id))
        assertEquals(
            "the re-save keeps the first copy's proof",
            listOf(first.uri, second.uri),
            DeleteExports.copiesFor(repo, id).map { it.uri }
        )
    }

    @Test
    fun `old two-line meta reads and survives setCategory`() {
        val id = seedDoc("Legacy")
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

    private fun exportSigned(id: String, name: String) {
        val saved = DownloadSaver.savePdfFile(app, "pdf".toByteArray(), DownloadSaver.pdfNameFor(name))
        repo.addExport(id, saved)
    }

    /** Trash → Delete permanently → (optionally untick) → confirm; returns the snackbar text. */
    private fun deleteForeverFromTrash(uncheck: Boolean): String? {
        ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
            awaitRows(scenario, 1)
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnTrashDelete).performClick()
            }
            val dialog = awaitDialog()
            val box = dialog.findViewById<android.widget.CheckBox>(R.id.deleteCopiesBox)
            assertNotNull("the checkbox row appears for a proven copy (Q8)", box)
            assertTrue("default checked (Q8)", box!!.isChecked)
            if (uncheck) box.isChecked = false
            dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
            return awaitSnackbarText(scenario)
        }
    }

    private fun awaitDialog(): android.app.Dialog {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val d = ShadowDialog.getLatestDialog()
            // v2 UI: 09_unlock_sheet — confirmations are SwSheets (found by id).
            if (d != null && d.isShowing && d.findViewById<View>(R.id.swSheetPrimary) != null) return d
            Thread.sleep(30)
        }
        throw AssertionError("dialog never appeared")
    }

    private fun awaitRows(scenario: ActivityScenario<TrashActivity>, n: Int) {
        assertTrue("Trash shows $n row(s)", poll {
            var count = -1
            scenario.onActivity { a ->
                val list = a.findViewById<RecyclerView>(R.id.trashList)
                list.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY)
                )
                list.layout(0, 0, 1080, 2000)
                count = if (list.findViewHolderForAdapterPosition(n - 1) != null) list.adapter!!.itemCount else -1
            }
            count == n
        })
    }

    private fun tapMoreDelete(activity: android.app.Activity) {
        activity.findViewById<android.view.View>(R.id.btnMore).performClick()
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("the More sheet must open", sheet)
        val label = sheet!!.findViewById<android.widget.TextView>(R.id.labelMoreDelete)
        assertEquals(app.getString(R.string.trash_move), label.text.toString())
        sheet.findViewById<android.view.View>(R.id.rowMoreDelete)!!.performClick()
    }

    private fun ensureLoaded(scenario: ActivityScenario<DocumentViewerActivity>) {
        poll {
            var ready = false
            scenario.onActivity { a ->
                ready = a.findViewById<android.widget.TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
            }
            ready
        }
    }

    private fun poll(check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (check()) return true
            Thread.sleep(30)
        }
        return false
    }

    private fun awaitSnackbarText(scenario: ActivityScenario<TrashActivity>): String? {
        var text: String? = null
        poll {
            scenario.onActivity { activity ->
                text = activity.findViewById<android.widget.TextView>(
                    com.google.android.material.R.id.snackbar_text
                )?.text?.toString()?.takeIf { it.isNotEmpty() }
            }
            text != null
        }
        return text
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String): String = runBlocking {
        val uri = android.net.Uri.parse("content://test/del_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, jpegBytes().inputStream())
        repo.saveScan(listOf(uri), null, name).id
    }
}
