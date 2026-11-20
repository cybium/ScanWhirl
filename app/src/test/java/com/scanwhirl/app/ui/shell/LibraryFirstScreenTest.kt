package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.runBlocking
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-B1-LIBRARY-CREATE (UI_CRITIQUE_GPT_2026-11-19 #1): the populated Library must start with
 * the person's documents. The old Home spent its first screen on three creation tiles and six
 * quick tools, so a library of sixteen documents showed none of them above the floating dock.
 *
 * Contract checked here, at 360dp, English and Hindi, 100 % and 200 % font:
 *  - the first document row is fully above the dock;
 *  - Home no longer renders the creation tiles or the quick tools (the Create sheet and the
 *    Tools tab replace them), and the six tools are still reachable on the Tools tab;
 *  - an empty library keeps a visible import shortcut plus the trust line;
 *  - About carries the same trust line.
 */
abstract class LibraryFirstScreenBase {

    protected lateinit var app: Application
    protected lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        Prefs.setLocaleTag(app, "")
        RuntimeEnvironment.setFontScale(1.0f)
        DocFixtures.saved(repo, app, pages = 2, name = "Rent receipt October")
        DocFixtures.saved(repo, app, pages = 6, name = "Physics notes chapter 4")
        DocFixtures.saved(repo, app, pages = 1, name = "Offer letter")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.setLocaleTag(app, "")
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lays the shell out exactly as a 360dp wide phone would. */
    private fun layoutRoot(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    private fun topIn(view: View, root: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== root) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun assertFirstRowAboveDock(activity: ShellActivity, scale: Float) {
        val root = layoutRoot(activity)
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        assertTrue("the seeded library renders rows", list.childCount > 0)
        val card = list.getChildAt(0)
        assertTrue("the first row is a document card", card is DocCard)
        assertTrue("the first row has height", card.height > 0)
        val dockTop = minOf(
            topIn(activity.findViewById<View>(R.id.swNavScan), root),
            topIn(activity.findViewById<View>(R.id.swShellNav), root)
        )
        val cardTop = topIn(card, root)
        val cardBottom = cardTop + card.height
        assertTrue(
            "at ${scale}x (${AppLanguage.currentTag(activity)}) the first row is not on the " +
                "first screen: top=$cardTop bottom=$cardBottom dockTop=$dockTop; " +
                "the first screen must show a real document, not chrome",
            cardBottom <= dockTop
        )
    }

    @Test
    fun `populated library shows a document row above the dock at 100 percent`() {
        RuntimeEnvironment.setFontScale(1.0f)
        launch { activity -> assertFirstRowAboveDock(activity, 1.0f) }
    }

    @Test
    fun `populated library shows a document row above the dock at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        launch { activity -> assertFirstRowAboveDock(activity, 2.0f) }
    }

    @Test
    fun `home no longer renders creation tiles or quick tools`() {
        launch { activity ->
            assertNull(
                "the Home creation row is replaced by the Create sheet",
                activity.findViewById<View>(R.id.swCreateRow)
            )
            assertNull(
                "the Home quick tools are replaced by the Tools tab",
                activity.findViewById<View>(R.id.swQuickRow)
            )
            assertNull(activity.findViewById<View>(R.id.swQuickRow2))
            assertNull(activity.findViewById<View>(R.id.swQuickRow3))
        }
    }

    @Test
    fun `the six quick tools stay reachable on the Tools tab`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            // T1 owner-approved 2026-11-20: every job lists all of its tools, so the whole
            // catalogue is reachable without opening anything.
            openAllToolsDoors(activity)
            val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
            // T2 owner 2026-11-21: icon grid
            val ids = ToolsHubScreen.tiles(body).mapNotNull { it.tool?.id }.toSet()
            for (id in listOf("merge", "compress", "sign", "protect", "to_word", "read_text")) {
                assertTrue("$id is still reachable in Tools", id in ids)
            }
        }
    }

    @Test
    fun `empty library keeps a visible import shortcut and the trust line`() {
        // An empty library: remove this test's seeded fixtures before the shell starts.
        runBlocking { repo.listDocuments().forEach { repo.deleteDocument(it.id) } }
        launch { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            assertEquals("the library is empty before the check", 0, list.childCount)
            val empty = activity.findViewById<View>(R.id.swHomeEmpty)
            assertNotNull("the empty state is shown", empty)
            assertTrue(empty.isVisible)
            val import = findText(empty, activity.getString(R.string.empty_import_button))
            assertNotNull(
                "an empty library offers the import shortcut \"${activity.getString(R.string.empty_import_button)}\"",
                import
            )
            val trust = findText(empty, activity.getString(R.string.trust_offline_no_ads))
            assertNotNull("the empty library keeps the trust line", trust)
        }
    }

    @Test
    fun `about carries the trust line`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            activity.findViewById<View>(R.id.swMeAbout).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("About opens", dialog)
            val message = dialog!!.findViewById<TextView>(R.id.swSheetMessage)
            assertNotNull("About shows its text", message)
            val trust = activity.getString(R.string.trust_offline_no_ads)
            assertTrue(
                "About must say \"$trust\" (found \"${message.text}\")",
                message.text.contains(trust)
            )
            dialog.dismiss()
        }
    }

    private fun findText(root: View?, text: String): TextView? {
        if (root == null) return null
        if (root is TextView && root.text?.toString() == text && root.isVisible) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }
}

/** The phone the owner checks: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LibraryFirstScreenEnTest : LibraryFirstScreenBase()

/** The same phone in Hindi, where the old header also clipped at 200 %. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class LibraryFirstScreenHiTest : LibraryFirstScreenBase()
