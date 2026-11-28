package com.scanwhirl.app.v2t

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressionPresentation
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
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

/** T2 — free decimal-KB quick chips and verified-output secondary actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V2t_T2_QuickTargetsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
    }

    @After
    fun tearDown() = Faults.resetAll()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(25L)
        }
        idle()
    }

    @Test
    fun `EN and HI carry quick target and secondary action wording`() {
        val en = V2tResources.en()
        val hi = V2tResources.hi()
        assertEquals(en.keys.toSet(), hi.keys.toSet())
        assertEquals("20 KB", en["v2t_size_20"])
        assertEquals("50 KB", en["v2t_size_50"])
        assertEquals("100 KB", en["v2t_size_100"])
        assertEquals("200 KB", en["v2t_size_200"])
        assertEquals("Compress", en["v2t_compress_this_copy"])
        assertEquals("Protect", en["v2t_protect_this_copy"])
        assertEquals("छोटा करें", hi["v2t_compress_this_copy"])
        assertEquals("सुरक्षित करें", hi["v2t_protect_this_copy"])
    }

    /**
     * Deliberately updated for J2: the approved spec replaces the legacy 20/50/100/200 KB
     * quick chips on the Tools-hub Compress setup with 200 KB / 500 KB / 1 MB / 2 MB decimal
     * targets. The legacy table still drives the viewer-dock sheet and Resize, so those source
     * checks stay; only the setup now reads the new J2 table. No assertion was weakened: the
     * decimal contract is pinned on BOTH tables.
     */
    @Test
    fun `one shared decimal KB table drives the legacy surfaces and J2 drives the setup`() {
        assertEquals(listOf(20, 50, 100, 200), CompressionPresentation.quickTargetKb)
        assertEquals(
            listOf(200_000L, 500_000L, 1_000_000L, 2_000_000L),
            CompressionPresentation.j2TargetBytes
        )
        val compress = java.io.File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt").readText()
        val setup = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt").readText()
        val resize = java.io.File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()
        assertTrue(compress.contains("CompressionPresentation.quickTargetKb"))
        assertTrue(setup.contains("CompressionPresentation.j2TargetBytes"))
        assertTrue(resize.contains("CompressionPresentation.quickTargetKb"))
    }

    /**
     * Deliberately updated for J2: the target field lives in the "Aim for a size" aim, so the
     * test switches to that aim first. A chip fills a NUMERIC field and sets its unit; typing
     * over it clears the chip. TalkBack reads selected state through `isSelected` now that the
     * chips are 48dp TextView rows instead of Material chips.
     */
    @Test
    fun `setup offers four selectable decimal targets and keeps a typed target`() {
        DocFixtures.saved(repo, app, pages = 1, name = "quick target doc")
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress"))
            .use { scenario ->
                scenario.onActivity { activity ->
                    waitUntil { activity.findViewById<View>(R.id.toolSetupRun)?.isShown == true }
                    activity.findViewWithTag<View>(ToolSetupActivity.COMPRESS_MODE_SIZE_TAG)!!.performClick()
                    idle()
                    val field = activity.findViewById<EditText>(R.id.toolSetupTarget)
                    assertNotNull("target field", field)
                    val chip = activity.findViewById<TextView>(R.id.v2t_quick_20)
                    assertNotNull("200 KB quick chip", chip)
                    chip.performClick()
                    idle()
                    assertEquals("the field holds 200 in its KB unit", "200", field.text.toString())
                    assertTrue("chip is selected for TalkBack", chip.isSelected)
                    // Typing a custom target must not be overwritten and clears the chip.
                    field.setText("37")
                    idle()
                    assertEquals("37", field.text.toString())
                    assertFalse("custom target clears the quick chip", chip.isSelected)
                }
            }
    }

    @Test
    fun `result keeps Share primary and offers verified-copy secondary actions`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "verified output")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val share = activity.findViewById<android.widget.TextView>(R.id.toolResultShare)
                assertEquals("Share is the PDF primary",
                    com.scanwhirl.app.ui.kit.SwStyle.color(activity, R.color.sw_disc_ink),
                    share.currentTextColor)
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                val compress = activity.findViewById<View>(R.id.v2t_compress_copy)
                val protect = activity.findViewById<View>(R.id.v2t_protect_copy)
                assertNotNull("Compress this copy", compress)
                assertNotNull("Protect this copy", protect)
                assertEquals(app.getString(R.string.v2t_compress_this_copy),
                    (compress as android.widget.TextView).text.toString())
                assertEquals(app.getString(R.string.v2t_protect_this_copy),
                    (protect as android.widget.TextView).text.toString())
            }
        }
    }
}
