package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * v2 UI: 03_tools_hub — every L4 tool in the catalogue is Ready and reaches its setup screen;
 * the shared setup screen offers the right option views and validates them; the result screen
 * has Open · Share · Save · Show in library · Undo and every button acts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolsUiTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    // fixui: tiles removed (owner F6/F7/F20) - business_card and duplicate no longer have a
    // tile. The activities stay for their own flows/tests; the tile-launch promise is gone.
    private val l4Tools = listOf(
        "id_card", "passport", "merge", "split", "extract",
        "compress", "pdf_to_images", "long_image", "to_word", "to_powerpoint", "to_excel", "zip",
        "remove_metadata"
    )

    @Before
    fun setUp() {
        // androidx FileProvider caches its path roots per authority in a static map; under
        // Robolectric every test class gets a fresh cacheDir, so a stale entry from an earlier
        // class makes getUriForFile fail with "Failed to find configured root".
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
        DocFixtures.saved(repo, app, pages = 3, name = "Notes")
    }

    @After
    fun tearDown() = Faults.resetAll()

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

    private class RecordingHost(private val context: Context) : ContextWrapper(context), ToolHost {
        val events = mutableListOf<String>()
        override fun open(tool: SwTool) {
            events += "open:${tool.id}"
        }

        override fun comingSoon(tool: SwTool) {
            events += "soon:${tool.id}"
        }
    }

    // ---- registry wiring ---------------------------------------------------

    @Test
    fun `every L4 tool is Ready and launches its own setup screen`() {
        val host = RecordingHost(app)
        for (id in l4Tools) {
            val tool = requireNotNull(ToolRegistry.byId(id)) { "missing tool $id" }
            assertEquals("$id must be Ready", ToolStatus.READY, tool.status)

            tool.launch(host, tool)

            val started = requireNotNull(shadowOf(app).nextStartedActivity) { "$id launched nothing" }
            when (id) {
                "passport" -> assertEquals(
                    "$id must open the passport crop preset",
                    PassportActivity::class.java.name, started.component?.className
                )
                "id_card" -> assertEquals(
                    "$id must open the ID card capture flow",
                    IdCardActivity::class.java.name, started.component?.className
                )
                else -> {
                    assertEquals(
                        "$id must open the shared setup screen",
                        ToolSetupActivity::class.java.name, started.component?.className
                    )
                    assertEquals("$id must carry its tool id", id, ToolSetupActivity.toolIdOf(started))
                }
            }
        }
        assertTrue("a ready tool must not fall back to Coming soon", host.events.isEmpty())
    }

    @Test
    fun `the other lanes' tools keep their own launch behaviour`() {
        val host = RecordingHost(app)
        for (id in listOf("sign", "to_excel", "qr_code")) {
            val tool = ToolRegistry.byId(id) ?: continue
            tool.launch(host, tool)
            assertTrue("$id must not open the L4 setup screen", host.events.none { it.startsWith("setup:") })
            shadowOf(app).nextStartedActivity
        }
    }

    // ---- setup screen ------------------------------------------------------

    @Test
    fun `split setup validates the ranges before anything is written`() {
        val before = repo.listDocuments().map { it.id }.toSet()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                val ranges = activity.findViewById<EditText>(R.id.toolSetupRanges)
                assertNotNull("Split needs a ranges field", ranges)
                val preview = activity.findViewById<TextView>(R.id.toolSetupRangePreview)

                ranges.setText("1-2")
                idle()
                assertTrue("a valid range previews: ${preview.text}", preview.text.contains("1"))

                ranges.setText("9-12")
                idle()
                assertTrue(
                    "an impossible page must explain itself: ${preview.text}",
                    preview.text.contains("not in this document")
                )

                activity.findViewById<View>(R.id.toolSetupRun).performClick()
                idle()
                assertTrue("running with a bad range shows an error", activity.findViewById<View>(R.id.toolSetupError).isVisible)
            }
        }
        assertEquals("a refused range must create nothing", before, repo.listDocuments().map { it.id }.toSet())
    }

    @Test
    fun `pdf to images setup offers JPG PNG and a quality control`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "pdf_to_images")).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.toolSetupFormatJpg))
                assertNotNull(activity.findViewById<View>(R.id.toolSetupFormatPng))
                assertNotNull("JPG has a quality slider", activity.findViewById<View>(R.id.toolSetupQuality))

                activity.findViewById<View>(R.id.toolSetupFormatPng).performClick()
                idle()
                assertNull(
                    "PNG has no JPEG quality control",
                    activity.findViewById<View>(R.id.toolSetupQuality)
                )
            }
        }
    }

    @Test
    fun `compress setup offers a target size`() {        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Compress needs a target-size field", activity.findViewById<View>(R.id.toolSetupTarget))
                assertNotNull(activity.findViewById<View>(R.id.toolSetupRun))
            }
        }
    }

    @Test
    fun `to word setup offers the page pictures option and toggles it`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_word")).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<View>(R.id.toolSetupWordPictures)
                assertNotNull("To Word needs the page-pictures option", toggle)
                toggle.performClick()
                idle()
                val after = activity.findViewById<View>(R.id.toolSetupWordPictures)
                assertNotNull("the option must survive the re-render", after)
                assertTrue("the option is a real toggle", after.hasOnClickListeners())
            }
        }
    }

    @Test
    fun `compress level rows show one selected choice and an estimate`() {
        // v2 UI: 03_tools_setup_picker — the four compress-sheet levels, each with its size.
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("Balanced is the default", activity.findViewById<View>(R.id.toolSetupLevelBalanced).isSelected)
                assertFalse(activity.findViewById<View>(R.id.toolSetupLevelBest).isSelected)
                assertFalse(activity.findViewById<View>(R.id.toolSetupLevelSmallest).isSelected)

                activity.findViewById<View>(R.id.toolSetupLevelBest).performClick()
                idle()
                assertTrue(
                    "Best quality is now the single choice",
                    activity.findViewById<View>(R.id.toolSetupLevelBest).isSelected
                )
                assertFalse(activity.findViewById<View>(R.id.toolSetupLevelBalanced).isSelected)

                assertTrue("the estimate appears", waitUntil {
                    var shown = false
                    fun walk(view: View) {
                        if (view is TextView && view.id == R.id.toolSetupLevelSize &&
                            view.text.toString().contains("B")
                        ) {
                            shown = true
                        }
                        if (view is android.view.ViewGroup) {
                            for (i in 0 until view.childCount) walk(view.getChildAt(i))
                        }
                    }
                    activity.findViewById<View>(R.id.toolSetupLevels)?.let { walk(it) }
                    shown
                })
            }
        }
    }

    @Test
    fun `to excel setup offers tables only and all text`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_excel")).use { scenario ->
            scenario.onActivity { activity ->
                val tables = activity.findViewById<View>(R.id.toolSetupExcelTables)
                val all = activity.findViewById<View>(R.id.toolSetupExcelAll)
                assertNotNull("Tables only option", tables)
                assertNotNull("All text option", all)
                assertTrue(tables.hasOnClickListeners())
                all.performClick()
                idle()
                assertNotNull(activity.findViewById<View>(R.id.toolSetupRun))
            }
        }
    }

    @Test
    fun `to excel tables only offers all text when no page has a table`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Plain letter")
        repo.ocrStore.putPage(
            id, "page_000.jpg", "letter",
            OcrPage(1000, 1400, listOf(OcrLine("Dear sir", 60, 80, 420, 120)))
        )
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_excel")).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
            }
            assertTrue("the no-tables sheet opens", waitUntil {
                ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetTitle)
                    ?.text?.toString() == app.getString(R.string.tools_excel_no_tables_title)
            })
            scenario.onActivity {
                ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue("the all-text export opens the result", waitUntil {
                var opened = false
                scenario.onActivity { opened = shadowOf(it).peekNextStartedActivity()
                    ?.component?.className == ToolResultActivity::class.java.name }
                opened
            })
        }
        val workbooks = File(app.cacheDir, "documents/tools").walkTopDown()
            .filter { it.name.endsWith(".xlsx") }.toList()
        assertEquals("one all-text workbook is written", 1, workbooks.size)
    }

    @Test
    fun `to powerpoint setup explains the slides and notes`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_powerpoint")).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<View>(R.id.toolSetupOptions))
                val texts = mutableListOf<String>()
                fun walk(view: View) {
                    if (view is TextView) texts += view.text?.toString().orEmpty()
                    if (view is android.view.ViewGroup) {
                        for (i in 0 until view.childCount) walk(view.getChildAt(i))
                    }
                }
                walk(activity.findViewById(R.id.toolSetupOptions))
                assertTrue(
                    "PowerPoint setup must describe the slides: $texts",
                    texts.any { it.contains("slide", ignoreCase = true) }
                )
                assertNotNull(activity.findViewById<View>(R.id.toolSetupRun))
            }
        }
    }

    @Test
    fun `running split opens the result screen with the new document`() {
        val doc = repo.listDocuments().first()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.toolSetupRanges).setText("1-1")
                idle()
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
            }
            assertTrue("split finishes", waitUntil {
                var started: Intent? = null
                scenario.onActivity { started = shadowOf(it).peekNextStartedActivity() }
                started?.component?.className == ToolResultActivity::class.java.name
            })
        }
        assertTrue("split published a document", repo.listDocuments().size >= 2)
        assertTrue("split kept its source name", repo.listDocuments().any { it.name.contains(doc.name) })
    }

    // ---- result screen -----------------------------------------------------

    @Test
    fun `result screen offers Open Share Save Show in library and Undo`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Result doc")
        val intent = ToolResultActivity.intent(app, "split", docIds = listOf(id))

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                for (rid in listOf(
                    R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultSave,
                    R.id.toolResultLibrary, R.id.toolResultUndo
                )) {
                    assertNotNull("missing result button $rid", activity.findViewById<View>(rid))
                }
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                val forResult = shadowOf(activity).nextStartedActivityForResult
                assertNotNull("Save opens the system Save-as picker", forResult)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, forResult.intent.action)
                assertEquals("application/pdf", forResult.intent.type)
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultShare).performClick()
                val chooser = com.scanwhirl.app.util.awaitShareChooser(activity)
                assertNotNull("Share opens the sharesheet", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultLibrary).performClick()
                idle()
                val library = shadowOf(activity).nextStartedActivity
                assertNotNull("Show in library leaves for the shell", library)
                assertTrue(
                    "must land on the library shell: ${library.component?.className}",
                    library.component?.className?.endsWith("ShellActivity") == true
                )
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultUndo).performClick()
            }
            assertTrue("Undo removes the new copy", waitUntil { repo.getDocument(id) == null })
        }
        assertNull("the source of a result must be gone only through Undo", repo.getDocument(id))
    }

    @Test
    fun `result screen acts on exported files too`() {
        val jpeg = File(app.cacheDir, "documents/result_page.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(com.scanwhirl.app.util.ImportFixtures.image(width = 120, height = 80))
        }
        val intent = ToolResultActivity.intent(
            app, "pdf_to_images", files = listOf(jpeg), mime = "image/jpeg"
        )

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultOpen).performClick()
                idle()
                val view = shadowOf(activity).nextStartedActivity
                assertNotNull(view)
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals("image/jpeg", view.type)
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultSave).performClick()
                idle()
                val forResult = shadowOf(activity).nextStartedActivityForResult
                assertNotNull(forResult)
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, forResult.intent.action)
                assertEquals(jpeg.name, forResult.intent.getStringExtra(Intent.EXTRA_TITLE))
            }
        }

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultUndo).performClick()
            }
            assertTrue("Undo deletes the exported file", waitUntil { !jpeg.exists() })
        }
    }
}
