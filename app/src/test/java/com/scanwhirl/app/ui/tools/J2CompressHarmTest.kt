package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.util.StatusLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * J2 — each test is named for the user harm it prevents, not for a round or a model.
 * The five harms here are the ones the approved spec calls out for Compress.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class J2CompressHarmTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ToolSetupActivity.compressBeforeRunForTest = null
    }

    @After
    fun tearDown() {
        ToolSetupActivity.compressBeforeRunForTest = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun ready(scenario: ActivityScenario<ToolSetupActivity>, block: (ToolSetupActivity) -> Unit) {
        scenario.onActivity { activity ->
            assertTrue(
                "the library is listed before Compress is usable",
                pollUntil { activity.findViewById<View>(R.id.toolSetupDocName)?.isShown == true }
            )
            block(activity)
        }
    }

    private fun openSetup(block: (ActivityScenario<ToolSetupActivity>) -> Unit) {
        ActivityScenario.launch<ToolSetupActivity>(
            ToolSetupActivity.intent(app, "compress")
        ).use(block)
    }

    // ---- 1. two aims, but never both on one page ----------------------------

    @Test
    fun `both modes never show at once`() {
        DocFixtures.saved(repo, app, pages = 2, name = "J2 Physics notes")
        openSetup { scenario ->
            ready(scenario) { activity ->
                val quality = activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_MODE_QUALITY_TAG)
                val size = activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_MODE_SIZE_TAG)
                assertNotNull("the quality aim is offered", quality)
                assertNotNull("the size aim is offered", size)
                assertTrue("Balanced starts as the chosen aim", quality.isSelected)
                assertFalse("the size aim is not shown with it", size.isSelected)
                val qualityBlock = activity.window.decorView
                    .findViewWithTag<View>(ToolSetupActivity.COMPRESS_QUALITY_BLOCK_TAG)
                val sizeBlock = activity.window.decorView
                    .findViewWithTag<View>(ToolSetupActivity.COMPRESS_SIZE_BLOCK_TAG)
                assertNotNull("the quality panel exists", qualityBlock)
                assertNotNull("the size panel exists", sizeBlock)
                assertEquals(View.VISIBLE, qualityBlock.visibility)
                assertEquals(View.GONE, sizeBlock.visibility)

                size.performClick()
                idle()
                assertEquals(View.GONE, qualityBlock.visibility)
                assertEquals(View.VISIBLE, sizeBlock.visibility)
                assertTrue("the size aim is now selected", size.isSelected)
                assertFalse("the quality aim is deselected", quality.isSelected)

                // Each aim keeps its own choice across a switch.
                activity.findViewById<EditText>(R.id.toolSetupTarget).setText("1.5")
                activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_UNIT_MB_TAG)!!.performClick()
                quality.performClick()
                idle()
                size.performClick()
                idle()
                assertEquals("1.5", activity.findViewById<EditText>(R.id.toolSetupTarget).text.toString())
                assertTrue(
                    "the unit survives the switch",
                    activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_UNIT_MB_TAG)!!.isSelected
                )
            }
        }
    }

    // ---- 2. an estimate is an estimate -------------------------------------

    @Test
    fun `estimate never presented as guaranteed`() {
        DocFixtures.saved(repo, app, pages = 2, name = "J2 Physics notes")
        openSetup { scenario ->
            ready(scenario) { activity ->
                val sizes = mutableListOf<TextView>()
                fun walk(v: View) {
                    if (v.id == R.id.toolSetupLevelSize && v is TextView) sizes += v
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(activity.findViewById(R.id.toolSetupLevels))
                assertEquals("one estimate slot per labelled level", 4, sizes.size)
                val pending = app.getString(R.string.ux130_l3_compress_estimate_pending)
                assertTrue(
                    "while measuring, an honest placeholder sits in place: $sizes",
                    pollUntil { sizes.all { it.text.toString() == pending || it.text.toString().contains("about") } }
                )
                assertTrue(
                    "every filled-in estimate keeps the word about: ${sizes.map { it.text }}",
                    pollUntil {
                        sizes.all {
                            val text = it.text.toString()
                            text == pending || text.startsWith("about ")
                        }
                    }
                )
                assertTrue(
                    "the estimates actually arrive",
                    pollUntil { sizes.all { it.text.toString().startsWith("about ") } }
                )
            }
        }
    }

    // ---- 3. a missed target must not look like a success --------------------

    @Test
    fun `a missed target never claims success`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Miss me")
        val bytes = requireNotNull(repo.getDocument(id)).pdfFile.length()
        assertTrue("the fixture is bigger than the cap", bytes > 1_000L)
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(id), targetBytes = 1_000L,
                beforeBytes = bytes + 10L, compressTargetMissed = true,
                outcomeClass = "TARGET_MISSED",
                note = app.getString(R.string.cp1_compress_floor_miss, "1 KB", "2 KB")
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(
                    "a miss is titled honestly",
                    app.getString(R.string.cp1_compress_header_missed),
                    activity.findViewById<TextView>(R.id.rsTitle).text.toString()
                )
                val hero = activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                assertTrue(
                    "the closest achieved size is shown: $hero",
                    hero.contains(StatusLine.formatBytes(bytes))
                )
                val successHeadline = activity.window.decorView.findViewWithTag<View>("result-headline")
                assertNotNull("the headline slot exists", successHeadline)
                assertEquals("no success headline is shown", View.GONE, successHeadline.visibility)
                assertEquals(
                    "Share stays the dock primary",
                    com.scanwhirl.app.ui.kit.SwStyle.color(activity, R.color.sw_disc_ink),
                    activity.findViewById<TextView>(R.id.toolResultShare).currentTextColor
                )
            }
        }
    }

    // ---- 4. no gain means no duplicate -------------------------------------

    @Test
    fun `no gain creates no duplicate`() {
        val before = repo.listDocuments().size
        V2tResultReceiptStore(app).save(
            V2tResultReceiptStore.Receipt(
                toolId = "compress", docIds = emptyList(), files = emptyList(),
                mime = "application/pdf", note = null, targetBytes = null, beforeBytes = 1_000L,
                outputIdentity = null, receiptSourceId = "source", sourceKept = true,
                chosenLocationName = null, documentsExistingName = null, undone = false,
                savedAt = System.currentTimeMillis(), outcomeClass = "NOT_SMALLER"
            )
        )
        ActivityScenario.launch<ToolResultActivity>(ToolResultActivity.intentLastResult(app)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(
                    "the no-gain result is named honestly",
                    app.getString(R.string.rs_not_smaller_title),
                    activity.findViewById<TextView>(R.id.rsTitle).text.toString()
                )
                assertNull(
                    "no removable copy exists on a no-gain result",
                    activity.findViewById<View>(R.id.toolResultUndo)
                )
                assertFalse(
                    "no saved copy is named",
                    activity.findViewById<TextView>(R.id.toolResultName).isVisible
                )
            }
        }
        assertEquals("no duplicate was published", before, repo.listDocuments().size)
    }

    // ---- 5. cancel publishes nothing ---------------------------------------

    @Test(timeout = 60_000)
    fun `cancel creates nothing`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ToolSetupActivity.compressBeforeRunForTest = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            Unit
        }
        DocFixtures.saved(repo, app, pages = 2, name = "J2 Physics notes")
        val before = repo.listDocuments().size
        openSetup { scenario ->
            var started: Intent? = null
            scenario.onActivity { activity ->
                assertTrue(
                    "the library is listed before the run",
                    pollUntil { activity.findViewById<View>(R.id.toolSetupDocName)?.isShown == true }
                )
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
            }
            assertTrue("the engine was reached", entered.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val cancel = activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_CANCEL_TAG)
                assertNotNull("one safe Cancel is offered while staging", cancel)
                cancel.performClick()
                idle()
            }
            release.countDown()
            Thread.sleep(80)
            idle()
            scenario.onActivity { activity ->
                started = shadowOf(activity).peekNextStartedActivity()
            }
            assertNull("cancel opens no result", started)
        }
        assertEquals("cancel publishes no document", before, repo.listDocuments().size)
    }

    // ---- 6. a bad custom number blocks the start ----------------------------

    @Test
    fun `invalid custom size blocks start`() {
        DocFixtures.saved(repo, app, pages = 2, name = "J2 Physics notes")
        val before = repo.listDocuments().size
        openSetup { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the library is listed before the run",
                    pollUntil { activity.findViewById<View>(R.id.toolSetupDocName)?.isShown == true }
                )
                activity.window.decorView.findViewWithTag<View>(ToolSetupActivity.COMPRESS_MODE_SIZE_TAG)!!.performClick()
                idle()
                activity.findViewById<EditText>(R.id.toolSetupTarget).setText("not a size")
                idle()
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
                idle()
                val error = activity.findViewById<TextView>(R.id.toolSetupError)
                assertTrue("one clear error is shown", error.isVisible)
                assertEquals(
                    "the error names the field's problem",
                    app.getString(R.string.ux130_l3_compress_invalid_custom),
                    error.text.toString()
                )
                assertNull("nothing was started", shadowOf(activity).peekNextStartedActivity())
            }
        }
        assertEquals("no document was created", before, repo.listDocuments().size)
    }
}
