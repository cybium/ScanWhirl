package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * fixshare — a multi-file result (batch / split / exported pages) must share EVERY file.
 *
 * The Android 10+ URI-grant model (and the sharesheet) follows the intent's ClipData, so a
 * ClipData that carries only the first URI leaves the other files unreadable for most
 * receivers: a batch of three PDFs shared to WhatsApp arrived as one file, silently.
 * The contract pinned here: ACTION_SEND_MULTIPLE, every URI in EXTRA_STREAM *and* in ClipData
 * in the same order, read grant only, and the single-file path untouched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolResultShareFixShareTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /**
     * androidx FileProvider caches its path roots per authority in a static map; under
     * Robolectric every test gets a fresh cacheDir, so a stale entry from an earlier test
     * makes getUriForFile fail with "Failed to find configured root". Reset it.
     */
    @Before
    fun resetFileProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun page(name: String): File =
        File(app.cacheDir, "documents/fixshare/$name").apply {
            parentFile?.mkdirs()
            writeBytes(ImportFixtures.image(width = 60, height = 40))
        }

    /** Clicks Share on a result made of [files] and returns (chooser, target intent). */
    private fun share(files: List<File>, mime: String): Pair<Intent, Intent> {
        val intent = ToolResultActivity.intent(app, "pdf_to_images", files = files, mime = mime)
        var chooser: Intent? = null
        var send: Intent? = null
        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultShare).performClick()
                idle()
                chooser = shadowOf(activity).nextStartedActivity
                @Suppress("DEPRECATION")
                send = chooser?.getParcelableExtra(Intent.EXTRA_INTENT)
            }
        }
        assertNotNull("Share opens the sharesheet", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
        assertNotNull("the chooser wraps the share intent", send)
        return chooser!! to send!!
    }

    @Suppress("DEPRECATION")
    private fun streams(send: Intent): List<Uri> =
        send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()

    @Test
    fun `three files put every Uri in EXTRA_STREAM and ClipData with read grant only`() {
        val files = listOf(page("batch_1.jpg"), page("batch_2.jpg"), page("batch_3.jpg"))
        val (chooser, send) = share(files, "image/jpeg")

        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("image/jpeg", send.type)

        val uris = streams(send)
        assertEquals("every file must be in EXTRA_STREAM", 3, uris.size)
        uris.forEach { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("${app.packageName}.fileprovider", uri.authority)
        }
        assertEquals("URIs must keep the result's order", 3, uris.toSet().size)

        // The grant follows ClipData: it must carry all three, in the same order.
        val clip = send.clipData
        assertNotNull("a multi share must carry ClipData", clip)
        assertEquals("ClipData must carry every URI, not only the first", 3, clip!!.itemCount)
        for (i in 0 until 3) assertEquals("ClipData item $i", uris[i], clip.getItemAt(i).uri)

        // Read grant only (never write) on the target and on the chooser that carries it.
        for (i in listOf(send, chooser)) {
            assertTrue("read grant on ${i.action}", i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("no write grant on ${i.action}", 0, i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        // Each URI resolves to its own complete bytes through the app's provider.
        uris.forEachIndexed { i, uri ->
            assertArrayEquals(
                "file $i must be the one at its URI",
                files[i].readBytes(),
                app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            )
        }
    }

    @Test
    fun `single file share is still a plain ACTION_SEND with one Uri`() {
        val (chooser, send) = share(listOf(page("single.jpg")), "image/jpeg")

        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("single share keeps EXTRA_STREAM as one Uri", uri)
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        for (i in listOf(send, chooser)) {
            assertTrue("read grant on ${i.action}", i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("no write grant on ${i.action}", 0, i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
