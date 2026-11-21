package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException

/**
 * L1lib UI regressions (Hy4 hunt, lead-confirmed defects #1, #3, #4). Fictional fixtures only.
 *
 *  #1 150 documents: the 150th must be reachable (the old hard cap of 60 hid documents 61+).
 *  #3 a disk-full failure during a library write shows an honest message and never crashes.
 *  #4 library search uses word boundaries + accent folding (SearchMatcher), not substring.
 *
 * The Tags off-main regression (#2) lives in [LibraryTagsOffMainTest] because it needs the
 * listTags test probe; keeping it apart lets these three tests fail with plain assertions on
 * the unfixed build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryL1LibTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        Prefs.setLocaleTag(app, "")
        Durable.resetForTests()
        ShadowToast.reset()
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    @After
    fun tearDown() {
        Durable.resetForTests()
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        Prefs.resetQuickTools(app)
        ShadowToast.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
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
        val scroll = host.getChildAt(0) as ScrollView
        return scroll.getChildAt(0) as LibraryScreen
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? DocCard }
    }

    private fun search(activity: ShellActivity, q: String) {
        activity.findViewById<EditText>(R.id.swHomeSearch).setText(q)
        idle()
    }

    // ---- #1: the 150th document is reachable ------------------------------

    @Test
    fun `every document is reachable not just the first sixty`() {
        repeat(150) { index ->
            val dir = File(repo.docsDir, "bulk%03d".format(index)).apply { mkdirs() }
            File(dir, "meta.txt").writeText("Bulk file $index\n${1_700_000_000_000L + index}\nother\n")
            File(dir, "page_000.jpg").writeBytes(ByteArray(8) { (it + 1).toByte() })
        }
        assertEquals(150, repo.listDocuments().size)

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("the first page renders") { cards(activity).isNotEmpty() }
                val count = activity.findViewById<TextView>(R.id.swDocCount)
                assertTrue("the count names all 150 documents, was '${count.text}'", count.text.contains("150"))
                assertEquals("the first page shows 60 cards", 60, cards(activity).size)

                // Drive the same append action the scroll listener uses. Reflection keeps this
                // test compiling against the unfixed build, where it fails (RED) with
                // NoSuchMethodException instead of a compile error.
                val lib = library(activity)
                val loadMore = lib.javaClass.getDeclaredMethod("loadMoreCards").apply { isAccessible = true }
                var guard = 0
                while (guard++ < 20 && loadMore.invoke(lib) == true) idle()
                idle()

                assertEquals("all 150 cards are reachable", 150, cards(activity).size)
                assertTrue(
                    "the 150th document is rendered",
                    cards(activity).any { it.doc?.name == "Bulk file 149" }
                )
                assertTrue("the count still matches after loading", count.text.contains("150"))
            }
        }
    }

    // ---- #3: a failed write never crashes ---------------------------------

    @Test
    fun `a disk full library write shows an honest message and reloads`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Move me")
        ShadowToast.reset()

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("the card renders") { cards(activity).isNotEmpty() }
                cards(activity).first().performLongClick()
                idle()
                activity.findViewById<View>(R.id.swSelMove).performClick()
                idle()
                val move = ShadowDialog.getLatestDialog()
                assertNotNull("the Move sheet opened", move)
                move!!.findViewById<ViewGroup>(R.id.swSheetItems).getChildAt(0).performClick()

                // Disk full exactly when the metadata rewrite is synced.
                Durable.beforeFileSync = { file ->
                    if (file.name == "meta.txt.tmp") throw IOException("fictional full disk")
                }
                idle()

                assertTrue(
                    "an honest failure message is shown, was '${ShadowToast.getTextOfLatestToast()}'",
                    waitForToast("Could not")
                )
                assertFalse("the activity did not crash", activity.isFinishing)
            }
        }
    }

    private fun waitForToast(fragment: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (ShadowToast.getTextOfLatestToast()?.toString()?.contains(fragment) == true) return true
            Thread.sleep(10)
        }
        idle()
        return ShadowToast.getTextOfLatestToast()?.toString()?.contains(fragment) == true
    }

    // ---- #4: word-boundary + accent-aware library search ------------------

    @Test
    fun `library search uses word boundaries and folds accents`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Form 2")
        DocFixtures.saved(repo, app, pages = 1, name = "Form 2026")
        val cafe = DocFixtures.saved(repo, app, pages = 1, name = "Receipt")
        repo.ocrStore.putTexts(cafe, mapOf("page_000.jpg" to "Total at the cafe was small."))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("the cards render") { cards(activity).size == 3 }

                search(activity, "2")
                waitFor("query 2 settles") {
                    cards(activity).any { it.doc?.name == "Form 2" } &&
                        cards(activity).none { it.doc?.name == "Form 2026" }
                }
                val names = cards(activity).mapNotNull { it.doc?.name }.toSet()
                assertTrue("2 finds Form 2", "Form 2" in names)
                assertFalse("2 must not match 2026", "Form 2026" in names)

                search(activity, "café")
                waitFor("the accent-insensitive OCR hit appears") {
                    cards(activity).any { it.doc?.id == cafe }
                }
                assertEquals("only the cafe document matches", cafe, cards(activity).single().doc?.id)
            }
        }
    }
}
