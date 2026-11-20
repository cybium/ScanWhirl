package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * H5-1 (overnight bug hunt): the manifest declares no `android:configChanges`, so a rotation,
 * dark-mode switch, font-size change or language change destroys and recreates the Stamp editor.
 * Everything the person placed must come back, the entry tool must fire only for a fresh launch,
 * and a camera capture that arrived just before the recreation must not be dropped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampEditorRecreateTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun intent(id: String, tool: String? = null) =
        Intent(context, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(StampEditorActivity.EXTRA_TOOL, tool)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test
    fun `marks, redactions and the current page survive a recreation`() {
        val id = DocFixtures.saved(repo, context, pages = 3)
        val redaction = PageStamp.Box(0.10f, 0.20f, 0.60f, 0.35f)

        ActivityScenario.launch<StampEditorActivity>(intent(id)).use { scenario ->
            scenario.onActivity { screen ->
                val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)
                canvas.listener?.onBox(StampCanvasView.Tool.REDACT, redaction)
                screen.findViewById<android.view.View>(R.id.nextPage).performClick()
                screen.editor.add(
                    PageStamp.Mark.Text("SELF-ATTESTED", PageStamp.Box(0.1f, 0.1f, 0.9f, 0.2f))
                )
                assertEquals(1, screen.editor.currentPage)
                assertEquals(1, screen.editor.marks(0).size)
                assertEquals(1, screen.editor.marks(1).size)
            }

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { screen ->
                assertEquals("the visible page must come back", 1, screen.editor.currentPage)
                assertEquals("page 1 keeps its redaction", 1, screen.editor.marks(0).size)
                val kept = screen.editor.marks(0).single()
                assertTrue("the redaction must stay a redaction", kept is PageStamp.Mark.Redact)
                assertEquals("its exact geometry must survive", redaction, (kept as PageStamp.Mark.Redact).box)
                assertEquals("page 2 keeps its text", 1, screen.editor.marks(1).size)
                val text = screen.editor.marks(1).single()
                assertTrue(text is PageStamp.Mark.Text)
                assertEquals("SELF-ATTESTED", (text as PageStamp.Mark.Text).text)
                assertEquals(
                    "2 / 3",
                    screen.findViewById<TextView>(R.id.pageIndicator).text.toString()
                )
                assertTrue(
                    "the restored marks can be saved",
                    screen.findViewById<android.view.View>(R.id.saveCopy).isEnabled
                )
            }
        }
    }

    @Test
    fun `the requested entry tool does not run again after a recreation`() {
        val id = DocFixtures.saved(repo, context, pages = 2)

        ActivityScenario.launch<StampEditorActivity>(
            intent(id, StampEditorActivity.TOOL_PAGE_NUMBERS)
        ).use { scenario ->
            scenario.onActivity { screen ->
                // The person's own work on top of the entry tool.
                screen.findViewById<StampCanvasView>(R.id.canvas).listener?.onBox(
                    StampCanvasView.Tool.REDACT,
                    PageStamp.Box(0.1f, 0.1f, 0.5f, 0.3f)
                )
                assertEquals(2, screen.editor.marks(0).size)
                assertEquals(1, screen.editor.marks(1).size)
            }

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { screen ->
                assertEquals(
                    "the redaction and the page number must both come back, not a re-run page number",
                    2,
                    screen.editor.marks(0).size
                )
                assertEquals(
                    "page numbers must be added once, not again on recreation",
                    1,
                    screen.editor.marks(1).size
                )
            }
        }
    }

    @Test
    fun `a camera photo waiting for its result survives a recreation`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val photo = File(File(context.cacheDir, "documents").apply { mkdirs() }, "ink_f6state.jpg")
        photo.writeBytes(byteArrayOf(1, 2, 3))

        ActivityScenario.launch<StampEditorActivity>(intent(id)).use { scenario ->
            scenario.onActivity { screen -> screen.pendingPhotoFile = photo }

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { screen ->
                assertNotNull(
                    "the signature/stamp photo taken before the recreation must not be dropped",
                    screen.pendingPhotoFile
                )
                assertEquals(photo.path, screen.pendingPhotoFile?.path)
            }
        }
    }

    @Test
    fun `a recreate never writes a copy on its own`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val before = repo.listDocuments().size

        ActivityScenario.launch<StampEditorActivity>(intent(id)).use { scenario ->
            scenario.onActivity { screen ->
                screen.editor.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.5f, 0.3f)))
            }

            scenario.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { screen ->
                assertEquals(
                    "recreating the screen must not create a document",
                    before,
                    repo.listDocuments().size
                )
                assertFalse(screen.isFinishing)
                assertEquals(1, screen.editor.marks().size)
            }
        }
    }
}
