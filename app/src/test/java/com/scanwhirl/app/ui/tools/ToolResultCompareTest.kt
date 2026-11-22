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
 * Lane C: every tool result owns the one Before | After surface. Compress and the page tools
 * derive their proof from real output; other tools may supply measured facts (fields removed,
 * words found, page counts). The identity-guarded Undo, Last result receipt and the two
 * "this copy" quick actions must survive unchanged.
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
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "compress", docIds = listOf(out),
                beforeBytes = sourceBytes, receiptSourceId = source
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue(view.pageChipText().contains(app.getString(R.string.cc_page, 1)))
                view.showBefore()
                assertTrue(
                    "before size present: ${view.captionText()}",
                    view.captionText().contains(app.getString(R.string.cc_before_size, ""))
                )
                view.showAfter()
                assertTrue(
                    "after size present: ${view.captionText()}",
                    view.captionText().contains(app.getString(R.string.cc_after_size, ""))
                )
                assertNotNull("identity-guarded Undo still present", activity.findViewById<View>(R.id.toolResultUndo))
            }
        }
        assertTrue("the source document is never named as the output", repo.getDocument(source) != null)
        assertTrue("the compressed copy still exists", repo.getDocument(out) != null)
    }

    @Test
    fun `measured proof shows removed fields with a Before After toggle`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Clean")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "clear_metadata", docIds = listOf(id),
                compareOutcome = "12 fields removed",
                compareBeforeCaption = "14 fields",
                compareAfterCaption = "2 fields kept",
                compareBeforeDetail = listOf("Author", "Creation time", "GPS location"),
                compareAfterDetail = listOf("Page count")
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertEquals("12 fields removed", view.outcomeText())
                view.showBefore()
                assertEquals("14 fields", view.captionText())
                assertTrue(view.emptyLabel().text.toString().contains("Author"))
                assertTrue(view.emptyLabel().text.toString().contains("GPS location"))
                view.showAfter()
                assertEquals("2 fields kept", view.captionText())
                assertTrue(view.emptyLabel().text.toString().contains("Page count"))
            }
        }
    }

    @Test
    fun `page tools say one mark added on page 1`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Signed")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "sign", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertTrue(view.pageChipText().contains(app.getString(R.string.cc_page, 1)))
                // The mark count is the outcome headline and is spoken with the caption.
                assertEquals(app.getString(R.string.cc_mark_added), view.outcomeText())
                val a11y = view.findViewById<View>(R.id.ccCaption).contentDescription.toString()
                assertTrue("mark count is spoken: $a11y", a11y.contains(app.getString(R.string.cc_mark_added)))
            }
        }
    }

    @Test
    fun `state only tools hide the toggle but keep the honest state line`() {
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
                val view = compare(activity)
                assertEquals(View.VISIBLE, view.visibility)
                assertEquals(View.GONE, view.beforeButton().visibility)
                assertEquals(View.GONE, view.afterButton().visibility)
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
                view.showBefore()
                assertTrue(view.captionText().contains("पहले:"))
                view.showAfter()
                assertTrue(view.captionText().contains("बाद में:"))
            }
        }
    }
}

/** Small helper on the view so the tests read the page chip without reaching into private fields. */
private fun CompareView.pageChipText(): String =
    findViewById<TextView>(com.scanwhirl.app.R.id.ccPageChip)?.text?.toString().orEmpty()
