package com.scanwhirl.app.ui

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.FakeMediaStore
import com.scanwhirl.app.util.Prefs
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
import org.robolectric.shadows.ShadowToast
import java.io.ByteArrayOutputStream

/**
 * H1 / H2 (BUGS_HUNT, found on the phone with 1.2.2): after "Save to Documents" the snackbar's
 * Open did nothing (no ACTION_VIEW reached the log, no app was ever asked) and its Undo left the
 * exported file on the device. Both actions re-ran the save instead ("Already saved, unchanged"),
 * which is what a tap that lands on the Save button underneath the bar does.
 *
 * These tests pin the two promises of the confirmation:
 *  - Open hands the exported file's own content URI to a viewer, with read permission, or says
 *    that no app on this phone can open it;
 *  - Undo deletes exactly the copy this save created and says "Removed from Documents".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportActionsFixResultTest {

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
        Prefs.setAutoCopyToDocuments(app, false)
        media.close()
    }

    @Test
    fun `open hands the exported file to a viewer`() {
        launchViewerAndSave("Open me") { activity, rowUri ->
            registerViewer(rowUri)
            drainStarted(activity)
            activity.findViewById<View>(R.id.snackOpen).performClick()
            idle()

            val started = shadowOf(activity).peekNextStartedActivity()
            assertNotNull("H1: Open must start a viewer, it started nothing", started)
            assertEquals("H1: Open must be an ACTION_VIEW", Intent.ACTION_VIEW, started!!.action)
            assertEquals("H1: the viewer must get the exported file", rowUri, started.data)
            assertEquals(
                "H1: the viewer needs read permission on the file",
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            assertEquals("H1: Open must not run the save again", 1, media.totalWrites())
        }
    }

    @Test
    fun `open says so when no app can open the file`() {
        launchViewerAndSave("Nothing opens this") { activity, _ ->
            drainStarted(activity)
            activity.findViewById<View>(R.id.snackOpen).performClick()
            idle()

            assertNull(
                "H1: with no viewer installed nothing may be started (a chooser is not a viewer)",
                shadowOf(activity).peekNextStartedActivity()
            )
            val toast = ShadowToast.getTextOfLatestToast().orEmpty()
            assertTrue(
                "H1: the user must be told that no app can open the export, got '$toast'",
                toast.contains("No app on this phone can open this file")
            )
            assertEquals("Open must never re-run the save", 1, media.totalWrites())
        }
    }

    @Test
    fun `undo deletes exactly the exported file and says so`() {
        // Another file the user put in Documents themselves: Undo must never touch it.
        val other = media.addRow(
            "Earlier.pdf", "earlier".toByteArray(), owner = null,
            relPath = android.os.Environment.DIRECTORY_DOCUMENTS
        )
        launchViewerAndSave("Undo me") { activity, rowUri ->
            assertEquals("both files must exist before Undo", 2, media.rows.size)
            activity.findViewById<View>(R.id.snackUndo).performClick()
            idle()

            val gone = pollUntil { media.rows[rowUri.lastPathSegment!!.toLong()] == null }
            assertTrue("H2: Undo must delete the exported row ($rowUri)", gone)
            assertNotNull("H2: Undo must not touch any other file", media.rows[other.id])
            val toast = ShadowToast.getTextOfLatestToast().orEmpty()
            assertTrue(
                "H2: Undo must confirm the removal, got '$toast'",
                toast.contains("Removed from Documents")
            )
        }
    }

    /**
     * The bar is drawn at the very bottom of an edge-to-edge screen, where the app's own bottom
     * buttons and the system's gesture area are. Its actions must therefore keep clear of the
     * system navigation inset, and the hidden stock row must not add 48 dp of dead space that
     * pushes the actions into the corner (H1/H2 on the phone).
     */
    @Test
    fun `the snackbar actions keep clear of the navigation bar`() {
        launchViewerAndSave("Inset me") { activity, _ ->
            val row = activity.findViewById<View>(R.id.snackOpen).parent as View
            val stock = activity.findViewById<View>(com.google.android.material.R.id.snackbar_text)
                ?.parent as? View
            if (stock != null && stock !== row.parent) {
                assertEquals(
                    "H1/H2: the empty stock row must not take space above the actions",
                    View.GONE,
                    stock.visibility
                )
            }
            ViewCompat.dispatchApplyWindowInsets(
                row.parent as View,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 0, 0, 96))
                    .build()
            )
            idle()
            assertTrue(
                "H1/H2: the actions must stay above the 96 px navigation inset, padding=${row.paddingBottom}",
                row.paddingBottom >= 96
            )
        }
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * Opens a document, saves it to Documents/ScanWhirl through the viewer's menu and hands the
     * save confirmation's exported row URI to [check].
     */
    private fun launchViewerAndSave(name: String, check: (android.app.Activity, Uri) -> Unit) {
        val id = seedDoc(name)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity -> shadowOf(activity).clickMenuItem(R.id.action_save_pdf) }
            assertTrue(
                "the save must create exactly one Documents/ScanWhirl row",
                pollUntil { media.scanwhirlPdfs().size == 1 }
            )
            val row = media.scanwhirlPdfs().single()
            val rowUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), row.id)
            var open: View? = null
            assertTrue(
                "the save confirmation must appear",
                pollUntil {
                    scenario.onActivity { activity -> open = activity.findViewById(R.id.snackOpen) }
                    open != null
                }
            )
            scenario.onActivity { activity -> check(activity, rowUri) }
        }
    }

    /** Pretends a PDF viewer is installed for exactly [uri]. */
    private fun registerViewer(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf") }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            view,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.example.viewer"
                    name = "com.example.viewer.PdfViewer"
                }
            }
        )
    }

    private fun drainStarted(activity: android.app.Activity) {
        val shadow = shadowOf(activity)
        while (shadow.peekNextStartedActivity() != null) shadow.getNextStartedActivity()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/fixresult_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun seedDoc(name: String, pages: Int = 1): String = runBlocking {
        val uris = (0 until pages).map { contentUri(jpegBytes()) }
        repo.saveScan(uris, null, name).id
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            310, 444, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

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
}
