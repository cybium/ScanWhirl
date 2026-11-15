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
            val tile = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
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
            ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                .first { it.tool?.id == "enhance" }
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
        launch { activity ->
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            val body = activity.findViewById<View>(R.id.swToolsBody)
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            val dm = activity.resources.displayMetrics
            val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

            var checked = 0
            for (tile in ToolsHubScreen.tiles(body)) {
                val tag = when {
                    tile.soonTag.isVisible -> tile.soonTag
                    tile.proTag.isVisible -> tile.proTag
                    else -> null
                } ?: continue
                val inner = tile.iconView.parent as View
                val iconRect = Rect(
                    inner.left + tile.iconView.left,
                    inner.top + tile.iconView.top,
                    inner.left + tile.iconView.right,
                    inner.top + tile.iconView.bottom
                )
                val tagRect = Rect(
                    tag.left + tag.translationX.toInt(),
                    tag.top + tag.translationY.toInt(),
                    tag.right + tag.translationX.toInt(),
                    tag.bottom + tag.translationY.toInt()
                )
                assertFalse(
                    "${tile.tool?.id} tag overlaps its icon ($tagRect vs $iconRect)",
                    Rect.intersects(iconRect, tagRect)
                )
                checked++
            }
            assertTrue("some tiles carry a tag ($checked)", checked > 0)
        }
    }
    @Test
    fun `the empty library has no stray button under the state`() {
        launch { activity ->
            val empty = activity.findViewById<ViewGroup>(R.id.swHomeEmpty)
            assertTrue("the empty state is shown", empty.isVisible)
            assertEquals("image, title, body only", 3, empty.childCount)
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
            val tiles = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
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
    fun `search mode collapses the create row and quick tools until cleared`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Searchable doc")
        launch { activity ->
            search(activity, "searchable")
            assertFalse("create row is out of the way", activity.findViewById<View>(R.id.swCreateRow).isVisible)
            assertFalse("quick tools are out of the way", activity.findViewById<View>(R.id.swQuickRow).isVisible)
            assertEquals(1, cards(activity).size)

            activity.findViewById<View>(R.id.swHomeClear).performClick()
            idle()
            assertTrue("create row returns", activity.findViewById<View>(R.id.swCreateRow).isVisible)
            assertTrue("quick tools return", activity.findViewById<View>(R.id.swQuickRow).isVisible)
        }
    }

    @Test
    fun `an empty library collapses the chrome so the empty state fits`() {
        launch { activity ->
            assertTrue("the empty state is shown", activity.findViewById<View>(R.id.swHomeEmpty).isVisible)
            assertFalse("quick tools are hidden on a first run",
                activity.findViewById<View>(R.id.swQuickRow).isVisible)
            assertEquals("no stray button under the empty state", 3,
                activity.findViewById<ViewGroup>(R.id.swHomeEmpty).childCount)
        }
    }

    @Test
    fun `the library list keeps room above the nav bar`() {        DocFixtures.saved(repo, app, pages = 1, name = "Bottom padding")
        launch { activity ->
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            val need = 90 * activity.resources.displayMetrics.density
            assertTrue(
                "the list's last card clears the nav/disc (${list.paddingBottom}px)",
                list.paddingBottom >= need
            )
        }
    }
}
