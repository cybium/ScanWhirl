package com.scanwhirl.app.ui.protect
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfImport
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayInputStream

/**
 * The old "This PDF is password-protected. [Got it]" dead end is now the Unlock sheet: a wrong
 * password shows the tries-left line, the right one imports the decrypted pages as a new
 * document. The real decode is covered by [com.scanwhirl.app.util.PdfDecryptorTest]; this test
 * replaces the render step (Robolectric cannot run PdfRenderer) with the same contract.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportProtectedPdfTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanWhirlApp.from(app).repository
        ProGate.setTestOverride(app, true)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        ScanRepository.resetTestSeams()
        PdfDecryptor.resetForTests()
        Faults.resetAll()
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun unlockSheet(): Dialog? {
        val d = ShadowDialog.getLatestDialog() ?: return null
        return d.takeIf { it.findViewById<View>(R.id.unlockPassword) != null }
    }

    private fun submit(dialog: Dialog, text: String) {
        dialog.findViewById<EditText>(R.id.unlockPassword).setText(text)
        dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
    }

    @Test
    fun `a protected PDF asks for its password and imports on the right one`() {        val page = ImportFixtures.image(width = 160, height = 220)
        val uri = Uri.parse("content://scanwhirl-import/protected.pdf")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream("%PDF-1.7 protected".toByteArray())
        }
        var importCalls = 0

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                LockFlow(
                    activity, repo, activity, { emptyList() }, { _ -> },
                    importProtected = { _, name, password ->
                        importCalls++
                        if (password != "1234") throw PdfDecryptor.WrongPassword()
                        repo.createDocument(name ?: "Imported", listOf(page))
                    }
                ).importProtectedPdf(uri, "Imported book")
            }

            assertTrue("the Unlock sheet opens", pollUntil { unlockSheet() != null })
            val sheet = unlockSheet()!!
            assertTrue("the password window is FLAG_SECURE",
                com.scanwhirl.app.util.SecureView.isSecure(sheet.window))
            assertTrue(
                "the title names the file",
                sheet.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
                    ?.contains("Imported book is protected") == true
            )
            assertTrue("nothing imported before a password",
                repo.listDocuments().isEmpty())

            submit(sheet, "0000")
            val error = sheet.findViewById<TextView>(R.id.swPasswordError)
            assertNotNull("a wrong password keeps the sheet open", error)
            assertTrue(
                "the tries-left line is shown",
                pollUntil { error!!.text.contains("4 tries left") }
            )
            assertTrue("nothing imported on a wrong password", repo.listDocuments().isEmpty())

            submit(sheet, "1234")
            assertTrue("the right password imports the pages", pollUntil {
                repo.listDocuments().any { it.name == "Imported book" && it.pageCount == 1 }
            })
            assertEquals("one attempt per submitted password", 2, importCalls)
            assertTrue(
                "the sheet closes after a successful import",
                pollUntil { !sheet.isShowing }
            )
            assertTrue(
                "the imported document has a PDF",
                repo.listDocuments().first().pdfFile.length() > 0
            )
        }
    }

    /**
     * B3: a 40-60 MB protected PDF on a 3 GB phone has to be refused before the whole file is
     * read into memory, with a normal failure the screen can explain. The gate uses the app's
     * heap, so the test shrinks the heap instead of building the file.
     */
    @Test
    fun `a protected PDF larger than the heap can hold is refused before it is read`() = runBlocking {
        PdfDecryptor.maxHeap = { 8L * 1024 * 1024 } // -> a 1 MiB ceiling (the floor)
        val uri = Uri.parse("content://scanwhirl-import/too-big.pdf")
        shadowOf(app.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("%PDF-1.7\n".toByteArray() + ByteArray(2 * 1024 * 1024))
        )

        val failure = try {
            repo.importProtectedPdfAsScan(uri, "Too big", "1234")
            fail("a protected PDF bigger than the memory gate was accepted")
            null
        } catch (e: PdfImport.ImportException) {
            e
        }

        assertEquals(PdfImport.Reason.LOW_MEMORY, failure!!.reason)
        assertTrue("nothing was published", repo.listDocuments().isEmpty())
        assertTrue(
            "the decrypted copy is not left in the cache",
            app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("decrypted-") }
        )
    }

    /**
     * B3: an OutOfMemoryError is an `Error`, so `catch (e: Exception)` never sees it and the
     * process dies with the progress dialog up. The import boundary turns it into the same
     * normal, explainable failure.
     */
    @Test
    fun `an OutOfMemoryError while decrypting becomes a normal import failure`() = runBlocking {
        ScanRepository.decryptPdf = { _, _ -> throw OutOfMemoryError("simulated heap exhaustion") }
        val uri = Uri.parse("content://scanwhirl-import/oom.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("%PDF-1.7 protected".toByteArray()))

        val failure = try {
            repo.importProtectedPdfAsScan(uri, "Cramped", "1234")
            fail("an OutOfMemoryError escaped the import boundary")
            null
        } catch (e: PdfImport.ImportException) {
            e
        }

        assertEquals(PdfImport.Reason.LOW_MEMORY, failure!!.reason)
        assertTrue("nothing was published", repo.listDocuments().isEmpty())
    }
}
