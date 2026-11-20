package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.text.Spanned
import android.text.style.StyleSpan
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
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T28 · the same search-result evidence in Hindi: the judged word पन्ना for the
 * page, a bold snippet, a spoken row that names the page, and the tap landing
 * on that page. The fixture text is fictional and English; only the UI is Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class SearchSnippetHindiTest {

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
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
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

    private fun topIn(root: View, child: View): Int {
        var value = 0
        var view: View? = child
        while (view != null && view !== root) {
            value += view.top
            view = view.parent as? View
        }
        return value
    }

    @Test
    fun `Hindi row says पन्ना 2 with a bold snippet and opens that page`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "छात्रवृत्ति फ़ाइल")
        repo.ocrStore.putTexts(
            id,
            mapOf(
                "page_000.jpg" to "पहला पन्ना।",
                "page_001.jpg" to "Reference ZEPHYRQUILL 77, issued at the counter.",
                "page_002.jpg" to "तीसरा पन्ना।"
            )
        )

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("library loaded") { cards(activity).isNotEmpty() }
                activity.findViewById<EditText>(R.id.swHomeSearch).setText("zephyrquill")
                idle()
                waitFor("Hindi text hit row") {
                    cards(activity).singleOrNull()
                        ?.findViewById<TextView>(R.id.swSearchSnippet)?.isVisible == true
                }
                val card = cards(activity).single()
                assertEquals(id, card.doc?.id)

                val page = card.findViewById<TextView>(R.id.swSearchPage)
                assertNotNull("page line", page)
                assertEquals("पन्ना 2", page.text.toString())

                val snippet = card.findViewById<TextView>(R.id.swSearchSnippet)
                val text = snippet.text.toString()
                assertTrue("snippet contains the match: $text", text.contains("ZEPHYRQUILL"))
                val at = text.indexOf("ZEPHYRQUILL")
                assertTrue(
                    "the matched word carries a bold span",
                    (snippet.text as Spanned).getSpans(at, at + 1, StyleSpan::class.java)
                        .any { it.style == android.graphics.Typeface.BOLD }
                )

                val desc = card.contentDescription?.toString().orEmpty()
                assertTrue("spoken row names पन्ना 2: $desc", desc.contains("पन्ना 2"))
                assertTrue("spoken row quotes the match: $desc", desc.contains("ZEPHYRQUILL"))

                card.performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(PdfViewerActivity::class.java.name, started.component?.className)
                assertEquals(1, started.getIntExtra("start_page", -1))
                assertEquals("page_001.jpg", started.getStringExtra("start_page_key"))
            }
        }
    }

    @Test
    fun `Hindi row keeps page and snippet under the name with one overflow`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "छोटी फ़ाइल")
        repo.ocrStore.putTexts(
            id,
            mapOf(
                "page_000.jpg" to "पहला पन्ना।",
                "page_001.jpg" to "Reference ZEPHYRQUILL 77, issued at the counter.",
                "page_002.jpg" to "तीसरा पन्ना।"
            )
        )

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("library loaded") { cards(activity).isNotEmpty() }
                activity.findViewById<EditText>(R.id.swHomeSearch).setText("zephyrquill")
                idle()
                waitFor("Hindi text hit row") {
                    cards(activity).singleOrNull()
                        ?.findViewById<TextView>(R.id.swSearchSnippet)?.isVisible == true
                }
                val card = cards(activity).single()
                layoutRoot(activity)

                assertTrue(
                    "Hindi row keeps one named overflow at compact width",
                    card.moreButton.isVisible
                )
                assertTrue(
                    "the direct Share folds into More in Hindi too",
                    !card.shareButton.isVisible
                )
                val nameBottom = topIn(card, card.nameView) + card.nameView.height
                assertTrue(
                    "पन्ना 2 stays below the name",
                    topIn(card, card.searchPage) >= nameBottom
                )
                assertTrue(
                    "the Hindi snippet stays below the name",
                    topIn(card, card.searchSnippet) >= nameBottom
                )
                assertEquals(
                    "पन्ना 2",
                    card.findViewById<TextView>(R.id.swSearchPage).text.toString()
                )
            }
        }
    }
}
