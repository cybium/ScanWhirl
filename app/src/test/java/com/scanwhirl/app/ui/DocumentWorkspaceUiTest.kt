package com.scanwhirl.app.ui

import android.app.Application
import android.content.pm.ProviderInfo
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.FakeMediaStore
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog

/**
 * UI-B6 (GPT judgement 2026-11-19b, item 1 — "Make Document the workspace"): the saved
 * document screen is a workspace with SEVEN labelled actions — Edit / crop (the only filled
 * cyan button), Read text, Share, Compress, Sign (+ a gold "Pro" word in Free), Save PDF and
 * More — laid out 4 + 3 on a 360 dp phone, 3 + 3 + 1 on a 320 dp phone, and as growing
 * two-column cards inside the scroll flow ABOVE the pages once type reaches 160 %.
 *
 * Every action is bound to the SHOWN document (not "the latest one"): the routing is proven
 * with two near-identical documents and the existing guarded handlers — CompressFlow.startWith,
 * ProOffer.require + StampEditorActivity TOOL_SIGN, the one save-to-Documents copy, and the
 * named Actions sheet.
 *
 * Red first: written against the pre-B6 screen (four-item dock: Edit · Read text · Share ·
 * Actions, a toolbar-only icon Save, Compress and Sign two taps away in the sheet).
 */
abstract class DocumentWorkspaceBase {

    protected lateinit var app: Application
    protected lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    /** The seven action buttons, in the panel's reading order. */
    private val actionIds = listOf(
        R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnCompress,
        R.id.btnSign, R.id.btnSavePdf, R.id.btnMore
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // A test build is Pro by default; every test starts from a known, neutral state.
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(
                app,
                ProviderInfo().apply { authority = "media"; exported = true }
            )
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        // androidx FileProvider caches its roots per authority in a static map; each Robolectric
        // test gets a fresh cacheDir, so a stale entry makes getUriForFile fail.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
        DocumentViewerActivity.actionTraceForTests = null
        DocumentActionsSheet.interceptForTests = null
    }

    @After
    fun tearDown() {
        DocumentViewerActivity.actionTraceForTests = null
        DocumentActionsSheet.interceptForTests = null
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ------------------------------------------------------------------ rig

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    protected val density: Float get() = app.resources.displayMetrics.density

    protected fun px(dp: Float): Int = Math.round(dp * density)

    protected val screenWidthDp: Int get() = app.resources.configuration.screenWidthDp

    /**
     * Launches the document screen at [fontScale], waits for the async first load and lays it
     * out exactly as the qualifier's phone would, with status/navigation insets dispatched.
     */
    protected fun open(
        docId: String,
        fontScale: Float = 1.0f,
        block: (DocumentViewerActivity, View) -> Unit
    ) {
        RuntimeEnvironment.setFontScale(fontScale)
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, docId)
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the document loads before the screen is measured",
                    pollUntil {
                        activity.findViewById<TextView>(R.id.pageCount)
                            ?.text?.isNotEmpty() == true
                    }
                )
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0) ?: content
                layout(activity, root)
                block(activity, root)
            }
        }
    }

    protected fun layout(activity: android.app.Activity, root: View) {
        val w = px(screenWidthDp.toFloat())
        val h = px(activity.resources.configuration.screenHeightDp.toFloat())
        val bar = px(48f)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        val passW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val passH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(passW, passH)
            root.layout(0, 0, w, h)
        }
        idle()
    }

    private fun button(activity: DocumentViewerActivity, id: Int): MaterialButton =
        requireNotNull(activity.findViewById<MaterialButton>(id)) { "action $id is missing" }

    private fun buttonOf(activity: DocumentViewerActivity, id: Int) = button(activity, id)

    /** Bottom edge of [v] in screen coordinates. */
    private fun absBottom(v: View): Int {
        var b = v.bottom
        var p = v.parent as? View
        while (p != null) {
            b += p.top
            p = p.parent as? View
        }
        return b
    }

    private fun absRight(v: View): Int {
        var r = v.right
        var p = v.parent as? View
        while (p != null) {
            r += p.left
            p = p.parent as? View
        }
        return r
    }

    private fun inScrollChain(v: View): Boolean {
        var p = v.parent as? View
        while (p != null) {
            val n = p.javaClass.simpleName
            if (n.contains("Scroll")) return true
            p = p.parent as? View
        }
        return false
    }

    private fun contrast(fg: Int, bg: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        fun luminance(c: Int) = 0.2126 * channel(Color.red(c)) +
            0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** The panel's spoken/visible description of edit: what the user sees. */
    private fun expectedLabel(activity: DocumentViewerActivity, id: Int): String = when (id) {
        R.id.btnEdit -> activity.getString(R.string.doc_action_edit_crop)
        R.id.btnText -> activity.getString(R.string.dock_read_text)
        R.id.btnShare -> activity.getString(R.string.share_pdf)
        R.id.btnCompress -> activity.getString(R.string.tool_compress)
        R.id.btnSign -> activity.getString(R.string.tool_sign)
        R.id.btnSavePdf -> activity.getString(R.string.doc_action_save_pdf)
        else -> activity.getString(R.string.more)
    }

    /** What TalkBack reads on each action (this rig is Pro, so Sign carries no Pro suffix). */
    private fun expectedDescription(activity: DocumentViewerActivity, id: Int): String = when (id) {
        R.id.btnEdit -> activity.getString(R.string.doc_action_edit_crop)
        R.id.btnText -> activity.getString(R.string.dock_read_text)
        R.id.btnShare -> activity.getString(R.string.share_pdf)
        R.id.btnCompress -> activity.getString(R.string.tool_compress)
        R.id.btnSign -> activity.getString(R.string.tool_sign)
        R.id.btnSavePdf -> activity.getString(R.string.save_pdf_device)
        else -> activity.getString(R.string.doc_action_more_desc)
    }

    // ------------------------------------------------------------------ 1. the panel

    @Test
    fun `seven labelled actions at every font scale, none clipped`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
        for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
            open(id, scale) { activity, _ ->
                val panel = activity.findViewById<View>(R.id.bottomDock)
                assertEquals(
                    "the action panel is on screen at ${scale}x",
                    View.VISIBLE,
                    panel.visibility
                )
                val screenH = px(activity.resources.configuration.screenHeightDp.toFloat())
                val navBar = px(48f)
                val scrolls = inScrollChain(panel)
                for (actionId in actionIds) {
                    val b = buttonOf(activity, actionId)
                    val label = expectedLabel(activity, actionId)
                    assertTrue(
                        "action ${activity.resources.getResourceEntryName(actionId)} keeps its " +
                            "label at ${scale}x (was '${b.text}')",
                        b.text.toString() == label || b.text.toString().startsWith("$label ")
                    )
                    assertTrue(
                        "action $label is visible at ${scale}x",
                        b.isShown && b.visibility == View.VISIBLE
                    )
                    assertTrue(
                        "action $label is enabled at ${scale}x",
                        b.isEnabled
                    )
                    assertTrue(
                        "action $label is at least 48 dp tall at ${scale}x (was ${b.height}px)",
                        b.height >= px(48f)
                    )
                    assertTrue(
                        "action $label is at least 48 dp wide at ${scale}x (was ${b.width}px)",
                        b.width >= px(48f)
                    )
                    val textLayout = requireNotNull(b.layout) {
                        "action $label is laid out at ${scale}x"
                    }
                    assertTrue("action $label has a label line", textLayout.lineCount >= 1)
                    for (line in 0 until textLayout.lineCount) {
                        assertEquals(
                            "action $label is never ellipsized at ${scale}x (line $line)",
                            0,
                            textLayout.getEllipsisCount(line)
                        )
                        assertTrue(
                            "action $label line $line fits between its paddings at ${scale}x " +
                                "(line ${textLayout.getLineMax(line)}px, view ${b.width}px, " +
                                "pads ${b.totalPaddingLeft}/${b.totalPaddingRight}, " +
                                "lines ${textLayout.lineCount})",
                            // 1 dp of tolerance: Android's line breaker rounds the last glyph
                            // advance, so a line can measure a sub-dp over the box it was
                            // broken for. The label still sits inside the button's padding.
                            textLayout.getLineMax(line) <=
                                (b.width - b.totalPaddingLeft - b.totalPaddingRight) + px(1f)
                        )
                    }
                    val needed = textLayout.height +
                        maxOf(b.totalPaddingTop, b.compoundPaddingTop) +
                        maxOf(b.totalPaddingBottom, b.compoundPaddingBottom)
                    assertTrue(
                        "action $label is not clipped vertically at ${scale}x " +
                            "(needs ${needed}px, is ${b.height}px)",
                        needed <= b.height + 0.5f
                    )
                    assertTrue(
                        "action $label stays inside the screen width at ${scale}x",
                        absRight(b) <= px(screenWidthDp.toFloat()) + 1
                    )
                    if (!scrolls) {
                        assertTrue(
                            "action $label sits above the navigation bar at ${scale}x " +
                                "(bottom ${absBottom(b)}px, screen ${screenH}px)",
                            absBottom(b) <= screenH - navBar
                        )
                    }
                    // Accessibility: every action carries a spoken description of its own.
                    val desc = b.contentDescription?.toString().orEmpty()
                    val expectedDesc = expectedDescription(activity, actionId)
                    assertTrue(
                        "action $label has the spoken description '$expectedDesc' at ${scale}x " +
                            "(was '$desc')",
                        desc == expectedDesc || desc.startsWith(expectedDesc)
                    )
                }
                if (scrolls) {
                    // One scroll owner: the panel and the pages scroll together above the bar.
                    val scroll = activity.findViewById<View>(R.id.actionFlowScroll)
                    assertTrue(
                        "at ${scale}x the panel scrolls above the navigation bar",
                        scroll.isShown && absBottom(scroll) <= screenH - navBar
                    )
                }
            }
        }
    }

    @Test
    fun `edit is the only filled cyan action and the others are raised`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Rent receipt")
        open(id) { activity, _ ->
            val cyan = activity.getColor(R.color.cyan_primary)
            val raised = activity.getColor(R.color.glass_surface_raised)
            for (actionId in actionIds) {
                val b = buttonOf(activity, actionId)
                val tint = b.backgroundTintList?.defaultColor
                if (actionId == R.id.btnEdit) {
                    assertEquals("Edit is the one filled cyan button", cyan, tint)
                } else {
                    assertEquals(
                        "action ${activity.resources.getResourceEntryName(actionId)} is raised, " +
                            "not filled cyan",
                        raised,
                        tint
                    )
                }
            }
            // Contrast of the label ink against its own fill (WCAG 4.5:1 for small text).
            for (actionId in actionIds) {
                val b = buttonOf(activity, actionId)
                val fill = b.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
                val ratio = contrast(b.currentTextColor, fill)
                assertTrue(
                    "action ${activity.resources.getResourceEntryName(actionId)} label contrast " +
                        "is ${"%.2f".format(ratio)}:1, needs 4.5:1",
                    ratio >= 4.5
                )
            }
            assertEquals(
                "the panel names itself",
                activity.getString(R.string.doc_actions_header),
                activity.findViewById<TextView>(R.id.actionPanelTitle).text.toString()
            )
            assertEquals(
                "the panel says where Save PDF puts the copy",
                activity.getString(R.string.doc_action_save_hint),
                activity.findViewById<TextView>(R.id.actionPanelHint).text.toString()
            )
        }
    }

    @Test
    fun `at 100 percent the actions are a full row then a shorter one`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Rent receipt")
        open(id) { activity, _ ->
            val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
            val rows = (0 until grid.childCount).map { grid.getChildAt(it) as ViewGroup }
            val expectedColumns = if (screenWidthDp >= 360) 4 else 3
            val expectedRows = (0 until actionIds.size)
                .chunked(expectedColumns)
                .map { it.size }
            assertEquals(
                "the panel reads $expectedColumns + the rest at ${screenWidthDp}dp",
                expectedRows,
                rows.map { it.childCount }
            )
            assertEquals(
                "seven actions fill the rows",
                actionIds.size,
                rows.sumOf { it.childCount }
            )
            assertEquals(
                "the reading order is unchanged by the wrap",
                actionIds,
                rows.flatMap { row -> (0 until row.childCount).map { row.getChildAt(it).id } }
            )
        }
    }

    @Test
    fun `at 200 percent the panel is two growing columns above the pages in one scroll`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
        open(id, 2.0f) { activity, _ ->
            val scroll = activity.findViewById<View>(R.id.actionFlowScroll)
            val column = activity.findViewById<ViewGroup>(R.id.actionFlowColumn)
            assertTrue("the whole content scrolls at 200 %", scroll.isShown)
            assertTrue("the panel moved into the scroll flow", scroll.findViewById<View>(R.id.bottomDock) != null)
            assertTrue("the pages scroll with it, not twice", scroll.findViewById<View>(R.id.pageList) != null)
            assertEquals("the panel is first: above the pages", R.id.bottomDock, column.getChildAt(0).id)
            assertEquals("the pages follow the panel", R.id.pageList, column.getChildAt(1).id)
            val pages = activity.findViewById<View>(R.id.pageList)
            assertFalse(
                "the grid never scrolls inside the one scroll owner",
                pages.isNestedScrollingEnabled
            )

            val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
            val rows = (0 until grid.childCount).map { grid.getChildAt(it) as ViewGroup }
            assertEquals("two columns at 200 %", listOf(2, 2, 2, 1), rows.map { it.childCount })
            assertEquals(
                "the last action keeps the full width rather than stretching one column",
                ViewGroup.LayoutParams.MATCH_PARENT,
                rows.last().getChildAt(0).layoutParams.width
            )
            for (row in rows) {
                for (i in 0 until row.childCount) {
                    val b = row.getChildAt(i) as MaterialButton
                    assertTrue(
                        "a 200 % cell grows past 48 dp, was ${b.height}px",
                        b.height >= px(48f)
                    )
                    val l = requireNotNull(b.layout) { "the 200 % label is laid out" }
                    for (line in 0 until l.lineCount) {
                        assertEquals(
                            "'${b.text}' is never ellipsized at 200 %",
                            0,
                            l.getEllipsisCount(line)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `at 200 percent the document name wraps and is never ellipsized`() {
        val long = "Rent receipt for October with the utility breakdown and the landlord's note"
        val id = DocFixtures.saved(repo, app, pages = 2, name = long)
        open(id, 2.0f) { activity, _ ->
            val name = activity.findViewById<TextView>(R.id.docName)
            assertEquals("the name row shows the document's name", long, name.text.toString())
            val l = requireNotNull(name.layout) { "the name is laid out" }
            assertTrue("a long name wraps at 200 %", l.lineCount >= 2)
            for (line in 0 until l.lineCount) {
                assertEquals(
                    "the name is never ellipsized at 200 % (line $line)",
                    0,
                    l.getEllipsisCount(line)
                )
                assertTrue(
                    "name line $line fits",
                    l.getLineMax(line) <=
                        (name.width - name.totalPaddingLeft - name.totalPaddingRight) + 0.5f
                )
            }
        }
    }

    // ------------------------------------------------------------------ 2. routing

    @Test
    fun `every action targets the shown document, not the newest one`() {
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt October")
        val decoy = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt Octobe")
        assertNotNull("the decoy is a different, newer document", repo.getDocument(decoy))

        val calls = mutableListOf<Pair<String, String>>()
        DocumentViewerActivity.actionTraceForTests = { action, doc -> calls += action to doc.id }

        open(shown) { activity, _ ->
            for (actionId in actionIds) {
                activity.findViewById<View>(actionId).performClick()
                idle()
                // Dismiss whatever the action opened, so the next click starts clean.
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
            }
            assertEquals(
                "all seven actions ran: $calls",
                actionIds.size,
                calls.size
            )
            assertTrue(
                "no action touched the near-identical decoy: $calls",
                calls.all { it.second == shown }
            )
        }
    }

    @Test
    fun `edit opens the page editor on the shown document`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Passport scan")
        DocFixtures.saved(repo, app, pages = 1, name = "Passport scan (2)")
        open(shown) { activity, _ ->
            activity.findViewById<View>(R.id.btnEdit).performClick()
            idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Edit opens the page editor"
            }
            assertEquals(PageViewerActivity::class.java.name, started.component?.className)
            assertEquals(shown, started.getStringExtra("doc_id"))
        }
    }

    @Test
    fun `free sign shows the Pro screen and never the editor`() {
        ProGate.setTestOverride(app, false)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        open(id) { activity, _ ->
            val sign = buttonOf(activity, R.id.btnSign)
            val label = sign.text as android.text.Spanned
            val goldAt = label.toString().indexOf(activity.getString(R.string.doc_action_pro))
            assertTrue("Free shows the word Pro on Sign, label was '${sign.text}'", goldAt >= 0)
            val gold = label.getSpans(goldAt, goldAt + 3, android.text.style.ForegroundColorSpan::class.java)
            assertTrue("the word Pro is gold", gold.any {
                it.foregroundColor == activity.getColor(R.color.pro_gold)
            })

            sign.performClick()
            idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Free Sign opens the Pro screen"
            }
            assertEquals(ShellActivity::class.java.name, started.component?.className)
            assertTrue(
                "the shell is asked for the Pro screen",
                started.getBooleanExtra(ShellActivity.EXTRA_OPEN_PRO, false)
            )
            assertNull(
                "the stamp editor is never reached for free",
                shadowOf(activity).peekNextStartedActivity()
                    ?.takeIf { it.component?.className == StampEditorActivity::class.java.name }
            )
        }
    }

    @Test
    fun `pro sign opens the editor with this document and the sign tool`() {
        ProGate.setTestOverride(app, true)
        val shown = DocFixtures.saved(repo, app, pages = 1, name = "Sign me")
        DocFixtures.saved(repo, app, pages = 1, name = "Sign me (2)")
        open(shown) { activity, _ ->
            val sign = buttonOf(activity, R.id.btnSign)
            val labelText = sign.text as? android.text.Spanned
            val hasGoldPro = labelText?.getSpans(
                0, labelText.length, android.text.style.ForegroundColorSpan::class.java
            )?.any { it.foregroundColor == activity.getColor(R.color.pro_gold) } == true
            assertFalse("Pro does not show the Pro word on Sign", hasGoldPro)
            activity.findViewById<View>(R.id.btnSign).performClick()
            idle()
            val started = requireNotNull(shadowOf(activity).nextStartedActivity) {
                "Pro Sign opens the stamp editor"
            }
            assertEquals(StampEditorActivity::class.java.name, started.component?.className)
            assertEquals(shown, started.getStringExtra(StampEditorActivity.EXTRA_DOC_ID))
            assertEquals(
                StampEditorActivity.TOOL_SIGN,
                started.getStringExtra(StampEditorActivity.EXTRA_TOOL)
            )
        }
    }

    @Test
    fun `compress opens the guarded compress sheet for this document`() {
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt October")
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt Octobe")
        val calls = mutableListOf<Pair<String, String>>()
        DocumentViewerActivity.actionTraceForTests = { action, doc -> calls += action to doc.id }
        open(shown) { activity, _ ->
            activity.findViewById<View>(R.id.btnCompress).performClick()
            idle()
            assertTrue(
                "the Compress sheet opens for the shown document",
                pollUntil {
                    ShadowDialog.getLatestDialog()?.window?.decorView
                        ?.findViewById<View>(R.id.editCompressTarget) != null
                }
            )
            assertEquals(
                "Compress ran through the document-bound handler",
                listOf("compress" to shown),
                calls
            )
        }
    }

    @Test
    fun `save pdf has exactly one route and writes one copy of the shown document`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Receipt A")
        DocFixtures.saved(repo, app, pages = 1, name = "Receipt B")
        open(shown) { activity, _ ->
            val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            assertEquals(
                "the toolbar keeps no icon-only duplicate save",
                0,
                toolbar.menu.size()
            )
            assertEquals(
                "Save PDF is the panel's labelled button",
                activity.getString(R.string.doc_action_save_pdf),
                buttonOf(activity, R.id.btnSavePdf).text.toString()
            )
            activity.findViewById<View>(R.id.btnSavePdf).performClick()
            assertTrue(
                "one copy is written",
                pollUntil { media.scanwhirlPdfs().size == 1 && media.totalWrites() == 1 }
            )
            val doc = requireNotNull(repo.getDocument(shown))
            assertTrue(
                "the copy is recorded against the shown document",
                pollUntil { repo.exportRecordsOf(doc.id).isNotEmpty() }
            )
        }
        assertEquals(
            "the screen never saved a second copy",
            1,
            media.scanwhirlPdfs().size
        )
    }

    @Test
    fun `more opens the named actions sheet for the shown document`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Audit actions A")
        DocFixtures.saved(repo, app, pages = 1, name = "Audit actions B")
        open(shown) { activity, _ ->
            activity.findViewById<View>(R.id.btnMore).performClick()
            idle()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) {
                "More opens the Actions sheet"
            }
            val name = requireNotNull(
                sheet.window?.decorView?.findViewWithTag<TextView>("docActions:name")
            ) { "the sheet names its document" }
            assertEquals("Audit actions A", name.text.toString())
        }
    }

    // ------------------------------------------------------------------ 3. protection

    @Test
    fun `a locked document disables compress, sign and read text with the sheet's reason`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 2)
        assertLockedState(id)
    }

    @Test
    fun `a vault document disables compress, sign and read text with the sheet's reason`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Vault doc")
        repo.setVaulted(id, true)
        assertLockedState(id)
    }

    private fun assertLockedState(id: String) {
        val doc: ScanDocument = requireNotNull(repo.getDocument(id))
        val calls = mutableListOf<Pair<String, String>>()
        DocumentViewerActivity.actionTraceForTests = { action, d -> calls += action to d.id }
        open(id) { activity, _ ->
            val expected = if (doc.isLocked) {
                activity.getString(R.string.locked_source_refused, doc.name)
            } else {
                activity.getString(R.string.doc_actions_vault_reason)
            }
            for (actionId in listOf(R.id.btnCompress, R.id.btnSign, R.id.btnText)) {
                val b = buttonOf(activity, actionId)
                assertFalse(
                    "${activity.resources.getResourceEntryName(actionId)} is disabled",
                    b.isEnabled
                )
                assertTrue(
                    "the disabled action carries the sheet's reason",
                    b.contentDescription?.toString()?.contains(expected) == true
                )
                b.performClick()
            }
            idle()
            assertTrue("no handler ran: $calls", calls.isEmpty())
            assertNull("no editor opened", shadowOf(activity).peekNextStartedActivity())
            assertNull("no sheet opened", ShadowDialog.getLatestDialog())
            assertTrue(
                "the reason is visible on the panel",
                activity.findViewById<TextView>(R.id.actionPanelReason).isShown
            )
            assertEquals(
                "the reason is the same words the sheet uses",
                expected,
                activity.findViewById<TextView>(R.id.actionPanelReason).text.toString()
            )
        }
    }

    /**
     * A document that stops being protected (the user takes it out of the Vault or unlocks it)
     * must come back whole: the reason disappears, the buttons work again, and no stale
     * "take it out of the Vault first" words stay in what TalkBack reads.
     */
    @Test
    fun `a document that becomes usable again loses the reason and the stale words`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Vault doc")
        repo.setVaulted(id, true)
        open(id) { activity, _ ->
            assertFalse(
                "Compress starts disabled in the Vault",
                buttonOf(activity, R.id.btnCompress).isEnabled
            )
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    repo.setVaulted(id, false)
                }
            }
            activity.refreshList()
            assertTrue(
                "the panel comes back when the document is usable again",
                pollUntil {
                    buttonOf(activity, R.id.btnCompress).isEnabled &&
                        !activity.findViewById<TextView>(R.id.actionPanelReason).isShown
                }
            )
            assertEquals(
                "no Vault reason is left in Compress's spoken label",
                activity.getString(R.string.tool_compress),
                buttonOf(activity, R.id.btnCompress).contentDescription?.toString()
            )
            assertEquals(
                "Sign speaks only its own label again",
                activity.getString(R.string.tool_sign),
                buttonOf(activity, R.id.btnSign).contentDescription?.toString()
            )
        }
    }
}

/** 360 dp phone, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class DocumentWorkspaceEn360Test : DocumentWorkspaceBase()

/** 360 dp phone, Hindi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class DocumentWorkspaceHi360Test : DocumentWorkspaceBase()

/** 320 dp phone, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class DocumentWorkspaceEn320Test : DocumentWorkspaceBase()

/** 320 dp phone, Hindi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w320dp-h640dp-xhdpi")
class DocumentWorkspaceHi320Test : DocumentWorkspaceBase()
