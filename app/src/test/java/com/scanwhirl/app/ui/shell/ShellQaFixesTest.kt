package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.util.AppLanguage
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

/**
 * v2 UI: 01_home_A / 03_tools_hub — phone-QA fixes: search over names, OCR text
 * and tags with an honest empty state; the persisted sort sheet; the nav bar
 * hiding for the keyboard; Searchable PDF as a real tool; opaque sheets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellQaFixesTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        // C1 owner 2026-11-20: never leak a chosen language into another test.
        Prefs.setLocaleTag(app, "")
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun cards(activity: ShellActivity): List<com.scanwhirl.app.ui.kit.DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as com.scanwhirl.app.ui.kit.DocCard }
    }

    private fun search(activity: ShellActivity, q: String) {
        activity.findViewById<EditText>(R.id.swHomeSearch).setText(q)
        idle()
    }

    @Test
    fun `search matches a name, OCR text and tags, and explains an empty result`() {
        val invoice = DocFixtures.saved(repo, app, pages = 1, name = "Invoice 0231")
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Passport copy")
        DocFixtures.withOcr(repo, other)
        repo.addTag(listOf(invoice), "bills")
        launch { activity ->
            search(activity, "invoice")
            assertEquals("name match", listOf("Invoice 0231"), cards(activity).map { it.doc?.name })

            search(activity, "text page_000")
            assertEquals("OCR text match", listOf("Passport copy"), cards(activity).map { it.doc?.name })

            search(activity, "bills")
            assertEquals("tag match", listOf("Invoice 0231"), cards(activity).map { it.doc?.name })

            search(activity, "zzz-no-hit")
            assertTrue("no cards", cards(activity).isEmpty())
            val none = activity.findViewById<TextView>(R.id.swNoMatches)
            assertTrue("the no-match line is shown", none.isVisible)
            assertTrue("it names the query: ${none.text}", none.text.toString().contains("zzz-no-hit"))
            assertFalse("the first-run empty state stays hidden",
                activity.findViewById<View>(R.id.swHomeEmpty).isVisible)
        }
    }

    @Test
    fun `the Newest control sorts and the choice persists`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Beta")
        DocFixtures.saved(repo, app, pages = 1, name = "Alpha")
        launch { activity ->
            assertEquals(
                "Newest ▾",
                activity.findViewById<TextView>(R.id.swSort).text.toString()
            )
            activity.findViewById<View>(R.id.swSort).performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the sort sheet opens", sheet)
            val options = sheet.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals(5, options.childCount)
            options.getChildAt(2).performClick() // Name A-Z
            idle()
            assertEquals(
                listOf("Alpha", "Beta"),
                cards(activity).map { it.doc?.name }
            )
            assertEquals(Prefs.LIB_NAME_ASC, Prefs.librarySort(app))
            assertEquals(
                "Name A-Z ▾",
                activity.findViewById<TextView>(R.id.swSort).text.toString()
            )
        }
    }

    @Test
    fun `the nav bar and disc hide while the keyboard is up`() {
        launch { activity ->
            val navBar = activity.findViewById<View>(R.id.swShellNav)
            val navHost = navBar.parent as View
            assertTrue("nav is visible at rest", navHost.isVisible)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

            val imeShown = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 600))
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, imeShown)
            assertFalse("the nav bar hides for the keyboard", navHost.isVisible)

            val imeHidden = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
            ViewCompat.dispatchApplyWindowInsets(root, imeHidden)
            assertTrue("the nav bar returns", navHost.isVisible)
        }
    }

    @Test
    fun `Searchable PDF picks a document and builds the text layer`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Search me")
        DocFixtures.withOcr(repo, id)
        assertFalse("starts without a text layer", repo.getDocument(id)!!.isSearchable)

        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val tile = ToolsHubScreen.rows(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "searchable_pdf" }
            tile.performClick()
            idle()
            val picker = ShadowDialog.getLatestDialog()
            assertNotNull("the document chooser opens", picker)
            val list = picker.findViewById<ViewGroup>(R.id.swDocPickerList)
            assertNotNull("compact document rows", list)
            (list.getChildAt(0) as View).performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the make-searchable sheet opens", sheet)
            sheet.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            var searchable = false
            val deadline = System.currentTimeMillis() + 4000
            while (!searchable && System.currentTimeMillis() < deadline) {
                idle()
                searchable = repo.getDocument(id)?.isSearchable == true
                Thread.sleep(30)
            }
            assertTrue("the PDF now carries the text layer", searchable)
        }
    }

    @Test
    fun `sheet surface is fully opaque`() {
        val sheet = SwSheet.builder(app).setTitle("Opaque").build()
        val colour = (sheet.view.background as GradientDrawable).color?.defaultColor ?: 0
        assertEquals("no dock shows through a sheet", 255, (colour ushr 24) and 0xFF)
        sheet.dismiss()
    }

    @Test
    fun `the document chooser uses compact rows and searches past eight documents`() {
        for (i in 1..10) DocFixtures.saved(repo, app, pages = 1, name = "Chooser doc $i")
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            // fixui: tiles removed (owner F6/F7/F20) - Enhance is gone; To text opens the same
            // document chooser, so the search sheet is still what this test covers.
            ToolsHubScreen.rows(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "to_text" }
                .performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull(sheet)
            val search = sheet.findViewById<EditText>(R.id.swDocPickerSearch)
            assertNotNull("a search field appears above eight documents", search)
            val list = sheet.findViewById<ViewGroup>(R.id.swDocPickerList)
            assertEquals(10, list.childCount)
            search.setText("Chooser doc 7")
            idle()
            assertEquals("filtered rows", 1, list.childCount)
            assertEquals("Chooser doc 7", (list.getChildAt(0) as com.scanwhirl.app.ui.kit.DocCard).doc?.name)
        }
    }

    @Test
    fun `the vault tab shows the live state and the shared trust pill`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            val status = activity.findViewById<TextView>(R.id.swVaultStatus)
            assertNotNull("the vault status line exists", status)
            assertEquals(activity.getString(R.string.vault_tab_not_setup), status.text.toString())

            val vaultPill = (activity.findViewById<ViewGroup>(R.id.swTabContent)
                .getChildAt(0) as ScrollView).getChildAt(0)
                .let { vault -> findTrustPill(vault as ViewGroup) }
            assertNotNull("the vault tab carries the trust pill", vaultPill)
            assertEquals(
                "same pill as the other tabs",
                activity.getString(R.string.trust_offline_no_ads),
                vaultPill!!.text.toString()
            )
        }
    }

    @Test
    fun `home has a language chip that offers English and Hindi in their own scripts`() {
        launch { activity ->
            val chip = activity.findViewById<TextView>(R.id.swLanguageChip)
            assertNotNull("Home shows the language chip", chip)
            assertEquals("A/अ", chip.text.toString())
            assertTrue("chip is a 48dp target", chip.layoutParams.height >= (48 * activity.resources.displayMetrics.density).toInt())
            assertNotNull("chip is announced", chip.contentDescription)
            chip.performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the language sheet opens", sheet)
            val options = sheet.findViewById<ViewGroup>(R.id.swSheetItems)
            assertEquals("only English and Hindi", 2, options.childCount)
            val labels = (0 until options.childCount).map { i ->
                val row = options.getChildAt(i)
                (if (row is TextView) row.text else allText(row as ViewGroup)).toString()
            }
            assertTrue(labels.toString(), labels[0].contains("English"))
            assertTrue(labels.toString(), labels[1].contains("हिन्दी"))
        }
    }

    @Test
    fun `choosing Hindi in the language sheet switches the app language`() {
        AppLanguage.apply(app, AppLanguage.ENGLISH)
        launch { activity ->
            activity.findViewById<TextView>(R.id.swLanguageChip).performClick()
            idle()
            val options = requireNotNull(ShadowDialog.getLatestDialog())
                .findViewById<ViewGroup>(R.id.swSheetItems)
            // Every row is a real checkable radio in TalkBack only when it is the chosen one.
            assertTrue("English is the checked row", options.getChildAt(0).isSelected)
            assertFalse("Hindi is not checked", options.getChildAt(1).isSelected)

            options.getChildAt(1).performClick() // हिन्दी
            idle()
            assertEquals(
                "the choice is saved through the same per-app locale path as Settings",
                AppLanguage.HINDI,
                Prefs.localeTag(app)
            )
            assertEquals(AppLanguage.HINDI, AppLanguage.currentTag(app))
        }
    }

    @Test
    fun `closing the language sheet changes nothing`() {
        AppLanguage.apply(app, AppLanguage.ENGLISH)
        launch { activity ->
            val chosenBefore = AppLanguage.currentTag(activity)
            val wordsBefore = activity.getString(R.string.shell_nav_library)
            activity.findViewById<TextView>(R.id.swLanguageChip).performClick()
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            dialog.dismiss()
            idle()

            assertEquals("closing the sheet keeps the language", chosenBefore, AppLanguage.currentTag(activity))
            assertEquals("the screen keeps its words", wordsBefore, activity.getString(R.string.shell_nav_library))
            assertEquals(AppLanguage.ENGLISH, Prefs.localeTag(app))
        }
    }

    @Test
    fun `the brand name stays on one line in the home header`() {
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swBrandMark).parent as ViewGroup
            val title = (0 until bar.childCount).map { bar.getChildAt(it) }
                .filterIsInstance<TextView>()
                .first { it.text.toString() == activity.getString(R.string.app_name) }
            assertEquals(1, title.maxLines)
        }
    }

    private fun allText(root: ViewGroup): String = (0 until root.childCount).joinToString(" ") { i ->
        when (val v = root.getChildAt(i)) {
            is TextView -> v.text.toString()
            is ViewGroup -> allText(v)
            else -> ""
        }
    }

    private fun findTrustPill(root: ViewGroup): TextView? {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is com.scanwhirl.app.ui.kit.TrustPill) return v
            if (v is ViewGroup) findTrustPill(v)?.let { return it }
        }
        return null
    }

    @Test
    fun `no tool tag sits on its icon`() {
        // Free is the only build that draws PRO tags now; force it for this audit.
        com.scanwhirl.app.util.ProGate.setTestOverride(app, false)
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            openAllToolsDoors(activity)
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            val dm = activity.resources.displayMetrics
            val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

            fun rectIn(v: View): Rect {
                val r = Rect(0, 0, v.width, v.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(v, r)
                return r
            }
            var checked = 0
            for (row in ToolsHubScreen.rows(body)) {
                val tag = row.proTag
                if (!tag.isVisible) continue
                // ATLAS-F2 (002): the Pro chip shares the tool's line, right after the name.
                // It used to drop under the label and read as a separate row.
                val iconRect = rectIn(row.iconBox)
                val tagRect = rectIn(tag)
                val labelRect = rectIn(row.labelView)
                assertFalse(
                    "${row.tool?.id} tag overlaps its icon ($tagRect vs $iconRect)",
                    Rect.intersects(iconRect, tagRect)
                )
                assertTrue(
                    "${row.tool?.id} tag sits on its name's line ($tagRect vs $labelRect)",
                    minOf(tagRect.bottom, labelRect.bottom) > maxOf(tagRect.top, labelRect.top)
                )
                assertTrue(
                    "${row.tool?.id} tag follows the name ($tagRect vs $labelRect)",
                    tagRect.left >= labelRect.right - 1
                )
                checked++
            }
            assertTrue("some rows carry a tag ($checked)", checked > 0)
        }
    }

    @Test
    fun `tagged and untagged tool rows keep icons and labels aligned`() {
        com.scanwhirl.app.util.ProGate.setTestOverride(app, false)
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            openAllToolsDoors(activity)
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            val dm = activity.resources.displayMetrics
            val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

            fun left(v: View): Int {
                val r = Rect(0, 0, v.width, v.height)
                (root as ViewGroup).offsetDescendantRectToMyCoords(v, r)
                return r.left
            }
            val rows = ToolsHubScreen.rows(body)
            val tagged = rows.count { it.proTag.isVisible }
            assertTrue("the hub mixes tagged and untagged rows", tagged in 1 until rows.size)
            val iconLefts = rows.map { left(it.iconBox) }.toSet()
            val labelLefts = rows.map { left(it.labelView) }.toSet()
            assertEquals("every row's icon starts in one column: $iconLefts", 1, iconLefts.size)
            assertEquals("every row's name starts in one column: $labelLefts", 1, labelLefts.size)
        }
    }

    @Test
    fun `the empty library has no stray button under the state`() {
        launch { activity ->
            val empty = activity.findViewById<ViewGroup>(R.id.swHomeEmpty)
            assertTrue("the empty state is shown", empty.isVisible)
            assertEquals("icon, title, body, Scan, import, trust", 6, empty.childCount)
        }
    }

    @Test
    fun `tools search has a clear button, clears on re-entry and ranks titles first`() {
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val search = activity.findViewById<EditText>(R.id.swToolsSearch)
            val clear = activity.findViewById<View>(R.id.swToolsClear)
            assertFalse("no clear button on an empty query", clear.isVisible)

            search.setText("read")
            idle()
            assertTrue("clear appears", clear.isVisible)
            val tiles = ToolsHubScreen.rows(activity.findViewById(R.id.swToolsBody))
            assertEquals("Read text ranks first", "read_text", tiles.first().tool?.id)

            clear.performClick()
            idle()
            assertEquals("clear empties the field", "", search.text.toString())
            assertFalse(clear.isVisible)

            search.setText("small")
            idle()
            activity.findViewById<View>(R.id.swNavLibrary).performClick()
            idle()
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            assertEquals("re-entering the tab starts from all tools", "", search.text.toString())
        }
    }

    @Test
    fun `search mode collapses folders and section chrome until cleared`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Searchable doc")
        launch { activity ->
            search(activity, "searchable")
            assertFalse(
                "folder chips are out of the way",
                (activity.findViewById<View>(R.id.swFolderScroll).parent as View).isVisible
            )
            assertEquals(1, cards(activity).size)

            activity.findViewById<View>(R.id.swHomeClear).performClick()
            idle()
            assertTrue(
                "folder chips return",
                (activity.findViewById<View>(R.id.swFolderScroll).parent as View).isVisible
            )
        }
    }

    @Test
    fun `an empty library collapses the chrome so the empty state fits`() {
        launch { activity ->
            assertTrue("the empty state is shown", activity.findViewById<View>(R.id.swHomeEmpty).isVisible)
            assertEquals(
                "the empty state is icon, title, body, Scan, import and trust",
                6,
                activity.findViewById<ViewGroup>(R.id.swHomeEmpty).childCount
            )
        }
    }

    @Test
    fun `the library list keeps room above the nav bar`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Bottom padding")
        launch { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            val need = 90 * activity.resources.displayMetrics.density
            assertTrue(
                "the list's last card clears the nav/disc (${list.paddingBottom}px)",
                list.paddingBottom >= need
            )
        }
    }

    /**
     * The dock is a sibling under the page. After scrolling to the end, the last
     * row of every tab must sit fully above the scan disc — not sliced on it.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
    fun `the last row of every tab scrolls fully above the dock`() {
        repeat(8) { DocFixtures.saved(repo, app, pages = 1, name = "Dock clear $it") }
        launch { activity ->
            val root = layoutShell(activity)
            assertClearsDock(activity, root, "Library", expectScroll = true) {
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                assertTrue("library has cards", list.childCount > 0)
                list.getChildAt(list.childCount - 1)
            }

            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            layoutShell(activity)
            assertClearsDock(activity, root, "Tools", expectScroll = true) {
                val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                assertTrue("tools hub has sections", body.childCount > 1)
                body.getChildAt(body.childCount - 1)
            }

            activity.findViewById<View>(R.id.swNavVault).performClick()
            idle()
            layoutShell(activity)
            assertClearsDock(activity, root, "Vault", expectScroll = false) {
                val scroll = tabScroll(activity)
                lastLaidOutChild(scroll.getChildAt(0) as ViewGroup)
            }

            activity.findViewById<View>(R.id.swNavMe).performClick()
            idle()
            layoutShell(activity)
            assertClearsDock(activity, root, "Me", expectScroll = false) {
                val list = activity.findViewById<ViewGroup>(R.id.swMeList)
                list.getChildAt(list.childCount - 1)
            }
        }
    }

    @Test
    fun `hindi kit text drops latin letter spacing so matras stay joined`() {
        val config = android.content.res.Configuration(app.resources.configuration)
        config.setLocale(java.util.Locale("hi"))
        val hi = app.createConfigurationContext(config)
        val hiHeader = com.scanwhirl.app.ui.kit.SwStyle.sectionHeader(hi, "व्यवस्थित करें", 9)
        val hiLabel = hiHeader.getChildAt(0) as TextView
        assertEquals("Hindi section header is not letter-spaced", 0f, hiLabel.letterSpacing, 0.001f)

        val enHeader = com.scanwhirl.app.ui.kit.SwStyle.sectionHeader(app, "Organize", 9)
        val enLabel = enHeader.getChildAt(0) as TextView
        assertEquals("English section header keeps its tracking", 0.08f, enLabel.letterSpacing, 0.001f)
    }

    private fun layoutShell(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        idle()
        return root
    }

    private fun tabScroll(activity: ShellActivity): ScrollView {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        return host.getChildAt(0) as ScrollView
    }

    private fun lastLaidOutChild(group: ViewGroup): View {
        var last = group.getChildAt(0)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.height > 0) last = child
        }
        return last
    }

    private fun topIn(view: View, root: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== root) {
            y += current.top
            val parent = current.parent as? View ?: break
            y -= parent.scrollY
            current = parent
        }
        return y
    }

    private fun assertClearsDock(
        activity: ShellActivity,
        root: View,
        name: String,
        expectScroll: Boolean,
        last: () -> View
    ) {
        val scroll = tabScroll(activity)
        val child = scroll.getChildAt(0)
        val range = (child.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom))
            .coerceAtLeast(0)
        scroll.scrollTo(0, range)
        if (expectScroll) {
            assertTrue("$name actually scrolls ($range px)", range > 0 && scroll.scrollY > 0)
        }

        val disc = activity.findViewById<View>(R.id.swNavScan)
        val dockTop = topIn(disc, root)
        val row = last()
        assertTrue("$name last row was laid out", row.height > 0)
        val rowBottom = topIn(row, root) + row.height
        assertTrue(
            "$name last row bottom $rowBottom must be at or above the disc ($dockTop)",
            rowBottom <= dockTop
        )
        assertFalse("$name content may draw in its padding", scroll.clipToPadding)

        val bar = activity.findViewById<View>(R.id.swShellNav)
        val host = bar.parent as View
        val margin = (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        assertEquals(
            "$name padding follows the dock, not a magic number",
            host.height + margin,
            scroll.paddingBottom
        )
        assertEquals(scroll.paddingBottom, activity.dockClearancePx())

        val fade = (0 until (scroll.parent as ViewGroup).childCount)
            .map { (scroll.parent as ViewGroup).getChildAt(it) }
            .first { it.tag == ShellActivity.DOCK_FADE }
        assertFalse("the fade must not eat taps", fade.isClickable)
        assertTrue(
            "$name last row must sit above the fade",
            rowBottom <= topIn(fade, root)
        )
    }
}
