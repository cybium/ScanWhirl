package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.util.TypedValue
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
        // A test may opt into auto-copy; never let it leak into the next method/class.
        Prefs.setAutoCopyToDocuments(app, false)
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
            // M06: an unchanged document is recognised as already saved: no
            // second write, no duplicate, and no Undo (nothing was created).
            var again: android.view.View? = null
            val settled = pollUntil {
                scenario.onActivity { activity -> again = findSnack(activity, "Already saved") }
                again != null
            }
            assertTrue("second download should report 'Already saved'", settled)
            // The provider rows are written by the save coroutine: wait until the second
            // attempt has fully settled before counting (the assertions below still fail on a
            // real duplicate).
            assertTrue(
                "the second save must settle without a write",
                pollUntil { media.scanwhirlPdfs().size == 1 && media.totalWrites() == 1 }
            )
            assertEquals(
                "Undo must not be offered when nothing was created",
                android.view.View.GONE,
                again!!.findViewById<android.view.View>(R.id.snackUndo).visibility
            )
            // M06 device finding: the message was light-on-light (colorOnSurface on
            // Material 3's inverse-surface snackbar). It must use the inverse text colour.
            scenario.onActivity { activity ->
                val tv = TypedValue()
                activity.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceInverse, tv, true
                )
                assertEquals(
                    "snackbar message uses colorOnSurfaceInverse",
                    tv.data,
                    again!!.findViewById<android.widget.TextView>(R.id.snackMessage).currentTextColor
                )
            }
        }

        val rows = media.scanwhirlPdfs()
        assertEquals(
            "T4.7: downloading an unchanged document again must not duplicate it " +
                "— got ${rows.names()}",
            1,
            rows.size
        )
        assertEquals("Hygiene.pdf", rows[0].values.getAsString("_display_name"))
        assertEquals("the existing copy is never rewritten", 1, media.totalWrites())
    }

    /**
     * M06 regression for the dangerous combination: re-saving a *changed*
     * document used to rewrite the existing row in place ("wt"), and Undo then
     * deleted that row, which destroyed the earlier export. Now the second save is a
     * new copy, the earlier file stays byte-identical, and Undo removes only the
     * new copy.
     */
    @Test
    fun `undo after a changed re-save keeps the earlier export`() {
        val id = seedDoc("Changed")
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
            }
            assertTrue(pollUntil { media.scanwhirlPdfs().size == 1 })
            val original = media.scanwhirlPdfs().single().file.readBytes()

            // The document changes (e.g. a page edit regenerated its PDF).
            repo.getDocument(id)!!.pdfFile.appendBytes("\n% edited\n".toByteArray())

            scenario.onActivity { activity ->
                shadowOf(activity).clickMenuItem(R.id.action_save_pdf)
            }
            var snack: android.view.View? = null
            assertTrue(
                "second save must say it made a new copy and kept the earlier file",
                pollUntil {
                    scenario.onActivity { activity -> snack = findSnack(activity, "earlier file was kept") }
                    snack != null
                }
            )
            assertEquals(2, media.scanwhirlPdfs().size)
            scenario.onActivity {
                snack!!.findViewById<android.view.View>(R.id.snackUndo).performClick()
            }
            assertTrue(
                "Undo removes only the new copy",
                pollUntil { media.scanwhirlPdfs().size == 1 }
            )
            val kept = media.scanwhirlPdfs().single()
            assertEquals("Changed.pdf", kept.values.getAsString("_display_name"))
            assertTrue("earlier export bytes unchanged", original.contentEquals(kept.file.readBytes()))
            // Undo forgets the removed copy's record right after deleting it (same IO task).
            assertTrue(
                "only the earlier copy stays recorded — got ${repo.exportsOf(id)}",
                pollUntil {
                    repo.exportsOf(id).map { android.content.ContentUris.parseId(it) } == listOf(kept.id)
                }
            )
        }
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
    fun `a changed save never writes the existing row`() {
        val first = DownloadSaver.savePdf(app, "original".toByteArray(), "Doc.pdf")
        assertEquals("Doc.pdf", first)
        // Even a copy written by an older install that cannot be rewritten is fine:
        // M06 never opens an existing export for writing at all.
        media.rows.values.first().writable = false

        val saved = DownloadSaver.savePdfFile(app, "replacement".toByteArray(), "Doc.pdf")
        assertEquals("M06: changed content becomes a new copy", "Doc (2).pdf", saved.name)
        assertTrue(saved.keptEarlier)

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

    /** The saved-snackbar content view whose message contains [text], if shown. */
    private fun findSnack(activity: android.app.Activity, text: String): android.view.View? {
        fun walk(v: android.view.View): android.view.View? {
            if (v.id == R.id.snackMessage && v is android.widget.TextView &&
                v.text.toString().contains(text)
            ) {
                return v.parent as android.view.View
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        return walk(activity.window.decorView)
    }

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
