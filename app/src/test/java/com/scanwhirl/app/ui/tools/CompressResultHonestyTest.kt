package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
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
import com.scanwhirl.app.tools.PdfAFlow
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration

/**
 * UI-B3 (#8, #9-now, #12): the Compress result must lead with the measured size and an honest
 * target status, keep the before size, and offer the destinations in the agreed order.
 *
 * On the base build the result opened with "Done", a big white preview and a page/size line, and
 * a reachable target was never said out loud ("Under your … limit" did not exist at all); the
 * system save action was called "Save as…" in the main row.
 */
abstract class CompressResultHonestyBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
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

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun doc(name: String = "Receipt"): String =
        DocFixtures.saved(repo, app, pages = 1, name = name)

    private fun bytesOf(id: String): Long =
        repo.getDocument(id)?.pdfFile?.let { if (it.exists()) it.length() else 0L } ?: 0L

    private fun summaryLines(activity: android.app.Activity): List<String> =
        activity.findViewById<TextView>(R.id.toolResultSummary).text.toString().split("\n")

    private fun fmt(bytes: Long): String = StatusLine.formatBytes(bytes)

    /** The "Above your … limit" half of the line, without the size before it. */
    private fun overTail(): String =
        app.getString(R.string.compress_result_over_limit, "1 B", "2 B").substringAfter("· ")

    /** The "Under your … limit" half of the line, without the size before it. */
    private fun underTail(): String =
        app.getString(R.string.compress_result_under_limit, "1 B", "2 B").substringAfter("· ")

    // ---- #8: measured size and target status come first -------------------

    @Test
    fun `a hit leads with the measured size and Under your limit`() {
        val id = doc()
        val bytes = bytesOf(id)
        val target = bytes + 4096
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(id),
                targetBytes = target, beforeBytes = bytes + 1024
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val lines = summaryLines(activity)
                assertEquals(
                    "B3: the first line is the measured size and the target status",
                    app.getString(
                        R.string.compress_result_under_limit,
                        fmt(bytes),
                        fmt(target)
                    ),
                    lines.first()
                )
                assertEquals(
                    "B3: the before size follows on its own line",
                    app.getString(R.string.compress_result_before, fmt(bytes + 1024)),
                    lines[1]
                )
            }
        }
    }

    /**
     * W3: equality is its own honest state. The base build folded measured == target into
     * "Under your … limit"; the winner spec asks for "Exactly at the selected limit", because a
     * file of exactly 100 KB is not under a 100 KB target. This test previously pinned the
     * under-limit wording at the boundary; it is adjusted, not weakened, to the superseding spec.
     */
    @Test
    fun `an exact boundary is said as exactly at the limit`() {
        val id = doc()
        val bytes = bytesOf(id)
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = bytes)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val first = summaryLines(activity).first()
                assertEquals(
                    "measured == target says exactly at the limit",
                    app.getString(R.string.compress_result_at_limit, fmt(bytes), fmt(bytes)),
                    first
                )
                assertFalse(
                    "B3: no above-limit line at the boundary: $first",
                    first.contains(overTail())
                )
                assertFalse(
                    "W3: equality is not called under the limit: $first",
                    first.contains(underTail())
                )
            }
        }
    }

    /**
     * W3: exact bytes decide below/equal/above, but the displayed size must never contradict
     * that verdict. 100 KB + 1 byte renders as "100 KB" with whole-KB rounding, which would read
     * "100 KB · Above your 100 KB limit". The exact byte count is added in that case.
     */
    @Test
    fun `a rounded size never contradicts the above-limit verdict`() {
        val id = doc("Boundary")
        val target = 100L * 1024L
        val measured = target + 1
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        pdf.writeBytes(ByteArray(measured.toInt()))
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = target)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val first = summaryLines(activity).first()
                val measuredShown = app.getString(
                    R.string.w3_size_with_exact_bytes, fmt(measured), measured
                )
                assertTrue(
                    "W3: the above-limit line carries the exact byte count: $first",
                    first.startsWith(measuredShown)
                )
                assertEquals(
                    "W3: and still states the honest miss",
                    app.getString(R.string.compress_result_over_limit, measuredShown, fmt(target)),
                    first
                )
            }
        }
    }

    @Test
    fun `a rounded size never contradicts the under-limit verdict`() {
        val id = doc("Boundary low")
        val target = 100L * 1024L
        val measured = target - 1
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        pdf.writeBytes(ByteArray(measured.toInt()))
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = target)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val first = summaryLines(activity).first()
                val measuredShown = app.getString(
                    R.string.w3_size_with_exact_bytes, fmt(measured), measured
                )
                assertTrue(
                    "W3: the under-limit line carries the exact byte count: $first",
                    first.startsWith(measuredShown)
                )
                assertEquals(
                    "W3: and still states the honest hit",
                    app.getString(R.string.compress_result_under_limit, measuredShown, fmt(target)),
                    first
                )
            }
        }
    }

    @Test
    fun `a miss leads with the measured size and Above your limit`() {
        val id = doc()
        val bytes = bytesOf(id)
        assertTrue("the fixture is larger than 1 KB", bytes > 1024)
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = 1024)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val first = summaryLines(activity).first()
                assertEquals(
                    "B3: the miss is said first, in the measured numbers",
                    app.getString(R.string.compress_result_over_limit, fmt(bytes), fmt(1024)),
                    first
                )
            }
        }
    }

    @Test
    fun `no target means no status line`() {
        val id = doc()
        val bytes = bytesOf(id)
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val lines = summaryLines(activity)
                assertEquals(
                    "B3: without a target the result just states the measured size",
                    app.getString(R.string.compress_result_size, fmt(bytes)),
                    lines.first()
                )
                val text = lines.joinToString("\n")
                assertFalse(
                    "no under-limit claim without a target: $text",
                    text.contains(underTail())
                )
                assertFalse(
                    "no above-limit claim without a target: $text",
                    text.contains(overTail())
                )
            }
        }
    }

    @Test
    fun `an unmeasured size is never reported as success`() {
        val id = doc("Unmeasured")
        // The pages stay; the produced PDF is gone, so nothing can be measured.
        repo.getDocument(id)!!.pdfFile.delete()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = 100_000)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val first = summaryLines(activity).first()
                assertEquals(
                    "B3: no measurement, no claim",
                    app.getString(R.string.compress_result_size_unknown),
                    first
                )
                assertFalse("no under-limit claim: $first", first.contains(underTail()))
                assertFalse("no above-limit claim: $first", first.contains(overTail()))
            }
        }
    }

    // ---- #9-now: the original is named, Undo names what it removes --------

    @Test
    fun `a copy-making compress says the original stayed in the library`() {
        val id = doc()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), sourceKept = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val text = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertTrue(
                    "B3: the result says where the original is: $text",
                    text.contains(app.getString(R.string.compress_result_source_kept))
                )
            }
        }
    }

    @Test
    fun `Undo names the copy it removes`() {
        val id = doc("Undo me")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val undo = activity.findViewById<TextView>(R.id.toolResultUndo)
                assertEquals(
                    "B3: the Undo action says what it removes",
                    app.getString(R.string.compress_undo),
                    undo.text.toString()
                )
                undo.performClick()
            }
            assertTrue("the copy is removed", pollUntil { repo.getDocument(id) == null })
            assertTrue(
                "the confirmation names the removed copy",
                pollUntil {
                    ShadowToast.getTextOfLatestToast().orEmpty()
                        .contains(app.getString(R.string.compress_undo_removed))
                }
            )
        }
    }

    // ---- #12: destinations ------------------------------------------------

    @Test
    fun `a usable miss still offers Also save in Documents`() {
        val id = doc()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), targetBytes = 1024)
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val also = activity.findViewById<TextView>(R.id.toolResultAlsoSave)
                assertNotNull("B3: the miss keeps the save offer", also)
                assertTrue("and it is visible", also.isShown)
                val open = activity.findViewById<TextView>(R.id.toolResultOpen)
                assertEquals(
                    "B3: Open is the filled action",
                    SwStyle.color(activity, R.color.sw_disc_ink),
                    open.currentTextColor
                )
                val share = activity.findViewById<TextView>(R.id.toolResultShare)
                assertEquals(
                    "B3: Share is the secondary action",
                    SwStyle.color(activity, R.color.text_hologram_hi),
                    share.currentTextColor
                )
            }
        }
    }

    @Test
    fun `the system save action is Choose another location under More options`() {
        val id = doc()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val panel = requireNotNull(activity.findViewById<View>(R.id.toolResultMorePanel)) {
                    "the extra destinations live in one More options area"
                }
                assertEquals("collapsed until asked for", View.GONE, panel.visibility)
                val choose = activity.findViewById<TextView>(R.id.toolResultSave)
                assertEquals(
                    "B3: the picker action is named for what it does",
                    app.getString(R.string.tools_result_choose_location),
                    choose.text.toString()
                )
                val more = requireNotNull(activity.findViewById<View>(R.id.toolResultMore)) {
                    "a More options control is offered"
                }
                assertEquals(View.VISIBLE, more.visibility)
                more.performClick()
                idle()
                assertEquals(View.VISIBLE, panel.visibility)
                assertEquals(View.VISIBLE, choose.visibility)
                more.performClick()
                idle()
                assertEquals(View.GONE, panel.visibility)
            }
        }
    }

    @Test
    fun `a PDF-A result never claims the file is in the library`() {
        val sourceId = doc("Archive me")
        val pdfa = runBlocking { PdfAFlow.exportForResult(app, repo, sourceId) }
        ActivityScenario.launch<ToolResultActivity>(PdfAFlow.resultIntent(app, pdfa)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                val shown = visibleTexts(activity.window!!.decorView)
                val offenders = shown.filter { it.contains("library", ignoreCase = true) }
                assertTrue(
                    "B3: an exported file is never described as a library document: $offenders",
                    offenders.isEmpty()
                )
                assertNotNull(
                    "the export keeps Also save in Documents",
                    activity.findViewById<View>(R.id.toolResultAlsoSave)
                )
                assertEquals(
                    "an export has no library entry to show",
                    View.GONE,
                    activity.findViewById<View>(R.id.toolResultLibrary).visibility
                )
            }
        }
    }

    @Test
    fun `a cancelled picker never reports a save`() {
        val id = doc()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val more = activity.findViewById<View>(R.id.toolResultMore)
                more.performClick()
                idle()
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                // The person backs out of the system picker: nothing may be claimed.
                val forResult = shadowOf(activity).nextStartedActivityForResult
                assertNotNull("the picker opens", forResult)
                shadowOf(activity).receiveResult(
                    forResult.intent,
                    android.app.Activity.RESULT_CANCELED,
                    null
                )
                idleFor(500)
                val toast = ShadowToast.getTextOfLatestToast()
                assertTrue(
                    "B3: a cancelled picker says nothing was saved: $toast",
                    toast == null || !toast.startsWith(app.getString(R.string.tools_result_saved, "").take(5))
                )
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Texts the person can actually see (the More options panel is collapsed by default). */
    private fun visibleTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompressResultHonestyEnTest : CompressResultHonestyBase()

/** The same phone in Hindi: same order, everyday words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressResultHonestyHiTest : CompressResultHonestyBase()

/**
 * The Hindi lines themselves, pinned (UI_PLAN 0b: neutral size copy such as
 * "फ़ाइल अब 544 KB" / "पहले: 824 KB").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressResultHindiWordingTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the Hindi lines read like everyday speech`() {
        assertEquals("फ़ाइल अब 544 KB", app.getString(R.string.compress_result_size, "544 KB"))
        assertEquals("पहले: 824 KB", app.getString(R.string.compress_result_before, "824 KB"))
        assertEquals(
            "544 KB · आपकी 100 KB सीमा के भीतर",
            app.getString(R.string.compress_result_under_limit, "544 KB", "100 KB")
        )
        assertEquals(
            "544 KB · आपकी 100 KB सीमा से ऊपर",
            app.getString(R.string.compress_result_over_limit, "544 KB", "100 KB")
        )
        // W3: equality is its own state, not "under".
        assertEquals(
            "544 KB · ठीक आपकी 100 KB सीमा पर",
            app.getString(R.string.compress_result_at_limit, "544 KB", "100 KB")
        )
        assertEquals(
            "100 KB (102401 बाइट) · आपकी 100 KB सीमा से ऊपर",
            app.getString(
                R.string.compress_result_over_limit,
                app.getString(R.string.w3_size_with_exact_bytes, "100 KB", 102401),
                "100 KB"
            )
        )
        assertEquals(
            "मूल कागज़ात में सुरक्षित है।",
            app.getString(R.string.compress_result_source_kept)
        )
        assertEquals(
            "यह छोटी की गई प्रति हटा दी गई।",
            app.getString(R.string.compress_undo_removed)
        )
        assertEquals(
            "यह छोटी की गई प्रति और उसकी Documents प्रति हटा दी गई।",
            app.getString(R.string.compress_undo_removed_with_copy)
        )
        assertEquals("गुणवत्ता चुनें", app.getString(R.string.compress_mode_quality))
        assertEquals("फ़ाइल का आकार तय करें", app.getString(R.string.compress_mode_target))
        assertEquals("छोटा करें", app.getString(R.string.compress_action))
    }
}
