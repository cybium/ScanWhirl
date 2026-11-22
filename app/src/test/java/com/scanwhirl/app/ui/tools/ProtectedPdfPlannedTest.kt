package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.PdfImport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream

/**
 * v2 UI: 03_tools_hub — a protected PDF still reaches L3's Unlock sheet through the *planned*
 * import path (the pre-check classifies it as PASSWORD before any budget sheet or page render).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectedPdfPlannedTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before
    fun setUp() {
        PdfImport.pageCountSeam = {
            throw PdfImport.ImportException(PdfImport.Reason.PASSWORD, "This PDF is password-protected")
        }
    }

    @After
    fun tearDown() {
        PdfImport.resetForTests()
        Faults.resetAll()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun unlockSheet(): Dialog? {
        val dialog = ShadowDialog.getLatestDialog() ?: return null
        return dialog.takeIf { it.findViewById<View>(R.id.unlockPassword) != null }
    }

    @Test
    fun `a protected PDF from the planned import opens the unlock sheet`() {
        val uri = Uri.parse("content://scanwhirl-planned/protected.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("%PDF-1.7 protected".toByteArray()))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { it.importPdf(uri) }
            assertTrue("the Unlock sheet opens", waitUntil { unlockSheet() != null })
            val sheet = unlockSheet()!!
            val title = sheet.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString().orEmpty()
            assertTrue("the title names the file: $title", title.contains("protected"))
            assertTrue("nothing is imported before a password", repo.listDocuments().isEmpty())
        }
    }
}
