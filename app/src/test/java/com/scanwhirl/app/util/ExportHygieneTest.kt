package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * T4.7 export hygiene (N10, complaint #13): no silent copy into Documents/ScanWhirl,
 * replace-not-duplicate when the same name is saved again, Open/Undo Snackbar.
 *
 * Uses the shared [FakeMediaStore] so every row the app writes to
 * Documents/ScanWhirl is observable (and deletable) from the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportHygieneTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            // attachInfo gives the provider a context + uid so the real
            // ContentResolver stream path (Transport.openAssetFile) is allowed.
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
    // T4.7 tests
    // ------------------------------------------------------------------

    @Test
    fun `scan save does not copy into Documents when auto copy is off`() {
        assertFalse(
            "auto copy into Documents/ScanWhirl must be off by default (T4.7/N10)",
            Prefs.autoCopyToDocuments(app)
        )
        val staging = File(app.cacheDir, "hygiene_review_${System.nanoTime()}")
            .apply { mkdirs() }
        File(staging, "page_000.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Hygiene doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            // The copy (if any) runs before the viewer opens, so once the
            // DocumentViewer intent is seen the save flow is complete.
            var opened: android.content.Intent? = null
            val done = pollUntil {
                scenario.onActivity { activity ->
                    opened = shadowOf(activity).peekNextStartedActivity()
                }
                opened != null
            }
            assertTrue("save should open the document viewer", done)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(
            "T4.7/N10: auto copy is off, so no Documents/ScanWhirl row may be " +
                "created — got ${media.scanwhirlPdfs().names()}",
            media.scanwhirlPdfs().isEmpty()
        )
    }

    @Test
    fun `downloading twice keeps a single row`() {
        val id = seedDoc("Hygiene")
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
            }
            val first = pollUntil { media.scanwhirlPdfs().size == 1 }
            assertTrue("first download should create exactly one row", first)

            // The save confirmation must offer both actions (T4.7).
            var snackOpen: android.view.View? = null
            var snackUndo: android.view.View? = null
            val shown = pollUntil {
                scenario.onActivity { activity ->
                    snackOpen = activity.findViewById(R.id.snackOpen)
                    snackUndo = activity.findViewById(R.id.snackUndo)
                }
                snackOpen != null && snackUndo != null
            }
            assertTrue("Snackbar must show Open and Undo (T4.7)", shown)

            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
            }
            // After the fix the second save rewrites the same row (writes >= 2);
            // today it creates a second row "Hygiene (2).pdf" (rows >= 2).
            val settled = pollUntil {
                media.scanwhirlPdfs().size >= 2 || media.totalWrites() >= 2
            }
            assertTrue("second download should complete", settled)
        }

        val rows = media.scanwhirlPdfs()
        assertEquals(
            "T4.7: downloading again must replace the file, not duplicate it " +
                "— got ${rows.names()}",
            1,
            rows.size
        )
        assertEquals("Hygiene.pdf", rows[0].values.getAsString("_display_name"))
    }

    @Test
    fun `auto copy on saves exactly one row`() {
        Prefs.setAutoCopyToDocuments(app, true)

        val staging = File(app.cacheDir, "hygiene_optin_${System.nanoTime()}")
            .apply { mkdirs() }
        File(staging, "page_000.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Opted in")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            var opened: android.content.Intent? = null
            val done = pollUntil {
                scenario.onActivity { activity ->
                    opened = shadowOf(activity).peekNextStartedActivity()
                }
                opened != null
            }
            assertTrue("save should open the document viewer", done)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val rows = media.scanwhirlPdfs()
        assertEquals(
            "auto copy ON must place exactly one row — got ${rows.names()}",
            1,
            rows.size
        )
        assertEquals("Opted in.pdf", rows[0].values.getAsString("_display_name"))
    }

    @Test
    fun `undo on the snackbar removes the saved row`() {
        val id = seedDoc("Undo me")
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
            }
            assertTrue(
                "first download should create one row",
                pollUntil { media.scanwhirlPdfs().size == 1 }
            )
            var snackUndo: android.view.View? = null
            val shown = pollUntil {
                scenario.onActivity { activity ->
                    snackUndo = activity.findViewById(R.id.snackUndo)
                }
                snackUndo != null
            }
            assertTrue("snackbar with Undo should appear", shown)

            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.snackUndo).performClick()
            }
            assertTrue(
                "Undo must delete the exported row (T4.7)",
                pollUntil { media.scanwhirlPdfs().isEmpty() }
            )
        }
    }

    @Test
    fun `an unwritable existing row falls back to a unique name`() {
        val first = DownloadSaver.savePdf(app, "original".toByteArray(), "Doc.pdf")
        assertEquals("Doc.pdf", first)
        // Simulate a copy written by an older install that cannot be rewritten.
        media.rows.values.first().writable = false

        val saved = DownloadSaver.savePdfFile(app, "replacement".toByteArray(), "Doc.pdf")
        assertEquals("T4.7: fallback must create a unique name", "Doc (2).pdf", saved.name)

        val rows = media.scanwhirlPdfs()
        assertEquals("old row stays, new row is added — got ${rows.names()}", 2, rows.size)
        assertEquals(
            "original",
            String(rows.first {
                it.values.getAsString("_display_name") == "Doc.pdf"
            }.file.readBytes())
        )
        assertEquals(
            "replacement",
            String(rows.first {
                it.values.getAsString("_display_name") == "Doc (2).pdf"
            }.file.readBytes())
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun List<FakeMediaStore.Row>.names(): String =
        joinToString { it.values.getAsString("_display_name") }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/hygiene_${System.nanoTime()}.jpg")
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
}
