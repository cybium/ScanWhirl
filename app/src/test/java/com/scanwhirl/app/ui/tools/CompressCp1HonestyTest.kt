package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
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

/**
 * CP1 defects 2, 7 and 9: a target miss or a copy that grew gets an honest header, the readable
 * floor miss is spelled out, and "under your limit" never hides "larger than the original".
 */
abstract class CompressCp1HonestyBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        media.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun docId(): String = DocFixtures.saved(repo, app, pages = 1, name = "Honest")

    private fun texts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `a missed target is titled Could not reach your size and states the readable floor`() {
        val id = docId()
        val note = app.getString(R.string.cp1_compress_floor_miss, "100 KB", "344 KB")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(id), targetBytes = 100_000,
                note = note, compressTargetMissed = true
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val shown = texts(activity.window!!.decorView)
                assertTrue("honest miss header: $shown", shown.contains(app.getString(R.string.cp1_compress_header_missed)))
                assertFalse("never titled Compressed: $shown", shown.contains(app.getString(R.string.compress_result_header)))
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertTrue("the readable floor miss is said out loud: $summary", summary.contains(note))
                assertNotNullAlsoSave(activity)
            }
        }
    }

    @Test
    fun `a copy that grew is titled Larger than the original even under the limit`() {
        val id = docId()
        val bytes = requireNotNull(repo.getDocument(id)).pdfFile.length()
        val warning = app.getString(R.string.compress_grew_warning)
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(id),
                targetBytes = bytes + 100_000, beforeBytes = 1L,
                note = warning, compressGrew = true
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val shown = texts(activity.window!!.decorView)
                assertTrue("honest grown header: $shown", shown.contains(app.getString(R.string.cp1_compress_header_grew)))
                assertFalse("never titled Compressed: $shown", shown.contains(app.getString(R.string.compress_result_header)))
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                val targetShown = com.scanwhirl.app.util.StatusLine.formatBytes(bytes + 100_000)
                val underTail = app.getString(
                    R.string.compress_result_under_limit, "", targetShown
                ).substringAfter("· ")
                assertTrue("the under-limit sentence is still there: $summary", summary.contains(underTail))
                assertTrue("and the larger-than-original warning: $summary", summary.contains(warning))
            }
        }
    }

    private fun assertNotNullAlsoSave(activity: ToolResultActivity) {
        assertTrue(
            "Undo and keep-original guidance stay on a miss",
            activity.findViewById<View>(R.id.toolResultAlsoSave) != null &&
                activity.findViewById<View>(R.id.toolResultUndo) != null
        )
    }

    @Test
    fun `an ordinary compress keeps the Compressed header`() {
        val id = docId()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertTrue(
                    "a real compression is still called Compressed",
                    texts(activity.window!!.decorView).contains(app.getString(R.string.compress_result_header))
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xhdpi")
class CompressCp1HonestyEnTest : CompressCp1HonestyBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressCp1HonestyHiTest : CompressCp1HonestyBase()
