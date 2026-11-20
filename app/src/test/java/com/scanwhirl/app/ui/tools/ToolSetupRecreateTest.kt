package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * H5-2 (overnight bug hunt): the manifest declares no `android:configChanges`, so rotation,
 * dark mode, a font-size change or a language change recreates the tool setup screen. It used to
 * rebuild itself around the newest document and drop everything typed, so Run could silently work
 * on the wrong document. The chosen document and every typed value must come back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupRecreateTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    /** The oldest document, deliberately not the one the screen auto-selects. */
    private lateinit var older: String

    @Before
    fun setUp() {
        ProGate.setTestOverride(app, null)
        older = DocFixtures.saved(repo, app, pages = 5, name = "Rent receipt")
        DocFixtures.saved(repo, app, pages = 3, name = "Offer letter")
        DocFixtures.saved(repo, app, pages = 1, name = "Newest scan")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun rows(activity: ToolSetupActivity): List<LinearLayout> {
        val host = activity.findViewById<LinearLayout>(R.id.toolSetupSource) ?: return emptyList()
        return (0 until host.childCount).mapNotNull { host.getChildAt(it) as? LinearLayout }
    }

    private fun row(activity: ToolSetupActivity, docId: String): LinearLayout? =
        rows(activity).firstOrNull { it.tag == docId }

    @Test
    fun `the chosen document and typed ranges survive a recreation and Run targets it`() {
        val names = repo.listDocuments().map { it.name }
        assertTrue("the newest document is first in the library", names.first() == "Newest scan")

        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { rows(activity).size == 3 })
                val chosen = requireNotNull(row(activity, older)) { "the older document has a row" }
                chosen.performClick()
                activity.findViewById<EditText>(R.id.toolSetupRanges)!!.setText("2-4")
            }

            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("the library is listed again", waitUntil { rows(activity).size == 3 })
                val kept = requireNotNull(row(activity, older)) { "the older document has a row" }
                assertEquals(
                    "the chosen document must stay ticked",
                    View.VISIBLE,
                    kept.findViewById<ImageView>(R.id.toolSetupDocCheck).visibility
                )
                assertEquals(
                    "the typed ranges must come back",
                    "2-4",
                    activity.findViewById<EditText>(R.id.toolSetupRanges)?.text?.toString()
                )

                val before = repo.listDocuments().size
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
                assertTrue(
                    "Run publishes the split",
                    waitUntil { repo.listDocuments().size > before }
                )
                val part = repo.listDocuments().first()
                assertTrue(
                    "the split must come from the chosen document, got: ${part.name}",
                    part.name.startsWith("Rent receipt")
                )
                assertEquals("pages 2-4 of 5 become three pages", 3, part.pageCount)
            }
        }
    }

    @Test
    fun `a typed output name survives a recreation`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "merge")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { rows(activity).size == 3 })
                activity.findViewById<EditText>(R.id.toolSetupName)!!.setText("Rent receipt copy")
            }

            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("the library is listed again", waitUntil { rows(activity).size == 3 })
                assertEquals(
                    "the typed name must come back",
                    "Rent receipt copy",
                    activity.findViewById<EditText>(R.id.toolSetupName)?.text?.toString()
                )
            }
        }
    }

    @Test
    fun `a typed target size survives a recreation`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library is listed", waitUntil { rows(activity).size == 3 })
                activity.findViewById<EditText>(R.id.toolSetupTarget)!!.setText("300 KB")
            }

            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("the library is listed again", waitUntil { rows(activity).size == 3 })
                assertEquals(
                    "the typed target size must come back",
                    "300 KB",
                    activity.findViewById<EditText>(R.id.toolSetupTarget)?.text?.toString()
                )
            }
        }
    }
}
