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
import androidx.core.view.isVisible
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
        vaulted: Boolean = false,
        legacyDuplicate: Boolean = false
    ): String {
        val id = if (legacyDuplicate) {
            DocFixtures.savedLegacyDuplicate(repo, app, pages = pages, name = name)
        } else {
            DocFixtures.saved(repo, app, pages = pages, name = name)
        }
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

    /** Lays the shell out as a phone would, so row geometry can be asserted. */
    private fun layoutRoot(activity: ShellActivity): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    /** The child's top inside [root]'s coordinate space (layout offsets, never screen reads). */
    private fun topIn(root: View, child: View): Int {
        var value = 0
        var view: View? = child
        while (view != null && view !== root) {
            value += view.top
            view = view.parent as? View
        }
        return value
    }

    /** The child's left inside [root]'s coordinate space. */
    private fun leftIn(root: View, child: View): Int {
        var value = 0
        var view: View? = child
        while (view != null && view !== root) {
            value += view.left
            view = view.parent as? View
        }
        return value
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

    // ---- UI-W4: search identity ------------------------------------------

    @Test
    fun `one overflow keeps page and snippet under the name at compact width`() {
        fixture(
            "Compact scholarship",
            ocr = filler(3) + mapOf(1 to "Counter reference ZEPHYRQUILL 77.")
        )
        launch { activity ->
            search(activity, "zephyrquill")
            waitFor("the text hit row appears") {
                cards(activity).singleOrNull()?.let {
                    shown(it.findViewById<TextView>(R.id.swSearchSnippet))
                } == true
            }
            val card = cards(activity).single()
            layoutRoot(activity)

            assertFalse(
                "at compact width Share folds into the named More",
                card.shareButton.isVisible
            )
            assertTrue("the named More stays on the row", card.moreButton.isVisible)
            assertEquals(
                "exactly one trailing action remains",
                1,
                listOf(card.shareButton, card.moreButton).count { it.isVisible }
            )

            val nameBottom = topIn(card, card.nameView) + card.nameView.height
            assertTrue(
                "the page line is below the name (page top ${
                    topIn(card, card.searchPage)
                }, name bottom $nameBottom)",
                topIn(card, card.searchPage) >= nameBottom
            )
            assertTrue(
                "the snippet is below the name",
                topIn(card, card.searchSnippet) >= nameBottom
            )
            assertTrue(
                "the snippet starts under the name, not beside the overflow",
                leftIn(card, card.searchSnippet) >= leftIn(card, card.nameView)
            )
            assertTrue(
                "the snippet ends before the overflow control",
                leftIn(card, card.searchSnippet) + card.searchSnippet.width <=
                    leftIn(card, card.moreButton)
            )
        }
    }

    @Test
    fun `share also folds into More at 200 percent font`() {
        RuntimeEnvironment.setFontScale(2.0f)
        try {
            fixture("Large font note", ocr = filler(3))
            launch { activity ->
                search(activity, "large font")
                waitFor("the name hit row appears") { cards(activity).size == 1 }
                val card = cards(activity).single()
                assertFalse("Share is folded at 200%", card.shareButton.isVisible)
                assertTrue("More stays at 200%", card.moreButton.isVisible)
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    @Test
    fun `near identical names differ by pages size and date in full`() {
        fixture("Bill", pages = 1, ocr = filler(1))
        // Same visible title on both rows: a legacy duplicate is never retroactively renamed.
        fixture("Bill", pages = 3, ocr = filler(3), legacyDuplicate = true)
        launch { activity ->
            search(activity, "bill")
            waitFor("both same-name rows appear") {
                cards(activity).count { it.doc?.name == "Bill" } == 2
            }
            layoutRoot(activity)
            val rows = cards(activity).filter { it.doc?.name == "Bill" }
            assertEquals(2, rows.size)

            val metas = rows.map { it.metaView.text.toString() }
            assertEquals("the meta line separates the two rows", 2, metas.toSet().size)
            for (meta in metas) {
                assertTrue("$meta names the pages", Regex("[0-9]+\\u00A0page").containsMatchIn(meta))
                assertTrue("$meta names the size", Regex("[0-9]+\\u00A0(KB|MB|B)").containsMatchIn(meta))
                assertTrue(
                    "$meta names the date",
                    meta.contains("Today") || meta.contains("Yesterday") ||
                        Regex("[0-9]{1,2} [A-Z][a-z]{2}").containsMatchIn(meta)
                )
            }
            for (row in rows) {
                val layout = row.metaView.layout
                assertNotNull("the meta was laid out", layout)
                // Council §3: OLD = the meta wrapped to two lines so the date stayed
                // readable at this compact (320dp) width. NEW = ONE line at 100%,
                // designed for 360/411dp; at 320dp END ellipsis is expected. The full
                // text (pages, decimal size, date) stays in the TextView for TalkBack,
                // and the 200% no-clip assertions in LibraryRailMetaFitTest are untouched.
                assertEquals("the meta is one line at 100%", 1, row.metaView.maxLines)
                assertTrue(
                    "the full meta stays in the view for TalkBack: ${row.metaView.text}",
                    row.metaView.text.toString().contains("Today") ||
                        row.metaView.text.toString().contains("Yesterday")
                )
            }
        }
    }

    @Test
    fun `a locked document is never found by its stored text and shows no thumbnail`() {
        val secret = "SALARYSECRET"
        val id = fixture(
            "Pay slip",
            ocr = filler(3) + mapOf(1 to "$secret 99 is on this page.")
        )
        kotlinx.coroutines.runBlocking { repo.protectPdf(id, "1234") }
        launch { activity ->
            search(activity, secret.lowercase())
            waitFor("the locked-text query settles") {
                cards(activity).isEmpty()
            }
            assertTrue(
                "locked OCR is never a search route",
                cards(activity).isEmpty()
            )

            search(activity, "pay slip")
            waitFor("the locked row is still found by its name") {
                cards(activity).singleOrNull()?.doc?.id == id
            }
            val card = cards(activity).single()
            assertFalse(
                "no page evidence for a locked row",
                shown(card.findViewById<TextView>(R.id.swSearchPage))
            )
            assertFalse(
                "no snippet for a locked row",
                shown(card.findViewById<TextView>(R.id.swSearchSnippet))
            )
            assertNull("no page image is decoded for a locked row", card.thumbView.drawable)
            assertTrue(
                "the neutral placeholder is used instead",
                shown(card.findViewById<View>(R.id.swDocThumbPlaceholder))
            )
            assertFalse(
                "the locked OCR never enters the spoken row",
                card.contentDescription?.toString().orEmpty().contains(secret)
            )
        }
    }

    @Test
    fun `fast typing and clearing end on the current query with no stale row`() {
        fixture("Alpha report", ocr = filler(2))
        fixture("Beta report", ocr = filler(2))
        launch { activity ->
            waitFor("both documents load") { cards(activity).size == 2 }
            val field = activity.findViewById<EditText>(R.id.swHomeSearch)
            field.setText("alpha")
            field.setText("bet")
            field.setText("")
            waitFor("clearing shows both documents again") { cards(activity).size == 2 }
            assertEquals(
                setOf("Alpha report", "Beta report"),
                cards(activity).mapNotNull { it.doc?.name }.toSet()
            )
            assertTrue(
                "no stale snippet survives on a cleared query",
                cards(activity).none { shown(it.findViewById<TextView>(R.id.swSearchSnippet)) }
            )

            field.setText("beta")
            waitFor("the last query wins") {
                cards(activity).singleOrNull()?.doc?.name == "Beta report"
            }
            assertEquals("Beta report", cards(activity).single().doc?.name)
        }
    }

    @Test
    fun `five hundred documents stay capped and searchable`() {
        val root = repo.docsDir
        repeat(500) { index ->
            val dir = File(root, "bulk%04d".format(index)).apply { mkdirs() }
            File(dir, "meta.txt").writeText("Bulk file $index\n${1_700_000_000_000 + index}\nother\n")
            File(dir, "page_000.jpg").writeBytes(ByteArray(8) { (it + 1).toByte() })
        }
        assertEquals(500, repo.listDocuments().size)
        launch { activity ->
            waitFor("the first page of rows renders") { cards(activity).isNotEmpty() }
            assertEquals("the list is capped at one screen's worth", 60, cards(activity).size)

            search(activity, "Bulk file 499")
            waitFor("the unique name is found") {
                cards(activity).singleOrNull()?.doc?.name == "Bulk file 499"
            }
            assertEquals("Bulk file 499", cards(activity).single().doc?.name)
        }
    }
}
