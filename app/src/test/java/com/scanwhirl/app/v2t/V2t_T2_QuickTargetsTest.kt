package com.scanwhirl.app.v2t

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
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

    @Test
    fun `one shared decimal KB table drives both setup surfaces`() {
        assertEquals(listOf(20, 50, 100, 200), CompressionPresentation.quickTargetKb)
        val compress = java.io.File("src/main/java/com/scanwhirl/app/tools/CompressFlow.kt").readText()
        val setup = java.io.File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt").readText()
        val resize = java.io.File("src/main/java/com/scanwhirl/app/tools/ResizeFlow.kt").readText()
        assertTrue(compress.contains("CompressionPresentation.quickTargetKb"))
        assertTrue(setup.contains("CompressionPresentation.quickTargetKb"))
        assertTrue(resize.contains("CompressionPresentation.quickTargetKb"))
    }

    @Test
    fun `setup offers four selectable quick chips and keeps a typed target`() {
        DocFixtures.saved(repo, app, pages = 1, name = "quick target doc")
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress"))
            .use { scenario ->
                scenario.onActivity { activity ->
                    waitUntil { activity.findViewById<View>(R.id.toolSetupRun)?.isShown == true }
                    val field = activity.findViewById<EditText>(R.id.toolSetupTarget)
                    assertNotNull("target field", field)
                    val chip = activity.findViewById<Chip>(R.id.v2t_quick_20)
                    assertNotNull("20 KB quick chip", chip)
                    chip.performClick()
                    idle()
                    assertEquals("20 KB", field.text.toString())
                    assertTrue("chip is checked for TalkBack", chip.isChecked)
                    // Typing a custom target must not be overwritten and clears the chip.
                    field.setText("37 KB")
                    idle()
                    assertEquals("37 KB", field.text.toString())
                    assertFalse("custom target clears the quick chip", chip.isChecked)
                }
            }
    }

    @Test
    fun `result keeps Open primary and offers verified-copy secondary actions`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "verified output")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val open = activity.findViewById<android.widget.TextView>(R.id.toolResultOpen)
                assertEquals("Open is still primary",
                    com.scanwhirl.app.ui.kit.SwStyle.color(activity, R.color.sw_disc_ink),
                    open.currentTextColor)
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
