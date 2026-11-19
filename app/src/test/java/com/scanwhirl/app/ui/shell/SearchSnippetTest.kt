package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.Typeface
import android.os.Looper
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.kit.DocCard
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
import java.io.File
import java.security.MessageDigest

/**
 * T28 · Return job: a Home search for a word inside a saved scan shows WHERE it
 * matched (one stored-text snippet with the word bold, "Page N") and opening the
 * row lands on that page. Name-only matches look exactly as before.
 *
 * Fixtures are fictional 3-page documents written through the real repository and
 * OCR store; no owner media or production app data is used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SearchSnippetTest {

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

    // ---- fixtures ----------------------------------------------------------

    /** A committed fictional scan with stored OCR text on chosen pages. */
    private fun fixture(
        name: String,
        pages: Int = 3,
        ocr: Map<Int, String> = emptyMap(),
        vaulted: Boolean = false
    ): String {
        val id = DocFixtures.saved(repo, app, pages = pages, name = name)
        if (ocr.isNotEmpty()) {
            repo.ocrStore.putTexts(id, ocr.mapKeys { "page_%03d.jpg".format(it.key) })
        }
        if (vaulted) File(File(repo.docsDir, id), "vault.flag").writeText("1")
        return id
    }

    private fun filler(pages: Int) = (0 until pages).associateWith { "Filler sheet ${it + 1}." }

    private fun snapshotLibrary(): Map<String, String> =
        repo.docsDir.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(repo.docsDir).path to
                MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } +
                "@" + f.lastModified()
        }

    // ---- helpers -----------------------------------------------------------

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

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? DocCard }
    }

    /** Actually on screen: this view and every ancestor is VISIBLE. */
    private fun shown(view: View?): Boolean {
        var current: View? = view ?: return false
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun search(activity: ShellActivity, q: String) {
        activity.findViewById<EditText>(R.id.swHomeSearch).setText(q)
        idle()
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                waitFor("library loaded", condition = { cards(activity).isNotEmpty() })
                block(activity)
            }
        }
    }

    // ---- the improvement ---------------------------------------------------

    @Test
    fun `word on page 2 shows Page 2 with a bold snippet and opens at that page`() {
        val id = fixture(
            "Scholarship file",
            ocr = filler(3) + mapOf(1 to "Reference ZEPHYRQUILL 77, issued at the counter.")
        )
        val before = snapshotLibrary()
        launch { activity ->
            search(activity, "zephyrquill")
            waitFor("the text hit row appears") {
                cards(activity).singleOrNull()?.let {
                    shown(it.findViewById<TextView>(R.id.swSearchSnippet))
                } == true
            }
            val card = cards(activity).single()
            assertEquals(id, card.doc?.id)

            val page = card.findViewById<TextView>(R.id.swSearchPage)
            assertNotNull("the row names the matching page", page)
            assertTrue("the page line is on screen", shown(page))
            assertEquals("Page 2", page.text.toString())

            val snippet = card.findViewById<TextView>(R.id.swSearchSnippet)
            assertNotNull("the row quotes the stored text", snippet)
            assertTrue("the snippet is on screen", shown(snippet))
            val text = snippet.text.toString()
            assertTrue("snippet contains the match: $text", text.contains("ZEPHYRQUILL"))
            assertTrue("snippet is short: ${text.length}", text.length <= 100)

            // The matched word is bold, not colour-only.
            val spanned = snippet.text as Spanned
            val at = text.indexOf("ZEPHYRQUILL")
            val bold = spanned.getSpans(at, at + 1, StyleSpan::class.java)
            assertTrue("the matched word carries a bold span", bold.any { it.style == Typeface.BOLD })

            // TalkBack speaks the page and the matched text.
            val desc = card.contentDescription?.toString().orEmpty()
            assertTrue("row description names page 2: $desc", desc.contains("page 2", ignoreCase = true))
            assertTrue("row description quotes the match: $desc", desc.contains("ZEPHYRQUILL"))

            card.performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(PdfViewerActivity::class.java.name, started.component?.className)
            assertEquals(id, started.getStringExtra("doc_id"))
            assertEquals("page index is 1 for Page 2", 1, started.getIntExtra("start_page", -1))
            assertEquals("page_001.jpg", started.getStringExtra("start_page_key"))
            assertEquals("zephyrquill", started.getStringExtra("find_query"))
        }
        assertEquals("searching and opening change no file", before, snapshotLibrary())
        assertEquals(1, repo.listDocuments().size)
    }

    @Test
    fun `name-only match keeps today's look and opens the document screen`() {
        val id = fixture("Zephyrquill notes", ocr = filler(3))
        launch { activity ->
            search(activity, "zephyrquill")
            waitFor("the name row appears") {
                cards(activity).singleOrNull()?.doc?.id == id
            }
            val card = cards(activity).single()
            assertFalse(
                "no page line for a name-only match",
                shown(card.findViewById<TextView>(R.id.swSearchPage))
            )
            assertFalse(
                "no snippet for a name-only match",
                shown(card.findViewById<TextView>(R.id.swSearchSnippet))
            )
            card.performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(DocumentViewerActivity::class.java.name, started.component?.className)
            assertEquals(id, started.getStringExtra("doc_id"))
        }
    }

    @Test
    fun `long page text is trimmed with an ellipsis at word boundaries`() {
        val long = "alpha bravo charlie delta echo foxtrot golf hotel india juliet NEEDLE " +
            "kilo lima mike november oscar papa quebec romeo sierra tango uniform victor whiskey " +
            "xray yankee zulu"
        fixture("Long note", ocr = filler(3) + mapOf(1 to long))
        launch { activity ->
            search(activity, "needle")
            waitFor("the snippet appears") {
                cards(activity).singleOrNull()?.let {
                    shown(it.findViewById<TextView>(R.id.swSearchSnippet))
                } == true
            }
            val snippet = cards(activity).single()
                .findViewById<TextView>(R.id.swSearchSnippet).text.toString()
            assertTrue("left cut is marked: $snippet", snippet.startsWith("…"))
            assertTrue("right cut is marked: $snippet", snippet.endsWith("…"))
            assertTrue("contains the match: $snippet", snippet.contains("NEEDLE"))
            val body = snippet.removePrefix("…").removeSuffix("…")
            val words = body.split(" ").filter { it.isNotEmpty() }
            assertTrue("starts on a whole word: ${words.first()}", long.contains(" ${words.first()} "))
            assertTrue("ends on a whole word: ${words.last()}", long.contains(" ${words.last()} "))
            assertFalse("no doubled spaces from cutting: $body", body.contains("  "))
        }
    }

    @Test
    fun `a Vault document never appears in search and contributes no snippet`() {
        fixture("Private passbook", ocr = filler(3) + mapOf(1 to "VAULTONLYWORD hidden"), vaulted = true)
        val visible = fixture("Shared receipt", ocr = filler(3) + mapOf(1 to "SHAREDWORD visible"))
        launch { activity ->
            search(activity, "vaultonlyword")
            waitFor("the vault-only query settles") {
                cards(activity).isEmpty()
            }
            assertTrue("Vault items are not in search", cards(activity).isEmpty())
            assertTrue("Vault items are not in the library list", repo.listDocuments().none { it.isVaulted })

            search(activity, "sharedword")
            waitFor("the ordinary text hit appears") {
                cards(activity).singleOrNull()?.doc?.id == visible
            }
            val card = cards(activity).single()
            assertEquals(visible, card.doc?.id)
            assertTrue(
                "the ordinary match still shows its page",
                shown(card.findViewById<TextView>(R.id.swSearchPage))
            )
            assertFalse(
                "no Vault text leaks into the row",
                card.contentDescription?.toString().orEmpty().contains("VAULTONLYWORD")
            )
        }
    }
}
