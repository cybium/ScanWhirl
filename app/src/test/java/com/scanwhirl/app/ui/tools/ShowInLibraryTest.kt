package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 01_home_A / 03_tools_hub — "Show in library" must open the Library tab with the new
 * document highlighted and scrolled into view (not the Tools tab).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShowInLibraryTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun cards(root: View): List<DocCard> {
        val found = mutableListOf<DocCard>()
        fun walk(view: View) {
            if (view is DocCard) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }

    @Test
    fun `the result button asks the library to reveal the new document`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Reveal me")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultLibrary).performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                assertNotNull("the shell must open", started)
                assertEquals(ShellActivity::class.java.name, started.component?.className)
                assertEquals(id, started.getStringExtra(ShellActivity.EXTRA_SHOW_DOC_ID))
            }
        }
    }

    @Test
    fun `the library tab highlights and scrolls to the revealed document`() {
        val target = DocFixtures.saved(repo, app, pages = 2, name = "Reveal me")
        DocFixtures.saved(repo, app, pages = 1, name = "Other")

        ActivityScenario.launch<ShellActivity>(ShellActivity.intent(app, target)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val library = findLibrary(activity)
                assertNotNull("the library tab is shown", library)
                idle()
                val targetCard = cards(activity.findViewById(R.id.swDocList))
                    .firstOrNull { it.doc?.id == target }
                assertNotNull("the new document has a card", targetCard)
                assertTrue("the new card is highlighted", targetCard!!.isSelected)
                assertTrue("the Tools tab is not shown", activity.findViewById<View>(R.id.swToolsSearch) == null)
            }
        }
    }

    /** A card stays highlighted after the shell's own onResume reload. */
    @Test
    fun `the highlight survives a reload`() {
        val target = DocFixtures.saved(repo, app, pages = 1, name = "Still here")
        ActivityScenario.launch<ShellActivity>(ShellActivity.intent(app, target)).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val library = findLibrary(activity)
                library?.reload()
                idle()
                val card = cards(activity.findViewById(R.id.swDocList))
                    .firstOrNull { it.doc?.id == target }
                assertNotNull(card)
                assertTrue("highlight lost on reload", card!!.isSelected)
            }
        }
    }

    private fun findLibrary(activity: ShellActivity): LibraryScreen? {
        var found: LibraryScreen? = null
        fun walk(view: View) {
            if (view is LibraryScreen) found = view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return found
    }
}
