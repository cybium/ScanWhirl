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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

                // Long-press a card -> selection mode (T3.7 will restyle the
                // header; the rail must keep working through it).
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()

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
}
