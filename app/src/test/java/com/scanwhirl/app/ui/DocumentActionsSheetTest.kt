package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.isVisible
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * B2 (GPT-6.2 critique #3, "Two-tap contract"): the one named Actions sheet, bound to ONE
 * document id.
 *
 * The sheet's header repeats the shown document's own thumbnail and name; Compress, Sign and
 * Read text carry the document in (never a second picker); Share goes through the existing
 * protection guard; a Free user tapping Sign gets the Pro gate, never the editor; a locked or
 * Vault document cannot reach a handler that would use its pages outside the protection
 * boundary; and the same API works from another host (the shell), so the Library row's
 * overflow can adopt it without another copy.
 *
 * Red first: written against the pre-B2 More sheet, which had no header, no Sign row, no Read
 * text row, no Share row, no per-document binding and no reason for an ineligible row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class DocumentActionsSheetTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        DocumentActionsSheet.interceptForTests = null
    }

    @After
    fun tearDown() {
        DocumentActionsSheet.interceptForTests = null
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    /** Opens the document viewer, waits for its first load, then the Actions sheet. */
    private fun withActions(
        docId: String,
        block: (DocumentViewerActivity, android.app.Dialog) -> Unit
    ) {
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, docId))
            .use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "the document screen loads before Actions",
                        pollUntil {
                            activity.findViewById<TextView>(R.id.pageCount)
                                ?.text?.isNotEmpty() == true
                        }
                    )
                    activity.findViewById<View>(R.id.btnMore).performClick()
                    idle()
                    val sheet = requireNotNull(ShadowDialog.getLatestDialog()) {
                        "the Actions sheet must open"
                    }
                    block(activity, sheet)
                }
            }
    }

    private fun tag(sheet: android.app.Dialog, name: String): View =
        requireNotNull(sheet.window?.decorView?.findViewWithTag<View>("docActions:$name")) {
            "the sheet has no '$name' row"
        }

    /** The header's thumbnail image (the tag sits on its 48 dp holder, so the loader's own tag survives). */
    private fun thumbOf(sheet: android.app.Dialog): ImageView {
        val holder = requireNotNull(
            sheet.window?.decorView?.findViewWithTag<View>("docActions:thumb")
        ) { "the header must carry a thumbnail" }
        if (holder is ImageView) return holder
        val group = holder as android.view.ViewGroup
        for (i in 0 until group.childCount) {
            (group.getChildAt(i) as? ImageView)?.let { return it }
        }
        throw AssertionError("the header thumbnail holder has no image")
    }

    private fun rowIds(): Map<String, Int> = mapOf(
        "compress" to R.id.rowMoreCompress,
        "rename" to R.id.rowMoreRename,
        "move" to R.id.rowMoreMove,
        "duplicate" to R.id.rowMoreDuplicate,
        "lock" to R.id.rowMoreLock,
        "vault" to R.id.rowMoreVault,
        "trash" to R.id.rowMoreDelete
    )

    // --- 1. the header names the document the sheet is bound to -----------------

    @Test
    fun `the header shows the shown document's name and its real page thumbnail`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Rent agreement")
        withActions(id) { activity, sheet ->
            val name = requireNotNull(
                sheet.window?.decorView?.findViewWithTag<TextView>("docActions:name")
            ) { "the header must show the document name" }
            assertEquals("the header is this document's name", "Rent agreement", name.text.toString())

            val thumb = thumbOf(sheet)
            assertNotNull("the header thumbnail is bound", thumb.colorFilter)
            val doc = requireNotNull(repo.getDocument(id))
            // PageThumbLoader tags the image with its request key: proves the header asked for
            // THIS document's first page at the header size, and that it decoded.
            val key = thumb.tag as? String
            assertNotNull("the header asked the loader for a page thumbnail", key)
            assertTrue(
                "the header decoded this document's own first page, key was '$key'",
                key!!.contains(doc.pageFiles.first().path) && key.contains("|fit")
            )
            assertTrue(
                "the header shows the decoded page, not the placeholder",
                pollUntil {
                    val d = thumb.drawable
                    d is android.graphics.drawable.BitmapDrawable ||
                        d is android.graphics.drawable.TransitionDrawable
                }
            )
        }
    }

    // --- 2. two near-identical documents: every row carries the shown one ------

    @Test
    fun `with two near-identical documents every row targets the shown document`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Passport scan")
        // The decoy is newer, so it is first in a "newest first" list: an unbound sheet that
        // reach for "the current document" would take this one instead.
        val decoy = DocFixtures.saved(repo, app, pages = 1, name = "Passport scan (2)")
        assertNotNull(repo.getDocument(decoy))

        val calls = mutableListOf<Pair<DocumentActionsSheet.Action, String>>()
        DocumentActionsSheet.interceptForTests = { action, doc ->
            calls += action to doc.id
            true
        }

        val expected = listOf(
            "compress" to DocumentActionsSheet.Action.COMPRESS,
            "sign" to DocumentActionsSheet.Action.SIGN,
            "read_text" to DocumentActionsSheet.Action.READ_TEXT,
            "share" to DocumentActionsSheet.Action.SHARE,
            "rename" to DocumentActionsSheet.Action.RENAME,
            "move" to DocumentActionsSheet.Action.MOVE,
            "duplicate" to DocumentActionsSheet.Action.DUPLICATE,
            "lock" to DocumentActionsSheet.Action.PROTECT,
            "trash" to DocumentActionsSheet.Action.TRASH
        )

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, shown))
            .use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "the shown document loads",
                        pollUntil {
                            activity.findViewById<TextView>(R.id.pageCount)
                                ?.text?.isNotEmpty() == true
                        }
                    )
                    for ((key, action) in expected) {
                        activity.findViewById<View>(R.id.btnMore).performClick()
                        idle()
                        val sheet = requireNotNull(ShadowDialog.getLatestDialog())
                        val row = rowIds()[key]?.let { sheet.findViewById<View>(it) } ?: tag(sheet, key)
                        assertNotNull("row '$key' is missing from the Actions sheet", row)
                        row!!.performClick()
                        idle()
                        val last = calls.lastOrNull()
                        assertNotNull("row '$key' never reached its handler", last)
                        assertEquals("row '$key' carries the wrong action", action, last!!.first)
                        assertEquals(
                            "row '$key' must act on the shown document, not the decoy",
                            shown,
                            last.second
                        )
                    }
                }
            }
        assertEquals("every row ran exactly once", expected.size, calls.size)
    }

    // --- 3. Share / Read text are the existing document-bound routes -----------

    @Test
    fun `Read text opens the OCR screen for the shown document`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Read me")
        val opened = mutableListOf<String>()
        DocumentActionsSheet.interceptForTests = { action, doc ->
            if (action == DocumentActionsSheet.Action.READ_TEXT) {
                opened += doc.id
                true
            } else {
                true
            }
        }
        withActions(id) { _, sheet ->
            tag(sheet, "read_text").performClick()
            idle()
        }
        assertEquals("Read text is bound to the shown document", listOf(id), opened)
    }

    @Test
    fun `Share hands the shown document to the system chooser`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Share me")
        withActions(id) { activity, sheet ->
            sheet.window!!.decorView.findViewWithTag<View>("docActions:share").performClick()
            idle()
            val chooser = shadowOf(activity).nextStartedActivity
            assertNotNull("Share must open the chooser, not die", chooser)
            assertEquals(
                "Share is the system send sheet",
                android.content.Intent.ACTION_CHOOSER,
                chooser!!.action
            )
            val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
            assertEquals(android.content.Intent.ACTION_SEND, send?.action)
        }
    }

    // --- 4. Sign: Pro marker, Free gate, never the editor ----------------------

    @Test
    fun `Free tapping Sign opens the Pro gate and never the editor`() {
        ProGate.setTestOverride(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        withActions(id) { activity, sheet ->
            val sign = tag(sheet, "sign")
            assertTrue("Sign is enabled for a Free user (the gate is the next step)", sign.isEnabled)
            assertNotNull(
                "Sign carries its Pro marker",
                sheet.window!!.decorView.findViewWithTag<View>("docActions:sign_pro")
            )
            sign.performClick()
            idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Sign must not be a dead tap for a Free user"
            }
            assertEquals(
                "Free must land on the Pro screen",
                ShellActivity::class.java.name,
                started.component?.className
            )
            assertTrue(
                "Free must ask the shell for the Pro screen",
                started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
            )
            assertFalse(
                "Free must never reach the Sign editor",
                started.component?.className == StampEditorActivity::class.java.name
            )
        }
    }

    @Test
    fun `Pro tapping Sign opens the stamp editor armed with Sign for the document`() {
        ProGate.setTestOverride(app, true)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        withActions(id) { activity, sheet ->
            tag(sheet, "sign").performClick()
            idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Pro Sign must open the editor"
            }
            assertEquals(
                StampEditorActivity::class.java.name,
                started.component?.className
            )
            assertEquals(
                "the editor opens on the shown document, not a picker",
                id,
                started.getStringExtra(StampEditorActivity.EXTRA_DOC_ID)
            )
            assertEquals(
                StampEditorActivity.TOOL_SIGN,
                started.getStringExtra(StampEditorActivity.EXTRA_TOOL)
            )
        }
    }

    // --- 5. Compress keeps the real flow and its source -----------------------

    @Test
    fun `Compress opens the real level sheet for the shown document`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Compress me")
        withActions(id) { _, sheet ->
            sheet.findViewById<View>(R.id.rowMoreCompress).performClick()
            assertTrue(
                "the compress level sheet opens with its estimates",
                pollUntil {
                    ShadowDialog.getLatestDialog()?.window?.decorView
                        ?.findViewById<View>(R.id.editCompressTarget) != null
                }
            )
            val level = requireNotNull(ShadowDialog.getLatestDialog())
            val title = level.window!!.decorView.findViewById<TextView>(R.id.swSheetTitle)
            assertEquals(
                "the level sheet is Compress's own set step",
                app.getString(R.string.compress_level_title),
                title.text.toString()
            )
            level.dismiss()
        }
    }

    // --- 6. protected documents cannot reach ineligible handlers ---------------

    @Test
    fun `a locked document disables the page-based rows and reaches no handler`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 2)
        val calls = mutableListOf<DocumentActionsSheet.Action>()
        DocumentActionsSheet.interceptForTests = { action, _ ->
            calls += action
            true
        }
        withActions(id) { activity, sheet ->
            for (key in listOf("compress", "sign", "read_text")) {
                val row = if (key == "compress") {
                    sheet.findViewById<View>(R.id.rowMoreCompress)
                } else {
                    tag(sheet, key)
                }
                assertNotNull("row '$key' exists (so the reason is visible)", row)
                assertFalse("row '$key' must be disabled for a locked document", row!!.isEnabled)
                assertTrue(
                    "row '$key' is visibly greyed, was alpha ${row.alpha}",
                    row.alpha <= 0.5f
                )
                val words = row.contentDescription?.toString().orEmpty()
                assertTrue(
                    "row '$key' says why it cannot run, was '$words'",
                    words.contains(app.getString(R.string.locked_source_refused, "x").substringBefore("%"))
                        || words.contains("Unlock")
                )
                row.performClick()
                idle()
            }
            assertTrue(
                "Share stays available: the protection guard handles it",
                tag(sheet, "share").isEnabled
            )
            // The header never decodes a locked page (privacy rule of the Home thumbnails):
            // no loader request is left on the image, and the lock glyph says why.
            val thumb = thumbOf(sheet)
            val doc = requireNotNull(repo.getDocument(id))
            assertTrue("locked documents have no page image", doc.isLocked)
            assertNull("a locked document sends no thumbnail request", thumb.tag)
            assertTrue(
                "a locked document never shows a decoded page",
                thumb.drawable !is android.graphics.drawable.BitmapDrawable &&
                    thumb.drawable !is android.graphics.drawable.TransitionDrawable
            )
            val holder = sheet.window!!.decorView.findViewWithTag<View>("docActions:thumb")
                as android.view.ViewGroup
            val glyph = (0 until holder.childCount)
                .map { holder.getChildAt(it) }
                .filterIsInstance<ImageView>()
                .last()
            assertEquals("the lock glyph is shown", View.VISIBLE, glyph.visibility)
            assertNull("no handler ran for a locked document", calls.firstOrNull())
        }
        assertTrue("no row reached a handler at all", calls.isEmpty())
    }

    @Test
    fun `a Vault document says to take it out first and reaches no page handler`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Private doc")
        repo.setVaulted(id, true)
        val calls = mutableListOf<DocumentActionsSheet.Action>()
        DocumentActionsSheet.interceptForTests = { action, _ ->
            calls += action
            true
        }
        withActions(id) { _, sheet ->
            for (key in listOf("compress", "sign", "read_text")) {
                val row = if (key == "compress") {
                    sheet.findViewById<View>(R.id.rowMoreCompress)
                } else {
                    tag(sheet, key)
                }
                assertFalse("row '$key' is disabled in the Vault", row!!.isEnabled)
                assertTrue(
                    "row '$key' gives the Vault reason",
                    row.contentDescription?.toString().orEmpty()
                        .contains(app.getString(R.string.doc_actions_vault_reason))
                )
                row.performClick()
                idle()
            }
            assertTrue("the Vault row is offered for a Vault item", sheet.findViewById<View>(R.id.rowMoreVault).isVisible)
            assertTrue(calls.isEmpty())
        }
    }

    // --- 7. the same sheet from another host (the Library lane's API) ----------

    @Test
    fun `the simple show API works from the shell, bound to the given id`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Library doc")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        val activity = controller.get()
        idle()
        DocumentActionsSheet.show(activity, id)
        idle()
        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) {
            "DocumentActionsSheet.show(activity, docId) must open the sheet"
        }
        val name = requireNotNull(
            sheet.window?.decorView?.findViewWithTag<TextView>("docActions:name")
        )
        assertEquals("Library doc", name.text.toString())
        assertNotNull("Compress is available from the Library sheet", sheet.findViewById<View>(R.id.rowMoreCompress))
        sheet.dismiss()
        controller.pause().stop().destroy()
    }
}
