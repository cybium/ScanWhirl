package com.scanwhirl.app.store

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * PM-3.1c: a `locked.meta` beside a plain `scan.pdf` (or an encrypted PDF with no `locked.meta`) is
 * visible, is never shared or saved silently, and can be repaired with the user's password.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectionStateTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }

    @After fun tearDown() = Faults.resetAll()

    private fun dirOf(id: String) = File(docsDir, id)
    private fun pdfOf(id: String) = File(dirOf(id), "scan.pdf")

    /** A plain document that carries a lock record (the old bug, or a PM-3.1b crash window). */
    private fun repairable(): String {
        val id = DocFixtures.saved(repo, app)
        Durable.atomicWriteText(File(dirOf(id), "locked.meta"), Prefs.sha256("pdf_lock:1234"))
        return id
    }

    /** An encrypted PDF whose lock record is gone. */
    private fun unknown(): String {
        val id = DocFixtures.locked(repo, app, "1234")
        assertTrue(File(dirOf(id), "locked.meta").delete())
        return id
    }

    /** Every file of the folder except `locked.meta`: relative path to digest. */
    private fun digestsWithoutLock(id: String): Map<String, String> =
        dirOf(id).walkTopDown().filter { it.isFile && it.name != "locked.meta" }
            .associate { it.relativeTo(dirOf(id)).path to Sha256.of(it) }

    // Pins PdfProtection.isEncodable (carry-over fix C1).
    @Test fun `password encoding accepts printable Latin-1 only`() {
        assertTrue(PdfProtection.isEncodable("abc 123!~"))
        assertTrue(PdfProtection.isEncodable("café"))
        assertFalse("C1 control character must be rejected", PdfProtection.isEncodable("\u0080x"))
        assertFalse(PdfProtection.isEncodable("पासवर्ड"))
        assertFalse(PdfProtection.isEncodable(""))
    }

    // Pins PdfProtection.state and ScanRepository.protectionState.
    @Test fun `classifies plain locked needs repair and encrypted without lock`() {
        val plain = DocFixtures.saved(repo, app)
        val locked = DocFixtures.locked(repo, app)
        val needsRepair = repairable()
        val noLock = unknown()
        assertEquals(PdfProtection.State.NONE, repo.protectionState(plain))
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(locked))
        assertEquals(PdfProtection.State.NEEDS_REPAIR, repo.protectionState(needsRepair))
        assertEquals(PdfProtection.State.UNKNOWN_PASSWORD, repo.protectionState(noLock))
        assertEquals(PdfProtection.State.UNREADABLE, repo.protectionState("does-not-exist"))
    }

    // Pins G18: only the trailer decides, never a grep of the whole file.
    @Test fun `a JPEG containing the bytes Encrypt does not make a plain PDF look encrypted`() {
        val jpeg = ImportFixtures.image()
        val comment = "/Encrypt << >>".toByteArray(Charsets.ISO_8859_1)
        val length = comment.size + 2
        val adversarial = jpeg.copyOfRange(0, 2) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), (length shr 8).toByte(), length.toByte()) +
            comment + jpeg.copyOfRange(2, jpeg.size)
        val image = File(app.cacheDir, "adversarial.jpg").apply { writeBytes(adversarial) }
        val out = File(app.cacheDir, "adversarial.pdf")
        PdfGenerator.imagesToPdf(listOf(image), out)
        assertTrue(
            "fixture must carry the bytes /Encrypt inside the PDF",
            String(out.readBytes(), Charsets.ISO_8859_1).contains("/Encrypt")
        )
        assertEquals(false, PdfProtection.isEncrypted(out))
    }

    // Pins ScanRepository.repairProtection.
    @Test fun `repair with the right password encrypts and with a wrong one changes nothing`() {
        val id = repairable()
        val before = PdfInvariants.dirDigest(dirOf(id))
        val wrong = runCatching { runBlocking { repo.repairProtection(id, "0000") } }
        assertTrue("wrong password must throw WrongPassword, got ${wrong.exceptionOrNull()}",
            wrong.exceptionOrNull() is PdfProtection.WrongPassword)
        assertEquals("a wrong password changed bytes", before, PdfInvariants.dirDigest(dirOf(id)))

        runBlocking { repo.repairProtection(id, "1234") }
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        PdfInvariants.assertI3(repo, id)
        PdfInvariants.assertI1(repo, id)
        assertTrue(repo.verifyPdfPassword(id, "1234"))
        PdfInvariants.assertNoOrphans(app, repo)
    }

    // Pins ScanRepository.removeProtectionLabel.
    @Test fun `remove lock with the right password deletes only locked meta`() {
        val id = repairable()
        val before = digestsWithoutLock(id)
        runBlocking { repo.removeProtectionLabel(id, "1234") }
        assertEquals(before, digestsWithoutLock(id))
        assertFalse(File(dirOf(id), "locked.meta").exists())
        assertEquals(PdfProtection.State.NONE, repo.protectionState(id))
    }

    private fun latestTitle(): String? =
        ShadowDialog.getLatestDialog()
            ?.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    // Pins ProtectionDialogs.guardExport on the reader's Share button.
    @Test fun `share of a document that needs repair shows the dialog and shares nothing`() {
        val id = repairable()
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var shown = false
            assertTrue("share button appears", pollUntil {
                scenario.onActivity { shown = it.findViewById<View>(R.id.btnShare).visibility == View.VISIBLE }
                shown
            })
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnShare).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(
                    "expected dialog 'Protection needs repair'",
                    app.getString(R.string.protection_repair_title), latestTitle()
                )
                assertNull("nothing may be shared", shadowOf(activity).nextStartedActivity)
            }
        }
    }

    // Pins ProtectionDialogs.guardExport around DocumentViewerActivity.savePdfToDocuments.
    @Test fun `save to documents of a document that needs repair writes nothing`() {
        val id = repairable()
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var shown = false
            assertTrue("share button appears", pollUntil {
                scenario.onActivity { shown = it.findViewById<View>(R.id.btnShare).visibility == View.VISIBLE }
                shown
            })
            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNotNull("a dialog is shown", ShadowDialog.getLatestDialog())
                assertEquals(
                    "expected dialog 'Protection needs repair'",
                    app.getString(R.string.protection_repair_title), latestTitle()
                )
            }
        }
        assertTrue("nothing may be written to Documents", repo.exportsOf(id).isEmpty())
    }
}
