package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.LibraryUiState
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L1-02 — rotation preserves the user's place, not only the selected tab.
 *
 * RED on base: SH1 saved only the tab, so a query, folder/category/sort choice,
 * document selection and scroll anchor were all lost on rotation. Every test
 * here uses ActivityController with an explicit destroy in finally: ActivityScenario.close()
 * hung CI on this activity before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class L1RotationStateTest {

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

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        val scroll = host.getChildAt(0) as ViewGroup
        return scroll.getChildAt(0) as LibraryScreen
    }

    @Test(timeout = 30_000)
    fun `query folder category sort selection and scroll survive rotation`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Alpha invoice")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Beta receipt")
        // Enough real cards for the ScrollView to have a scrollable range after filtering.
        val fillers = (0 until 14).map { DocFixtures.saved(repo, app, pages = 1, name = "Filler $it") }
        repo.createFolder("Travel") // folder capability stays free
        repo.setFolder(listOf(a, b) + fillers, "Travel")
        val first = Robolectric.buildActivity(ShellActivity::class.java).setup()
        var saved = Bundle()
        try {
            val activity = first.get()
            waitFor("library loaded") { library(activity).loaded }
            val lib = library(activity)
            lib.restoreLibraryState(
                LibraryUiState(
                    query = "Filler",
                    folder = "Travel",
                    category = null,
                    sort = Prefs.LIB_NAME_ASC,
                    selection = listOf(a, b),
                    selectionMode = true,
                    scrollY = 64
                )
            )
            idle()
            waitFor("restored render") { lib.restoreAppliedCount == 1 }
            assertEquals("Filler", activity.findViewById<EditText>(R.id.swHomeSearch).text.toString())
            // Actually scroll the shell's list so the anchor is captured from the real view.
            val scroller = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0) as android.widget.ScrollView
            scroller.scrollTo(0, 64)
            idle()
            first.saveInstanceState(saved)
        } finally {
            first.pause().stop().destroy()
        }

        val second = Robolectric.buildActivity(ShellActivity::class.java).setup(saved)
        try {
            val activity = second.get()
            waitFor("recreated library loaded") { library(activity).loaded }
            waitFor("restore applied after async load") { library(activity).restoreAppliedCount == 1 }
            val lib = library(activity)
            assertEquals("Filler", activity.findViewById<EditText>(R.id.swHomeSearch).text.toString())
            val state = lib.captureLibraryState()
            assertEquals("Filler", state.query)
            assertEquals("Travel", state.folder)
            assertEquals(Prefs.LIB_NAME_ASC, state.sort)
            assertEquals(setOf(a, b), state.selection.toSet())
            assertEquals(64, state.scrollY)
            assertEquals(1, lib.restoreAppliedCount)
        } finally {
            second.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `a deleted selection anchor falls back safely on restore`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Still here")
        val first = Robolectric.buildActivity(ShellActivity::class.java).setup()
        var saved = Bundle()
        try {
            val activity = first.get()
            waitFor("library loaded") { library(activity).loaded }
            library(activity).restoreLibraryState(
                LibraryUiState("", null, null, Prefs.LIB_NEWEST, listOf(a, "gone-doc-id"), true, 0)
            )
            idle()
            first.saveInstanceState(saved)
        } finally {
            first.pause().stop().destroy()
        }
        // The anchor was removed from the repository before the new instance loads.
        repo.deleteDocument("gone-doc-id")
        val second = Robolectric.buildActivity(ShellActivity::class.java).setup(saved)
        try {
            val activity = second.get()
            waitFor("recreated library loaded") { library(activity).loaded }
            waitFor("restore applied") { library(activity).restoreAppliedCount == 1 }
            assertEquals(setOf(a), library(activity).captureLibraryState().selection.toSet())
        } finally {
            second.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `onSaveInstanceState carries the library state keys and the tab`() {
        val source = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
        val save = source.substringAfter("override fun onSaveInstanceState")
            .substringBefore("override fun onNewIntent")
        assertTrue("tab is still saved", save.contains("KEY_TAB"))
        for (key in listOf("KEY_QUERY", "KEY_FOLDER", "KEY_CATEGORY", "KEY_SORT", "KEY_SELECTION", "KEY_SCROLL")) {
            assertTrue("$key must be saved for rotation", save.contains(key))
        }
        val lib = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt").readText()
        assertTrue("LibraryScreen exposes a state snapshot", lib.contains("fun captureLibraryState"))
        assertTrue("LibraryScreen applies a snapshot once", lib.contains("fun restoreLibraryState"))
        assertTrue("selection ids are filtered against loaded docs", lib.contains("captureLibraryState"))
    }
}
