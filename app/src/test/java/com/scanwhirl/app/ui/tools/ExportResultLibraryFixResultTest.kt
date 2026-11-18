package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * H7 (BUGS_HUNT, phone 1.2.2): after an export to Word/Excel/PowerPoint/images/ZIP the result
 * screen offered "Show in library", which opened the Library tab where the exported file is not
 * and can never be listed (exports are files, not library documents). Tapping it looked broken.
 *
 * The export results therefore drop that button; a screen whose result *is* a library document
 * (split, compress, merge, ...) keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportResultLibraryFixResultTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private lateinit var work: File

    @Before
    fun setUp() {
        work = File(app.cacheDir, "fixresult_export_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    @Test
    fun `an exported file result does not offer Show in library`() {
        val docx = File(work, "Report.docx").apply { writeBytes("PK\u0003\u0004docx".toByteArray()) }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "to_word", files = listOf(docx),
                mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                val library = activity.findViewById<View>(R.id.toolResultLibrary)
                assertEquals(
                    "H7: an export is not a library document, so Show in library must be hidden",
                    View.GONE,
                    library.visibility
                )
                // Hiding must not leave a dead label: the row's other action still works, and
                // nothing on this screen pretends the export is in the library.
                assertEquals(
                    "the row's remaining action stays usable",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.toolResultUndo).visibility
                )
                assertNull(
                    "opening the result screen must not leave for the library",
                    shadowOf(activity).peekNextStartedActivity()
                )
            }
        }
    }

    @Test
    fun `an export result still offers the buttons that do work`() {
        val zip = File(work, "All.zip").apply { writeBytes("PK\u0003\u0004zip".toByteArray()) }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "zip", files = listOf(zip), mime = "application/zip")
        ).use { scenario ->
            scenario.onActivity { activity ->
                for (id in listOf(R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultSave, R.id.toolResultUndo)) {
                    val view = activity.findViewById<View>(id)
                    assertEquals("missing/!shown result button $id", View.VISIBLE, view.visibility)
                }
            }
        }
    }

    @Test
    fun `a document result keeps Show in library`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Split result")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                val library = activity.findViewById<View>(R.id.toolResultLibrary)
                assertEquals("H7: a document result is in the library", View.VISIBLE, library.visibility)
                library.performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val started: Intent? = shadowOf(activity).peekNextStartedActivity()
                assertTrue(
                    "the library button still opens the library: ${started?.component?.className}",
                    started?.component?.className == ShellActivity::class.java.name
                )
            }
        }
    }
}
