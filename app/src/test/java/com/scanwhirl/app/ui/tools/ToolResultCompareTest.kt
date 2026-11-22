package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.CompareView
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

/**
 * Lane RS: Compress/Resize show a stacked zoom pair; mark tools show one After page; state-only
 * tools show no image. The identity-guarded Undo, Last-result receipt and the two "this copy"
 * quick actions stay unchanged and are pinned here.
 */
abstract class ToolResultCompareBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected val repo get() = ScanRepository(app)
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun clearProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    protected fun compare(activity: ToolResultActivity): CompareView =
        activity.findViewById(R.id.ccCompare)

    @Test
    fun `compress derives before and after sizes from the real documents`() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Source")
        val out = DocFixtures.saved(repo, app, pages = 1, name = "Source small")
        val sourceBytes = repo.getDocument(source)!!.pdfFile.length()
        val outBytes = repo.getDocument(out)!!.pdfFile.length()
        // A real compress shrinks the file; the two fixtures are the same size, so give the
        // source a larger nominal size so this is not classified as a no-gain result.
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(out),
                beforeBytes = sourceBytes + 100_000L, receiptSourceId = source
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue(view.pageChipText().contains(app.getString(R.string.cc_page, 1)))
                val beforeShown = com.scanwhirl.app.util.StatusLine.formatBytes(sourceBytes + 100_000L)
                val afterShown = com.scanwhirl.app.util.StatusLine.formatBytes(outBytes)
                val hero = activity.findViewById<android.widget.TextView>(R.id.toolResultSummary).text.toString()
                assertTrue(hero.contains(beforeShown) && hero.contains(afterShown))
                assertEquals(app.getString(R.string.cc_before), view.beforeCaptionText())
                assertEquals(app.getString(R.string.rs_after_label), view.captionText())
                assertNotNull("identity-guarded Undo still present", activity.findViewById<View>(R.id.toolResultUndo))
            }
        }
        assertTrue("the source document is never named as the output", repo.getDocument(source) != null)
        assertTrue("the compressed copy still exists", repo.getDocument(out) != null)
    }

    @Test
    fun `a measured state tool shows no page image`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Clean")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "clear_metadata", docIds = listOf(id),
                compareOutcome = "12 fields removed",
                compareBeforeCaption = "14 fields",
                compareAfterCaption = "2 fields kept"
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(View.GONE, compare(activity).visibility)
                assertEquals(
                    "12 fields removed",
                    activity.findViewById<TextView>(R.id.toolResultSummary).text.toString()
                )
            }
        }
    }

    @Test
    fun `page tools show one After page and say one mark added on page 1`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Signed")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "sign", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue(view.pageChipText().contains(app.getString(R.string.cc_page, 1)))
                assertEquals(View.GONE, view.beforeButton().visibility)
                assertEquals(app.getString(R.string.cc_mark_added), view.outcomeText())
                val a11y = view.afterButton().contentDescription.toString()
                assertTrue("mark count is spoken: $a11y", a11y.contains(app.getString(R.string.cc_mark_added)))
            }
        }
    }

    @Test
    fun `state only tools show no comparison surface`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Locked")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "protect", docIds = listOf(id),
                compareOutcome = "Protected; no preview",
                compareShowToggle = false
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(View.GONE, compare(activity).visibility)
            }
        }
    }

    @Test
    fun `compress this copy and protect this copy survive on the more panel`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Quick")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolResultMore).performClick()
                idle()
                assertEquals(
                    app.getString(R.string.v2t_compress_this_copy),
                    activity.findViewById<TextView>(R.id.v2t_compress_copy).text.toString()
                )
                assertEquals(
                    app.getString(R.string.v2t_protect_this_copy),
                    activity.findViewById<TextView>(R.id.v2t_protect_copy).text.toString()
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ToolResultCompareEnTest : ToolResultCompareBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class ToolResultCompareHiTest : ToolResultCompareBase() {

    @Test
    fun `Hindi caption uses before and after words`() {
        assertEquals("पहले", app.getString(R.string.cc_before))
        assertEquals("बाद में", app.getString(R.string.cc_after))
        val id = DocFixtures.saved(repo, app, pages = 1, name = "छोटा")
        val bytes = repo.getDocument(id)!!.pdfFile.length()
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(id), beforeBytes = bytes + 1024
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertTrue(view.beforeCaptionText().contains("पहले"))
                assertTrue(view.captionText().contains("बाद में"))
            }
        }
    }
}

private fun CompareView.pageChipText(): String =
    findViewById<TextView>(com.scanwhirl.app.R.id.ccPageChip)?.text?.toString().orEmpty()
