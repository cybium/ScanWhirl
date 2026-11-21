package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * L1-05 — search promises what it does, reports a scoped live count, and selection
 * says "Move to Trash" / "Finish selection". Confirmation and Undo must stay intact.
 *
 * RED on base: the hint under-promised ("Search pages"), there was no live result
 * count, and the selection bar said "Done" / "Delete".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1SearchSelectionWordingTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? DocCard }
    }

    @Test(timeout = 30_000)
    fun `search hint and live scoped plural count are honest`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        DocFixtures.saved(repo, app, pages = 1, name = "Physics notes")
        repo.createFolder("Travel")
        val travel = repo.listDocuments().first { it.name == "Rent receipt" }.id
        repo.setFolder(listOf(travel), "Travel")

        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            waitFor("library loaded") { cards(activity).size == 2 }
            val field = activity.findViewById<EditText>(R.id.swHomeSearch)
            assertEquals("Search documents and pages", field.hint.toString())
            val status = activity.findViewById<TextView>(R.id.uxL1SearchStatus)
            assertNotNull("the live status exists", status)

            field.setText("rent")
            idle()
            waitFor("one result") { status.text.toString().contains("1 result") }
            assertTrue("the count is scoped: ${status.text}", status.text.toString().contains("All documents"))
            assertEquals(status.text.toString(), status.contentDescription.toString())
            assertEquals(View.VISIBLE, status.visibility)

            // A folder scope must be named, not presented as a global result. Use the
            // same snapshot API the shell uses on restore to avoid driving a chooser sheet.
            val host = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0) as ViewGroup
            val lib = host.getChildAt(0) as LibraryScreen
            lib.restoreLibraryState(
                com.scanwhirl.app.ui.shell.LibraryUiState(
                    "rent", "Travel", null, Prefs.LIB_NEWEST, emptyList(), false, 0
                )
            )
            idle()
            waitFor("the scope names Travel") { status.text.toString().contains("Travel") }
            assertTrue("plural is not '1 results'", !status.text.toString().contains("1 results"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `selection says Finish selection and Move to Trash and keeps Undo`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Selected one")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            waitFor("library loaded") { cards(activity).isNotEmpty() }
            cards(activity).first { it.doc?.id == id }.performLongClick()
            idle()
            assertEquals(
                "Finish selection",
                activity.findViewById<TextView>(R.id.swSelDone).text.toString()
            )
            val trash = activity.findViewById<View>(R.id.swSelDelete)
            assertEquals("Move to Trash", trash.contentDescription.toString())
            val chip = trash as com.scanwhirl.app.ui.kit.SwChip
            assertEquals("Move to Trash", chip.labelView.text.toString())
            assertNotNull("the action keeps its icon", chip.iconView.drawable)

            trash.performClick()
            idle()
            val confirm = ShadowDialog.getLatestDialog()
            assertNotNull("confirmation survives", confirm)
            confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
            waitFor("document is trashed") { repo.getDocument(id) == null }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `decided EN and HI copy is present in the lane resources`() {
        fun text(path: String, name: String): String {
            val xml = java.io.File(path).readText()
            val block = xml.substringAfter("name=\"$name\"").substringBefore("</string>")
            return block.substringAfter(">").trim()
        }
        val en = "src/main/res/values/strings_ux130_l1.xml"
        val hi = "src/main/res/values-hi/strings_ux130_l1.xml"
        assertEquals("Search documents and pages", text(en, "ux130_l1_search_hint"))
        assertEquals("दस्तावेज़ और पन्ने खोजें", text(hi, "ux130_l1_search_hint"))
        assertEquals("Move to Trash", text(en, "ux130_l1_move_to_trash"))
        assertEquals("हटाई गई फ़ाइलों में भेजें", text(hi, "ux130_l1_move_to_trash"))
        assertEquals("Finish selection", text(en, "ux130_l1_finish_selection"))
        assertTrue("Hindi uses चयन, not a loanword", text(hi, "ux130_l1_finish_selection").contains("चयन"))
    }
}
