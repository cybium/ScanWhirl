package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import android.view.View
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test26 Phase 3 — the rebuilt Home screen: chip rail (T3.4), search and
 * list header (T3.5), Tools sheet (T3.6), selection mode (T3.7) and the
 * empty states (T3.8).
 *
 * Tasks add their cases here in the same commit as the UI they cover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, true)
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://home_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Saves one page under an explicit category ([null] = let the rules decide). */
    private fun seed(name: String, category: String?): String = runBlocking {
        val uri = contentUri(jpegBytes())
        repo.saveScan(listOf(uri), null, name, category = category).id
    }

    private fun chips(group: ChipGroup): List<Chip> =
        (0 until group.childCount).map { group.getChildAt(it) as Chip }

    private fun labels(group: ChipGroup): List<String> = chips(group).map { it.text.toString() }

    /** The chip whose tag is [key] (the "All" chip's tag is null). */
    private fun chipFor(group: ChipGroup, key: String?): Chip =
        chips(group).first { it.tag as String? == key }

    /** ScanListAdapter is a ListAdapter: diffs land on a background thread. */
    private fun forceLayout(rv: androidx.recyclerview.widget.RecyclerView) {
        if (rv.childCount == 0) {
            val w = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val h = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            rv.measure(w, h)
            rv.layout(0, 0, 1080, 1920)
        }
    }

    /** Same contract as UiSmokeTest: idle the looper until [condition] holds. */
    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun rowCount(activity: MainActivity): Int =
        activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
            .adapter!!.itemCount

    // ---- T3.4: category chip rail ---------------------------------------

    @Test
    fun `chip rail lists every category with counts and hides an empty unsorted`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rail = activity.findViewById<View>(R.id.chipRail)
                assertTrue("chip rail visible", rail.isVisible)

                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                // Unsorted has nothing, so it is absent: All + the five categories.
                assertEquals(
                    listOf("All 2", "Study 1", "Receipts 1", "IDs 0", "Forms 0", "Other 0"),
                    labels(group)
                )
                // Order: All first, then the five picker categories.
                assertEquals(null, chips(group).first().tag)
                assertEquals(CategoryRules.STUDY, chips(group)[1].tag)
                assertEquals(CategoryRules.RECEIPTS, chips(group)[2].tag)
            }
        }
    }

    @Test
    fun `unsorted chip appears between all and study only when it has documents`() {
        seed("Loose page", CategoryRules.UNSORTED)
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                assertEquals(
                    listOf("All 2", "Unsorted 1", "Study 1", "Receipts 0", "IDs 0", "Forms 0", "Other 0"),
                    labels(group)
                )
                assertEquals(CategoryRules.UNSORTED, chips(group)[1].tag)
            }
        }
    }

    @Test
    fun `all chip starts checked and tapping a chip filters the list to it`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)

                // Nothing selected yet -> All is the checked chip.
                assertTrue("All starts checked", chipFor(group, null).isChecked)
                assertEquals(2, list.adapter!!.itemCount)

                chipFor(group, CategoryRules.RECEIPTS).performClick()
                val filtered = pollUntil { list.adapter!!.itemCount == 1 }
                assertTrue("filtered to Receipts", filtered)
                assertTrue(chipFor(group, CategoryRules.RECEIPTS).isChecked)
                assertFalse("All is no longer checked", chipFor(group, null).isChecked)
            }
        }
    }

    @Test
    fun `tapping the active chip falls back to all`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)

                chipFor(group, CategoryRules.STUDY).performClick()
                assertTrue("filtered to Study", pollUntil { list.adapter!!.itemCount == 1 })

                // Second tap on the already-active chip clears the filter.
                chipFor(group, CategoryRules.STUDY).performClick()
                assertTrue("back to All", pollUntil { list.adapter!!.itemCount == 2 })
                assertTrue(chipFor(group, null).isChecked)
                assertFalse(chipFor(group, CategoryRules.STUDY).isChecked)
            }
        }
    }

    @Test
    fun `category filter combines with the search box`() {
        seed("Rent receipt", CategoryRules.RECEIPTS)
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                val search = activity.findViewById<android.widget.EditText>(R.id.searchInput)

                // "receipt" matches only the Receipts doc; Study stays empty.
                chipFor(group, CategoryRules.STUDY).performClick()
                search.setText("receipt")
                assertTrue(
                    "category AND query -> nothing",
                    pollUntil { list.adapter!!.itemCount == 0 }
                )

                chipFor(group, CategoryRules.RECEIPTS).performClick()
                assertTrue(
                    "category AND query -> the receipt",
                    pollUntil { list.adapter!!.itemCount == 1 }
                )
            }
        }
    }

    @Test
    fun `selection mode keeps the rail usable and still filters`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)

                // T4.3: long-press opens quick actions — `Select` + tap
                // enters selection (the rail must keep working through it).
                // Capture the card first: Select re-lays out the list.
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card.performClick()

                chipFor(group, CategoryRules.RECEIPTS).performClick()
                assertTrue(
                    "rail still filters while selecting",
                    pollUntil { list.adapter!!.itemCount == 1 }
                )
                assertTrue(chipFor(group, CategoryRules.RECEIPTS).isChecked)
            }
        }
    }

    @Test
    fun `rail is rebuilt when the library changes`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                assertEquals(listOf("All 1", "Study 1", "Receipts 0", "IDs 0", "Forms 0", "Other 0"), labels(group))
            }
        }

        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                assertEquals(
                    listOf("All 2", "Study 1", "Receipts 1", "IDs 0", "Forms 0", "Other 0"),
                    labels(group)
                )
            }
        }
    }

    // ---- T3.5: search bar + list header --------------------------------

    @Test
    fun `search hint reads Search names and text`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<android.widget.EditText>(R.id.searchInput)
                assertEquals("Search names and text", search.hint.toString())
            }
        }
    }

    @Test
    fun `clear button appears while typing and wipes the query`() {
        seed("Alpha notes", CategoryRules.OTHER)
        seed("Beta receipt", CategoryRules.OTHER)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                (activity as MainActivity).searchDebounceMs = 0
                val search = activity.findViewById<android.widget.EditText>(R.id.searchInput)
                val clear = activity.findViewById<android.view.View>(R.id.btnClearSearch)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)

                assertFalse("hidden with an empty box", clear.isVisible)

                search.setText("Alpha")
                assertTrue("query narrows the list", pollUntil { list.adapter!!.itemCount == 1 })
                assertTrue("clear shows with text", clear.isVisible)

                clear.performClick()
                assertTrue("query wiped", pollUntil { list.adapter!!.itemCount == 2 })
                assertFalse("clear hides again", clear.isVisible)
                assertEquals("", search.text.toString())
            }
        }
    }

    @Test
    fun `in text chip toggles OCR matching for pro`() {
        com.scanwhirl.app.util.ProGate.setTestOverride(app, true)
        val id = seed("Scanned bill", CategoryRules.OTHER)
        runBlocking {
            repo.updateOcrPages(id, linkedMapOf("page_001.jpg" to "hologram invoice"))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<android.widget.EditText>(R.id.searchInput)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)

                // T6: search covers names + text — no chip needed.
                search.requestFocus()
                search.setText("hologram")
                assertTrue("matches OCR text", pollUntil { list.adapter!!.itemCount == 1 })

                search.setText("")
                assertTrue("back to all", pollUntil { list.adapter!!.itemCount >= 1 })

                search.clearFocus()
            }
        }
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    @Test
    fun `list header counts documents, offers sort and select`() {
        com.scanwhirl.app.util.Prefs.setSortMode(app, com.scanwhirl.app.util.Prefs.SORT_DATE)
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val header = activity.findViewById<android.view.View>(R.id.listHeader)
                assertTrue("header shows with a non-empty list", header.isVisible)
                assertEquals(
                    "2 documents",
                    activity.findViewById<android.widget.TextView>(R.id.headerCount).text.toString()
                )
                assertEquals(
                    "${activity.getString(R.string.sort_date)} ▾",
                    activity.findViewById<android.widget.TextView>(R.id.sortSelector).text.toString()
                )
                assertEquals(
                    "Select",
                    activity.findViewById<android.widget.TextView>(R.id.btnSelect).text.toString()
                )
            }
        }
    }

    @Test
    fun `list header counts within the selected category`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                val count = activity.findViewById<android.widget.TextView>(R.id.headerCount)

                chipFor(group, CategoryRules.STUDY).performClick()
                assertEquals("1 in Study", count.text.toString())
                assertTrue(pollUntil { list.adapter!!.itemCount == 1 })

                chipFor(group, CategoryRules.STUDY).performClick()
                assertEquals("2 documents", count.text.toString())
            }
        }
    }

    @Test
    fun `Select enters selection mode and hides the header until back`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val header = activity.findViewById<android.view.View>(R.id.listHeader)
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                val selHeader = activity.findViewById<android.view.View>(R.id.selectionHeader)
                assertTrue(header.isVisible)
                assertFalse(selHeader.isVisible)

                activity.findViewById<android.view.View>(R.id.btnSelect).performClick()
                assertFalse("contextual list header yields", header.isVisible)
                assertTrue("selection header takes over", selHeader.isVisible)
                assertFalse("toolbar hides", toolbar.isVisible)
                assertEquals(
                    "0 selected",
                    activity.findViewById<android.widget.TextView>(R.id.selCount).text.toString()
                )

                activity.onBackPressed()
                assertTrue("header returns with the list", header.isVisible)
                assertFalse("selection header leaves", selHeader.isVisible)
                assertTrue("toolbar returns", toolbar.isVisible)
            }
        }
    }

    @Test
    fun `selection header shows Move disabled and gates Merge and Export`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)

                // `Select` + tap picks one doc -> 1 selected.
                // Test builds are Pro by default — force the Free UI first.
                com.scanwhirl.app.util.ProGate.setTestOverride(activity, false)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<android.view.View>(R.id.btnSelect).performClick()
                card.performClick()

                // T3.12: the actions live in the contextual bottom bar now —
                // the header row keeps only X / count / Select all (the old
                // one-row layout clipped the count, complaint #15).
                val header = activity.findViewById<android.view.View>(R.id.selectionHeader)
                val selBar = activity.findViewById<android.view.View>(R.id.selectionBar)
                assertNull("header keeps no actions", header.findViewById<android.view.View>(R.id.btnSelMerge))
                assertNotNull("actions sit in the selection bar", selBar.findViewById<android.view.View>(R.id.btnSelMerge))
                assertTrue("selection bar visible while selecting", selBar.isVisible)

                val move = activity.findViewById<android.view.View>(R.id.btnSelMove)
                val merge = activity.findViewById<android.view.View>(R.id.btnSelMerge)
                val del = activity.findViewById<android.view.View>(R.id.btnSelDelete)
                val export = activity.findViewById<android.view.View>(R.id.btnSelExport)

                // T4.3: Move is live with a selection (was disabled until T4.3).
                assertTrue("Move is visible", move.isVisible)
                assertTrue("Move enabled with 1 selected", move.isEnabled)
                // Free build: no dead Export button.
                assertFalse("Export hidden in Free", export.isVisible)
                assertEquals(
                    "1 selected",
                    activity.findViewById<android.widget.TextView>(R.id.selCount).text.toString()
                )
                assertTrue("Delete works with 1", del.isEnabled)
                assertFalse("Merge waits for 2", merge.isEnabled)

                // Second doc -> Merge unlocks. (ListAdapter diffs async —
                // wait for the row to exist before clicking it.)
                assertTrue(
                    "row 1 is laid out",
                    pollUntil { list.findViewHolderForAdapterPosition(1) != null }
                )
                list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
                assertTrue(
                    "Merge enabled at 2 selected",
                    pollUntil { merge.isEnabled }
                )
                assertEquals(
                    "2 selected",
                    activity.findViewById<android.widget.TextView>(R.id.selCount).text.toString()
                )

                // Pro: Export appears (the header re-reads Pro on every toggle).
                com.scanwhirl.app.util.ProGate.setTestOverride(activity, true)
                activity.findViewById<android.view.View>(R.id.btnSelClose).performClick()
                forceLayout(list)
                assertTrue(
                    "row 0 is back after leaving selection",
                    pollUntil { list.findViewHolderForAdapterPosition(0) != null }
                )
                // Capture the card before Select re-lays out the list.
                val card0 = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<android.view.View>(R.id.btnSelect).performClick()
                card0.performClick()
                assertTrue(
                    "Export visible for Pro",
                    pollUntil { export.isVisible }
                )
            }
        }
    }

    // ---- T3.8: empty states ----------------------------------------------

    @Test
    fun `empty library shows the viewfinder frame and first-scan copy`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val state = activity.findViewById<View>(R.id.emptyState)
                assertTrue("empty state shows", state.isVisible)
                assertTrue("viewfinder brackets show", activity.findViewById<View>(R.id.emptyFrame).isVisible)
                assertEquals(
                    "Scan your first document",
                    activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString()
                )
                // T3.13: one muted line under the title names the call to
                // action (the SCAN button in the bottom bar).
                assertTrue("hint shows for a fresh library",
                    activity.findViewById<View>(R.id.emptySubtitle).isVisible)
                assertEquals(
                    "Everything stays on this phone.",
                    activity.findViewById<android.widget.TextView>(R.id.emptySubtitle).text.toString()
                )
                assertFalse("no Show all chip for a fresh library",
                    activity.findViewById<View>(R.id.btnShowAll).isVisible)
                // The orbit icon died with T3.8.
                assertEquals(
                    "emptyOrbit id must be gone",
                    0,
                    activity.resources.getIdentifier("emptyOrbit", "id", activity.packageName)
                )
            }
        }
    }

    @Test
    fun `empty category offers Show all and brings the documents back`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val group = activity.findViewById<ChipGroup>(R.id.chipGroup)
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                val state = activity.findViewById<View>(R.id.emptyState)
                val showAll = activity.findViewById<View>(R.id.btnShowAll)

                // Receipts has nothing while Study holds the only doc.
                chipFor(group, CategoryRules.RECEIPTS).performClick()
                assertTrue("empty state for the empty category",
                    pollUntil { state.isVisible })
                assertEquals(
                    "No Receipts documents yet",
                    activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString()
                )
                assertTrue("Show all appears", showAll.isVisible)

                // One tap drops the filter: doc back, All checked, chip gone.
                showAll.performClick()
                assertTrue("doc returns", pollUntil { list.adapter!!.itemCount == 1 })
                assertFalse("empty state leaves", state.isVisible)
                assertTrue("All is checked again", chipFor(group, null).isChecked)
                assertFalse(chipFor(group, CategoryRules.RECEIPTS).isChecked)
            }
        }
    }

    @Test
    fun `search with no matches offers Show all and clears the query`() {
        seed("Rent receipt", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                (activity as MainActivity).searchDebounceMs = 0
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                val state = activity.findViewById<View>(R.id.emptyState)
                val search = activity.findViewById<android.widget.EditText>(R.id.searchInput)

                search.setText("zzz-no-such-doc")
                assertTrue("empty state for the dead query", pollUntil { state.isVisible })
                assertEquals(
                    "No documents match your search",
                    activity.findViewById<android.widget.TextView>(R.id.emptyTitle).text.toString()
                )
                val showAll = activity.findViewById<View>(R.id.btnShowAll)
                assertTrue("Show all appears", showAll.isVisible)

                // One tap wipes the query: doc back, box empty, state gone.
                showAll.performClick()
                assertTrue("doc returns", pollUntil { list.adapter!!.itemCount == 1 })
                assertEquals("query cleared", "", search.text.toString())
                assertFalse("empty state leaves", state.isVisible)
            }
        }
    }

    @Test
    fun `sort selector opens a four-item menu and applies the choice`() {
        com.scanwhirl.app.util.Prefs.setSortMode(app, com.scanwhirl.app.util.Prefs.SORT_DATE)
        seed("Zebra plan", CategoryRules.OTHER)
        seed("Apple plan", CategoryRules.OTHER)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sortSel = activity.findViewById<android.widget.TextView>(R.id.sortSelector)
                sortSel.performClick()

                val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
                assertNotNull("sort menu opens", popup)
                val titles = (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() }
                assertEquals(
                    listOf(
                        activity.getString(R.string.sort_name),
                        activity.getString(R.string.sort_date),
                        activity.getString(R.string.sort_modified),
                        activity.getString(R.string.sort_category)
                    ),
                    titles
                )

                // Choose "Name" through the menu, exactly as a tap would.
                val nameItem = (0 until popup.menu.size())
                    .map { popup.menu.getItem(it) }
                    .first { it.title == activity.getString(R.string.sort_name) }
                popup.menu.performIdentifierAction(nameItem.itemId, 0)

                assertTrue(
                    "pref switched to name",
                    pollUntil { com.scanwhirl.app.util.Prefs.sortMode(app) == com.scanwhirl.app.util.Prefs.SORT_NAME }
                )
                assertEquals(
                    "${activity.getString(R.string.sort_name)} ▾",
                    sortSel.text.toString()
                )
            }
        }
    }

    // ---- T3.6: bottom bar + Tools sheet ---------------------------------

    /** Tile ids among [root]'s descendants that are in [ids] (T3.11: sections
     *  chunk their tiles into rows of 4 or 3 columns, so count the section,
     *  not one row). */
    private fun tilesIn(root: android.view.View, ids: Set<Int>): Set<Int> = buildSet {
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c.id in ids) add(c.id)
                addAll(tilesIn(c, ids))
            }
        }
    }

    @Test
    fun `tools sheet opens and holds nine tiles in two sections`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bar = activity.findViewById<android.view.View>(R.id.dockRoot)
                assertTrue("dock shows in normal mode", bar.isVisible)

                activity.findViewById<android.view.View>(R.id.btnTools).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Tools sheet opens", sheet)

                // T6: OCR tile is Free, Edit tile is gone.
                val freeIds = setOf(
                    R.id.tile_ocr, R.id.tile_merge, R.id.tile_compress, R.id.tile_resize,
                    R.id.tile_quick_sort
                )
                val proIds = setOf(
                    R.id.tile_lock, R.id.tile_long_image, R.id.tile_vault
                )
                for (id in freeIds + proIds) {
                    assertNotNull(
                        "tile ${activity.resources.getResourceEntryName(id)} present",
                        sheet!!.findViewById<android.view.View>(id)
                    )
                }

                // T3.11: rows are chunked per section (4 columns, or 3 when the
                // longest word would not fit) — assert the section holds its
                // tiles, whatever the column count.
                val free = sheet.findViewById<android.view.ViewGroup>(R.id.sheetFree)
                val pro = sheet.findViewById<android.view.ViewGroup>(R.id.sheetPro)
                assertEquals("Free section holds 5 tiles", freeIds, tilesIn(free, freeIds))
                assertEquals("Pro section holds 4 tiles", proIds, tilesIn(pro, proIds))

                sheet.dismiss()
                assertFalse("sheet dismissed", sheet.isShowing)
            }
        }
    }
}
