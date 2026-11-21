package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.nio.file.Files

/**
 * C2 item 1: an EXTRA_PATH is caller-supplied, so the reader may only open a file that
 * canonically lives inside this app's own documents (filesDir) or cache (cacheDir).
 * `../` traversal and symlinks that escape must be refused with an honest error.
 *
 * RED on base 282630d1: the helper does not exist and the reader opens an outside path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfViewerPathSafetyTest {

    private lateinit var app: Application
    private lateinit var originalFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private var opens = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        originalFactory = PdfViewerActivity.pagesFactory
        opens = 0
        PdfViewerActivity.pagesFactory = {
            opens++
            object : PdfViewerActivity.PdfPages {
                override val pageCount = 1
                override fun render(index: Int, maxDim: Int) =
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                override fun close() = Unit
            }
        }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = originalFactory
    }

    @Test
    fun `files inside documents and cache are accepted`() {
        assertTrue(
            "a saved scan lives in filesDir/documents",
            PdfViewerActivity.isPathInsideAppDirs(
                File(File(app.filesDir, "documents"), "abc/scan.pdf").path,
                app.filesDir, app.cacheDir
            )
        )
        assertTrue(
            "a decrypted Vault copy lives in cacheDir/vault_open",
            PdfViewerActivity.isPathInsideAppDirs(
                File(File(app.cacheDir, "vault_open"), "abc/scan.pdf").path,
                app.filesDir, app.cacheDir
            )
        )
    }

    @Test
    fun `traversal and absolute outside paths are rejected`() {
        assertFalse(
            "../ escapes the app directory",
            PdfViewerActivity.isPathInsideAppDirs(
                File(app.cacheDir, "../outside.pdf").path, app.filesDir, app.cacheDir
            )
        )
        assertFalse(
            "a nested traversal escapes too",
            PdfViewerActivity.isPathInsideAppDirs(
                File(File(app.cacheDir, "vault_open/abc"), "../../../outside.pdf").path,
                app.filesDir, app.cacheDir
            )
        )
        val outside = File.createTempFile("c2harden-outside", ".pdf")
        try {
            assertFalse(
                "an arbitrary absolute path is not the app's own file",
                PdfViewerActivity.isPathInsideAppDirs(outside.absolutePath, app.filesDir, app.cacheDir)
            )
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a symlink inside cache that escapes is rejected`() {
        val outside = File.createTempFile("c2harden-target", ".pdf")
        val link = File(app.cacheDir, "c2harden-link.pdf")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertFalse(
                "canonical containment follows the symlink to its target",
                PdfViewerActivity.isPathInsideAppDirs(link.path, app.filesDir, app.cacheDir)
            )
        } finally {
            link.delete()
            outside.delete()
        }
    }

    @Test
    fun `an outside EXTRA_PATH shows an honest error finishes and never opens the file`() {
        val outside = File.createTempFile("c2harden-viewer", ".pdf").apply {
            writeBytes("not a real pdf, but base still opens it".toByteArray())
        }
        try {
            val controller = Robolectric.buildActivity(
                PdfViewerActivity::class.java,
                PdfViewerActivity.fileIntent(app, outside.absolutePath, "Outside")
            )
            controller.setup()
            val activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()

            assertTrue("the reader refuses and finishes", activity.isFinishing)
            assertEquals("the outside file is never opened", 0, opens)
            assertNotNull("an honest error is shown", ShadowToast.getLatestToast())
            controller.destroy()
        } finally {
            outside.delete()
        }
    }
}
