package com.scanwhirl.app.ux130.v2l
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
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
import org.robolectric.shadows.ShadowDialog

/**
 * V2L: "Move 1 documents to Trash?" was ungrammatical. The confirmation now uses a
 * lane plural resource in both languages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2TrashPluralTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun confirmTitle(count: Int, lang: String): String {
        val ids = (0 until count).map { DocFixtures.saved(repo, app, pages = 1, name = "Trash $it") }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val cards = activity.findViewById<View>(R.id.swDocList) as ViewGroup
            // Enter selection on the first card, then tick any further cards.
            cards.getChildAt(0).performLongClick()
            idle()
            for (i in 1 until count) {
                cards.getChildAt(i).performClick()
                idle()
            }
            activity.findViewById<View>(R.id.swSelDelete).performClick()
            idle()
            return ShadowDialog.getLatestDialog()
                .findViewById<TextView>(R.id.swSheetTitle).text.toString()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `one and many use different English plural forms`() {
        assertEquals("Move 1 document to Trash?", confirmTitle(1, "en"))
        assertEquals("Move 2 documents to Trash?", confirmTitle(2, "en"))
    }

    @Test(timeout = 30_000)
    fun `the lane plural resource carries both English and Hindi forms`() {
        val en = java.io.File("src/main/res/values/strings_v2l.xml").readText()
        val hi = java.io.File("src/main/res/values-hi/strings_v2l.xml").readText()
        assertTrue(en.contains("Move %1\$d document to Trash?"))
        assertTrue(en.contains("Move %1\$d documents to Trash?"))
        assertTrue(hi.contains("name=\"v2l_move_to_trash_title\""))
        assertTrue(hi.contains("हटाई गई फ़ाइलों में भेजें?"))
    }
}
