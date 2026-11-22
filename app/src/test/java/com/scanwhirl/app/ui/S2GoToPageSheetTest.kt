package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * S2 item 10: the reader's Go-to-page prompt is a lifecycle-managed kit SwSheet (not a leaked raw
 * AlertDialog), and in Vault mode its window is FLAG_SECURE.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S2GoToPageSheetTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var defaultPages: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private var vaultFile: File? = null
    private var now = 1_000_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        defaultPages = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages(3) }
        VaultSession.clock = { now }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultPages
        VaultSession.close()
        VaultSession.clock = { System.currentTimeMillis() }
        vaultFile?.delete()
        ShadowDialog.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun openSheet(scenario: ActivityScenario<PdfViewerActivity>): Dialog {
        scenario.onActivity { a ->
            assertTrue("the page indicator is the entry to Go-to-page", a.findViewById<View>(R.id.pdfPageIndicator).performClick())
        }
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("Go-to-page must show a sheet", dialog)
        assertTrue(
            "Go-to-page must be the kit SwSheet, not a raw AlertDialog",
            dialog!!.findViewById<View>(R.id.swSheetRoot) != null
        )
        assertTrue("the sheet must be showing", dialog.isShowing)
        return dialog
    }

    private fun readerReady(scenario: ActivityScenario<PdfViewerActivity>): Boolean {
        var ready = false
        scenario.onActivity { a ->
            ready = a.findViewById<View>(R.id.pdfPageIndicator)?.visibility == View.VISIBLE
        }
        return ready
    }

    @Test
    fun `the Go-to-page sheet is dismissed on rotation instead of leaking its window`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "GoTo")
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, id, "GoTo")).use { scenario ->
            assertTrue("reader must open", await { readerReady(scenario) })
            val dialog = openSheet(scenario)
            scenario.recreate()
            idle()
            assertFalse(
                "a kit sheet follows its owner's lifecycle and cannot outlive a rotation",
                dialog.isShowing
            )
        }
    }

    @Test
    fun `the Go-to-page sheet is secure in Vault mode`() {
        val file = File(app.cacheDir, "vault_open/fictional/goto.pdf").apply { parentFile!!.mkdirs() }
        vaultFile = file
        val image = File(app.cacheDir, "goto-page.jpg").apply {
            writeBytes(ImportFixtures.image(width = 20, height = 30))
        }
        try {
            PdfGenerator.imagesToPdf(listOf(image), file)
        } finally {
            image.delete()
        }
        VaultSession.open(ByteArray(32) { 1 })

        ActivityScenario.launch<PdfViewerActivity>(
            PdfViewerActivity.privateFileIntent(app, file.path, "Fictional")
        ).use { scenario ->
            assertTrue("the vault reader must open", await { readerReady(scenario) })
            val dialog = openSheet(scenario)
            assertTrue(
                "the Go-to-page sheet must not be screenshot-able over a private document",
                SecureView.isSecure(dialog.window)
            )
        }
    }
}
