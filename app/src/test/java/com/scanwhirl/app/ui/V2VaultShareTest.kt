package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.SecureView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * V2 Vault reader hardening (test first, RED on base 05e392f0):
 *  - a Vault copy is never fed to FileProvider (no share path exists for it);
 *  - the reader's More sheet keeps the private title FLAG_SECURE;
 *  - the whole `vault_open/<id>` directory dies with the reader and on session expiry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V2VaultShareTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var pdf: File
    private lateinit var page: File
    private lateinit var factory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private var now = 1_000_000L

    private class FakePages : PdfViewerActivity.PdfPages {
        override val pageCount = 1
        override fun render(index: Int, maxDim: Int) = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VaultSession.clock = { now }
        VaultSession.close()
        root = File(app.cacheDir, "vault_open")
        root.mkdirs()
        dir = File(root, "v2-fixture")
        dir.deleteRecursively()
        dir.mkdirs()
        pdf = File(dir, "document.pdf")
        page = File(dir, "page_001.jpg")
        val source = File(app.cacheDir, "v2-source-page.jpg").apply {
            writeBytes(ImportFixtures.image(width = 20, height = 30))
        }
        try {
            PdfGenerator.imagesToPdf(listOf(source), pdf)
        } finally {
            source.delete()
        }
        page.writeBytes(ImportFixtures.image(width = 12, height = 18))
        factory = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = { FakePages() }
        VaultSession.open(ByteArray(32) { 3 })
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        PdfViewerActivity.pagesFactory = factory
        VaultSession.close()
        VaultSession.clock = { System.currentTimeMillis() }
        dir.deleteRecursively()
    }

    private fun reader() = Robolectric.buildActivity(
        PdfViewerActivity::class.java,
        PdfViewerActivity.privateFileIntent(app, pdf.path, "Private title")
    )

    @Test
    fun vaultDockShareIsHiddenAndNeverGrantsAUriForVaultOpen() {
        val controller = reader().setup()
        val activity = controller.get()
        val share = activity.findViewById<View>(R.id.readerDockShare)
        assertEquals("Share is not offered for a Vault copy", View.GONE, share.visibility)

        val failure = runCatching {
            share.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }.exceptionOrNull()
        assertNull("Vault Share must not crash in FileProvider", failure)

        val started = shadowOf(app).nextStartedActivity
        assertNull("No share chooser and no vault_open content URI may be granted", started)
        controller.pause().stop().destroy()
    }

    @Test
    fun vaultMoreSheetIsSecureAndOffersNoShare() {
        val controller = reader().setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.readerDockMore).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "More must open a sheet" }
        assertTrue(
            "the private title sits on a FLAG_SECURE window",
            SecureView.isSecure(dialog.window)
        )
        val secondary = dialog.findViewById<View>(R.id.swSheetSecondary)
        assertTrue(
            "Share must not be offered for a Vault copy",
            secondary == null || secondary.visibility != View.VISIBLE
        )
        val failure = runCatching {
            secondary?.performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }.exceptionOrNull()
        assertNull("More > Share must not crash or grant a URI", failure)
        assertNull("No share chooser may start from the Vault reader", shadowOf(app).nextStartedActivity)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun finishingVaultReaderDeletesTheWholeOpenDirectory() {
        val sibling = File(root, "v2-other").apply { mkdirs() }
        val keep = File(sibling, "keep.txt").apply { writeText("another document") }
        val outside = File(app.cacheDir, "v2-outside.pdf").apply { writeText("ordinary file") }
        val controller = reader().setup()
        assertTrue(pdf.isFile)
        assertTrue(page.isFile)
        // Back/finish is the real exit; a plain destroy() is a configuration change.
        controller.get().finish()
        controller.pause().stop().destroy()

        assertFalse("page images must die with the decrypted PDF", dir.exists())
        assertTrue("only this document's open folder is deleted", keep.isFile)
        assertTrue("files outside vault_open are untouched", outside.isFile)
        sibling.deleteRecursively()
        outside.delete()
    }

    @Test
    fun sessionExpiryWipesDecryptedCopies() {
        // Constructing a store registers the open-copy wipe with the process-wide session.
        VaultStore(
            context = app,
            repo = ScanRepository(app),
            keys = FakeVaultKeys(),
            iterations = 2_000
        )
        val expired = File(root, "v2-expiry").apply { mkdirs() }
        val pageCopy = File(expired, "page_001.jpg").apply {
            writeBytes(ImportFixtures.image(width = 12, height = 18))
        }
        VaultSession.open(ByteArray(32) { 9 })
        VaultSession.onBackground(now - 180_000L)
        assertFalse("the key expired", VaultSession.isUnlocked())
        assertFalse("expiry wipes the whole open directory", expired.exists())
        assertFalse(pageCopy.exists())
    }
}
