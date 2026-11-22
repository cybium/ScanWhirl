package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.BatchActivity
import com.scanwhirl.app.util.ProGate
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog

/**
 * fixshare extension — the other two multi-file share paths had the same grant-model bug as
 * ToolResultActivity: Batch "share all" put only the first URI in ClipData and the library's
 * multi-document share had no ClipData at all. On Android 10+ the grant follows ClipData, so the
 * receivers could read at most the first file and the rest failed silently.
 *
 * Both are driven through their real UI entry points and pinned to the same contract as
 * ToolResultShareFixShareTest: ACTION_SEND_MULTIPLE, every URI in EXTRA_STREAM *and* ClipData in
 * the same order, read grant only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MultiShareSitesFixShareTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, true)
        // androidx FileProvider caches its path roots per authority in a static map; under
        // Robolectric every test class gets a fresh cacheDir, so a stale entry from an earlier
        // class makes getUriForFile fail with "Failed to find configured root". Reset it.
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @Suppress("DEPRECATION")
    private fun assertMultiShare(chooser: Intent, expectedType: String, expectedSize: Int) {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val wrapped = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("the chooser wraps the share intent", wrapped)
        val send = wrapped!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(expectedType, send.type)

        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
        assertEquals("every file must be in EXTRA_STREAM", expectedSize, uris.size)
        assertEquals("no duplicate URIs", expectedSize, uris.toSet().size)
        uris.forEach {
            assertEquals("content", it.scheme)
            assertEquals("${app.packageName}.fileprovider", it.authority)
            val opened = app.contentResolver.openInputStream(it)
            assertNotNull("the URI must resolve through the app's provider", opened)
            opened!!.close()
        }

        // The grant follows ClipData: it must carry every URI, in the same order.
        val clip = send.clipData
        assertNotNull("a multi share must carry ClipData", clip)
        assertEquals("ClipData must carry every URI, not only the first", expectedSize, clip!!.itemCount)
        for (i in 0 until expectedSize) assertEquals("ClipData item $i", uris[i], clip.getItemAt(i).uri)

        // Read grant only (never write) on the target and on the chooser that carries it.
        for (i in listOf(send, chooser)) {
            assertTrue("read grant on ${i.action}", i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("no write grant on ${i.action}", 0, i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ---- Batch "share all" ------------------------------------------------

    @Test
    fun `batch share all puts every result file in EXTRA_STREAM and ClipData`() {
        val ids = (0 until 2).map { DocFixtures.saved(repo, app, pages = 1, name = "Batch $it") }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            assertTrue("the action sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchActionCompress) != null
            })
            scenario.onActivity { _ ->
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.batchActionCompress).performClick()
                idle()
                val options = ShadowDialog.getLatestDialog()
                assertNotNull("compress options", options.findViewById<View>(R.id.batchTarget))
                options.findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("two copies are published", waitUntil {
                repo.listDocuments().count { it.name.endsWith(" (2)") } == 2
            })
            assertTrue("the summary opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.batchSummary) != null
            })
            idle()
            scenario.onActivity { activity ->
                // Drop anything the screen started while loading.
                while (shadowOf(activity).nextStartedActivity != null) Unit
                val summary = ShadowDialog.getLatestDialog()
                summary.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share all opens the sharesheet", chooser)
                assertMultiShare(chooser, "application/pdf", expectedSize = 2)
            }
        }
    }

    // ---- Library multi-document share -------------------------------------

    @Test
    fun `sharing two selected documents puts both PDFs in EXTRA_STREAM and ClipData`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Share one")
        DocFixtures.saved(repo, app, pages = 1, name = "Share two")
        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                val cards = (0 until list.childCount).map { list.getChildAt(it) as DocCard }
                assertEquals("two documents", 2, cards.size)
                cards[0].performLongClick()
                idle()
                cards[1].performClick()
                idle()

                // Drop anything the shell started while loading.
                while (shadowOf(activity).nextStartedActivity != null) Unit
                activity.findViewById<View>(R.id.swSelShare).performClick()
                idle()
                val chooser = shadowOf(activity).nextStartedActivity
                assertNotNull("Share opens the sharesheet", chooser)
                assertMultiShare(chooser, "application/pdf", expectedSize = 2)
            }
        }
    }
}
