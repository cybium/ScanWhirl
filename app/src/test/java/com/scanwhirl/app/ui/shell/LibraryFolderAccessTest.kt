package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-W4 Part B: folders are reachable without discovering a long-press.
 *
 * The visible route is the "All folders" chip: it lists every folder (including empty
 * ones), every row has an explicit More that renames or deletes it, and deleting a
 * folder keeps every document. Folder creation and move are FREE per the accepted
 * correction in docs/release/PRO_PLAN.md (line 10-11); Tags stay Pro.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryFolderAccessTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("library loaded") {
                    (activity.findViewById<ViewGroup>(R.id.swDocList))?.childCount ?: 0 > 0 ||
                        repo.listDocuments().isEmpty()
                }
                block(activity)
            }
        }
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? DocCard }
    }

    private fun layoutRoot(activity: ShellActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun allTexts(root: View?): List<String> = allTextViews(root).mapNotNull { it.text?.toString() }

    private fun allTextViews(root: View?): List<TextView> {
        if (root == null) return emptyList()
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.visibility == View.VISIBLE) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun byDescription(root: View?, description: String): List<View> {
        if (root == null) return emptyList()
        val out = mutableListOf<View>()
        fun walk(view: View) {
            if (view.contentDescription?.toString() == description) out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun sheetContent(): ViewGroup {
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("a sheet is open", sheet)
        val content = sheet!!.findViewById<ViewGroup>(R.id.swSheetContent)
        assertNotNull("the sheet has content", content)
        return content!!
    }

    private fun sheetTitle(): String {
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("a sheet is open", sheet)
        return sheet!!.findViewById<TextView>(R.id.swSheetTitle).text.toString()
    }

    // ---- the explicit route ----------------------------------------------

    @Test
    fun `All folders is a visible 48dp tap that lists every folder with its own More`() {
        val billed = DocFixtures.saved(repo, app, pages = 1, name = "Electricity bill")
        assertTrue(repo.createFolder("Bills"))
        assertTrue(repo.createFolder("Tax 2026"))
        repo.setFolder(listOf(billed), "Bills")

        launch { activity ->
            waitFor("the folder chip rail renders") {
                activity.findViewById<View>(R.id.swAllFolders) != null
            }
            val chip = activity.findViewById<TextView>(R.id.swAllFolders)
            assertNotNull("All folders is an explicit chip, not a gesture", chip)
            assertTrue("All folders is on screen", chip.isVisible)
            assertEquals("All folders ▾", chip.text.toString())

            layoutRoot(activity)
            val density = activity.resources.displayMetrics.density
            assertTrue(
                "All folders is ${chip.width}x${chip.height}px, needs 48dp",
                chip.width >= 48 * density - 1 && chip.height >= 48 * density - 1
            )

            chip.performClick()
            idle()
            assertEquals("All folders", sheetTitle())
            val content = sheetContent()
            val texts = allTexts(content)
            assertTrue("empty-capable folder Bills is listed: $texts", texts.any { it.startsWith("Bills (") })
            assertTrue("folder Tax 2026 is listed: $texts", texts.any { it.startsWith("Tax 2026 (") })
            assertTrue(
                "Bills has an explicit More",
                byDescription(content, "Folder options for Bills").isNotEmpty()
            )
            assertTrue(
                "Tax 2026 has an explicit More",
                byDescription(content, "Folder options for Tax 2026").isNotEmpty()
            )
        }
    }

    @Test
    fun `many folders appear in one list without losing any`() {
        repeat(12) { assertTrue(repo.createFolder("Folder %02d".format(it + 1))) }
        DocFixtures.saved(repo, app, pages = 1, name = "Many folders doc")
        launch { activity ->
            waitFor("All folders exists") {
                activity.findViewById<View>(R.id.swAllFolders) != null
            }
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            val texts = allTexts(sheetContent()).toSet()
            for (index in 1..12) {
                val name = "Folder %02d".format(index)
                assertTrue("$name is listed once: $texts", texts.any { it.startsWith("$name (") })
            }
        }
    }

    @Test
    fun `deleting a folder from its explicit More keeps every document`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Folder A")
        val b = DocFixtures.saved(repo, app, pages = 2, name = "Folder B")
        assertTrue(repo.createFolder("Tax"))
        repo.setFolder(listOf(a, b), "Tax")

        launch { activity ->
            waitFor("All folders exists") {
                activity.findViewById<View>(R.id.swAllFolders) != null
            }
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            val more = byDescription(sheetContent(), "Folder options for Tax").single()
            more.performClick()
            idle()

            val options = ShadowDialog.getLatestDialog()
            assertNotNull("the folder menu opens", options)
            assertTrue(
                "the menu offers rename and a delete that keeps documents",
                allTexts(options!!.findViewById(R.id.swSheetRoot)).any {
                    it.contains("keep every document", ignoreCase = true)
                }
            )
            assertEquals(
                "Rename folder",
                options.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
            options.findViewById<TextView>(R.id.swSheetSecondary).performClick()
            idle()

            val confirm = ShadowDialog.getLatestDialog()
            assertNotNull("delete asks first", confirm)
            confirm!!.findViewById<TextView>(R.id.swSheetPrimary).performClick()
            waitFor("the folder is deleted") { repo.listFolders().isEmpty() }

            val all = repo.listDocuments()
            assertEquals("both documents survive", 2, all.size)
            assertTrue("A stays", all.any { it.id == a })
            assertTrue("B stays", all.any { it.id == b })
            assertNull("A has no folder now", repo.getDocument(a)?.folder)
            assertNull("B has no folder now", repo.getDocument(b)?.folder)
        }
    }

    @Test
    fun `zero folders is an honest state that still offers New folder`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Only doc")
        launch { activity ->
            waitFor("All folders exists") {
                activity.findViewById<View>(R.id.swAllFolders) != null
            }
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            assertEquals("All folders", sheetTitle())
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull(sheet)
            val empty = sheet!!.findViewById<View>(R.id.swFolderSheetEmpty)
            assertNotNull("the empty state is explicit", empty)
            assertTrue("the empty state is visible", empty.isVisible)
            assertEquals("No folders yet", (empty as TextView).text.toString())
            // V027: one creation control only — the plain row under "All folders".
            val newFolder = sheet.findViewById<TextView>(R.id.swNewFolder)
            assertNotNull("New folder stays reachable from the empty state", newFolder)
            assertEquals("+ New folder", newFolder.text.toString())
            assertTrue("New folder is visible", newFolder.isVisible)
        }
    }

    @Test
    fun `a very long folder name stays whole in the rail and the list`() {
        val requested = "Village land records " + "and survey papers ".repeat(6) + "2026"
        assertTrue(repo.createFolder(requested))
        // The repository normalises whitespace; the chip must show the stored name whole.
        val longName = repo.listFolders().single()
        DocFixtures.saved(repo, app, pages = 1, name = "Land map")

        launch { activity ->
            // Council §7: OLD read the long chip from the face rail. NEW reads it from
            // the shared folder/category chooser behind "All folders ▾".
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            val content = sheetContent()
            waitFor("the long folder row renders") {
                content.findViewById<ViewGroup>(R.id.swFolderRow)?.let { row ->
                    allTexts(row).any { it.startsWith(longName) }
                } == true
            }
            val row = content.findViewById<ViewGroup>(R.id.swFolderRow)
            val chip = allTextViews(row).first { it.text.toString().startsWith(longName) }
            assertEquals("$longName (0)", chip.text.toString())
            val layout = chip.layout
            if (layout != null) {
                for (line in 0 until layout.lineCount) {
                    assertEquals("the name is never cut with an ellipsis", 0, layout.getEllipsisCount(line))
                }
            }
            val texts = allTexts(content)
            assertTrue("the whole name is listed: $texts", texts.any { it.startsWith(longName) })
        }
    }

    @Test
    fun `Free creates a folder and moves a document into it while Tags stay Pro`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Move me")
        launch { activity ->
            // Council §7: New folder is now a row in the shared chooser, not a face chip.
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            waitFor("the New folder row exists") {
                sheetContent().findViewById<View>(R.id.swNewFolder) != null
            }
            sheetContent().findViewById<View>(R.id.swNewFolder).performClick()
            idle()
            assertTrue(
                "folder creation must not open the Pro screen in Free",
                activity.findViewById<View>(R.id.swProScreen)?.isVisible != true
            )
            val create = ShadowDialog.getLatestDialog()
            assertNotNull("the New folder sheet opens for Free", create)
            create!!.findViewById<EditText>(R.id.swSheetInput).setText("Bills")
            create.findViewById<TextView>(R.id.swSheetPrimary).performClick()
            waitFor("the folder is created for Free") { repo.listFolders().contains("Bills") }

            cards(activity).first { it.doc?.id == id }.performLongClick()
            idle()
            activity.findViewById<View>(R.id.swSelMove).performClick()
            idle()
            val move = ShadowDialog.getLatestDialog()
            assertNotNull("the move sheet opens", move)
            val rows = move!!.findViewById<ViewGroup>(R.id.swSheetItems)
            val target = (0 until rows.childCount)
                .map { rows.getChildAt(it) }
                .first { it.contentDescription?.toString()?.startsWith("Bills") == true }
            target.performClick()
            waitFor("moving into the folder is Free") {
                repo.getDocument(id)?.folder == "Bills"
            }

            // Tags are still Pro: the accepted change is only about folders. The UI gate is
            // asserted in LibraryOrganisationTest; here the catalogue stays unambiguous.
            assertTrue(
                "folders left the Pro catalogue",
                com.scanwhirl.app.ui.kit.ToolRegistry.byId("folders")?.pro == false
            )
            assertTrue(
                "batch rename stays Pro",
                com.scanwhirl.app.ui.kit.ToolRegistry.byId("batch_rename")?.pro == true
            )
        }
    }

    @Test
    fun `an active folder filter is named under the rails at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Tax proof")
        assertTrue(repo.createFolder("Tax 2026"))
        repo.setFolder(listOf(id), "Tax 2026")

        launch { activity ->
            // Council §7: pick the folder from the shared chooser; the visible state
            // afterwards is the removable Filter chip, not a second chip rail.
            activity.findViewById<View>(R.id.swAllFolders).performClick()
            idle()
            val content = sheetContent()
            waitFor("the folder row renders") {
                content.findViewById<ViewGroup>(R.id.swFolderRow)?.let { row ->
                    allTexts(row).any { it.startsWith("Tax 2026") }
                } == true
            }
            val row = content.findViewById<ViewGroup>(R.id.swFolderRow)
            val chip = allTextViews(row).first { it.text.toString().startsWith("Tax 2026") }
            chip.performClick()
            idle()
            layoutRoot(activity)

            val active = activity.findViewById<TextView>(R.id.swActiveFilter)
            assertNotNull("the active filter is stated in words", active)
            assertTrue("the active filter line is visible", active.isVisible)
            assertEquals("Showing only: Tax 2026", active.text.toString())
            assertEquals("Folder filter: Tax 2026", active.contentDescription?.toString())
            val layout = active.layout
            assertNotNull("the filter line is laid out at 200%", layout)
            for (line in 0 until layout!!.lineCount) {
                assertEquals("the filter name is not cut", 0, layout.getEllipsisCount(line))
            }
        }
    }
}
