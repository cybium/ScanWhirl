package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test26 T3.12 — selection mode per §4.2 Rev 3 (complaint #15): the count
 * gets its own header row (`X · N selected · Select all`) so it can never
 * be clipped, and the four actions move to a contextual bottom action bar
 * that replaces the SCAN bar while selecting.
 *
 * Red-proofed on T3.11's code: `selectionBar` never shows and `Select all`
 * does nothing until the T3.12 logic lands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class SelectionBarTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        // Test builds are Pro by default — Free is the baseline UI here.
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, true)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        // Never leak a 1.3 font scale into other test classes.
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ---- fixtures (same contract as HomeScreenTest) -----------------------

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://selbar_${System.nanoTime()}.jpg")
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

    /** ScanListAdapter is a ListAdapter: diffs land on a background thread. */
    private fun forceLayout(rv: androidx.recyclerview.widget.RecyclerView) {
        if (rv.childCount == 0) {
            val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            rv.measure(w, h)
            rv.layout(0, 0, 1080, 1920)
        }
    }

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

    // ---- T3.12: header row -----------------------------------------------

    @Test
    fun `one selected shows the full count and the action bar takes over`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                // T4.3: long-press opens quick actions now — `Select` enters
                // selection mode, then a tap ticks the card. Capture the card
                // first: entering selection re-lays out the list.
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card.performClick()

                // The count is the whole point of the row: the exact, full
                // string (complaint #15 clipped it to `1 sel…`).
                assertEquals(
                    app.resources.getQuantityString(R.plurals.selected_count, 1, 1),
                    activity.findViewById<TextView>(R.id.selCount).text.toString()
                )

                // Actions live in the bar; the header keeps only X/count/all.
                val header = activity.findViewById<View>(R.id.selectionHeader)
                val bar = activity.findViewById<View>(R.id.selectionBar)
                assertNotNull(
                    "Merge sits in the action bar",
                    bar.findViewById<View>(R.id.btnSelMerge)
                )
                assertEquals(
                    "header holds no action buttons",
                    null,
                    header.findViewById<View>(R.id.btnSelMerge)
                )
                assertTrue("action bar replaces the SCAN bar", bar.isVisible)
                assertFalse(
                    "SCAN bar leaves while selecting",
                    activity.findViewById<View>(R.id.bottomBar).isVisible
                )

                // Enable rules (§4.2).
                assertFalse(
                    "Merge waits for 2",
                    bar.findViewById<View>(R.id.btnSelMerge).isEnabled
                )
                assertTrue(
                    "Move is live with 1 selected (T4.3)",
                    bar.findViewById<View>(R.id.btnSelMove).isEnabled
                )
                assertTrue(
                    "Delete works with 1",
                    bar.findViewById<View>(R.id.btnSelDelete).isEnabled
                )
            }
        }
    }

    @Test
    fun `two selected enable Merge`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)

                // T4.3: `Select` + tap picks doc 1 (long-press = quick actions).
                // The card view is captured before Select: entering selection
                // re-lays out the list and the holder lookup comes back null.
                val card0 = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card0.performClick()
                assertTrue(
                    "row 1 is laid out",
                    pollUntil { list.findViewHolderForAdapterPosition(1) != null }
                )
                list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()

                val merge = activity.findViewById<View>(R.id.btnSelMerge)
                assertTrue("Merge enabled at 2 selected", pollUntil { merge.isEnabled })
                assertEquals(
                    app.resources.getQuantityString(R.plurals.selected_count, 2, 2),
                    activity.findViewById<TextView>(R.id.selCount).text.toString()
                )
            }
        }
    }

    @Test
    fun `Select all picks every visible card`() {
        seed("Lecture notes", CategoryRules.STUDY)
        seed("Lab worksheet", CategoryRules.STUDY)
        seed("Electricity bill", CategoryRules.RECEIPTS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)

                // Filter to Study first: only the two Study cards are
                // visible, so "every visible card" must mean 2 — not 3.
                val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroup)
                val study = (0 until group.childCount)
                    .map { group.getChildAt(it) as com.google.android.material.chip.Chip }
                    .first { it.tag as String? == CategoryRules.STUDY }
                study.performClick()
                assertTrue("study list lands", pollUntil { list.adapter!!.itemCount == 2 })

                activity.findViewById<View>(R.id.btnSelect).performClick()
                activity.findViewById<View>(R.id.btnSelSelectAll).performClick()

                assertEquals(
                    app.resources.getQuantityString(R.plurals.selected_count, 2, 2),
                    activity.findViewById<TextView>(R.id.selCount).text.toString()
                )
            }
        }
    }

    @Test
    fun `Back leaves selection and the SCAN bar returns`() {
        seed("Lecture notes", CategoryRules.STUDY)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                activity.findViewById<View>(R.id.btnSelect).performClick()
                card.performClick()

                val bar = activity.findViewById<View>(R.id.bottomBar)
                val selBar = activity.findViewById<View>(R.id.selectionBar)
                val header = activity.findViewById<View>(R.id.selectionHeader)
                val toolbar =
                    activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertTrue("action bar up", selBar.isVisible)
                assertFalse("SCAN bar gone", bar.isVisible)
                assertTrue("header up", header.isVisible)
                assertFalse("toolbar hides", toolbar.isVisible)

                activity.onBackPressed()

                assertFalse("action bar leaves", selBar.isVisible)
                assertTrue("SCAN bar returns", bar.isVisible)
                assertFalse("header leaves", header.isVisible)
                assertTrue("toolbar returns", toolbar.isVisible)
            }
        }
    }

    @Test
    fun `the count fits the header at large font scale with twelve selected`() {
        RuntimeEnvironment.setFontScale(1.3f)
        repeat(12) { i -> seed("Document $i", null) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnSelect).performClick()
                activity.findViewById<View>(R.id.btnSelSelectAll).performClick()

                val count = activity.findViewById<TextView>(R.id.selCount)
                val text = count.text.toString()
                assertEquals(
                    app.resources.getQuantityString(R.plurals.selected_count, 12, 12),
                    text
                )

                // Lay the window out as the device would, then check the
                // string against the room the row actually gives it.
                val decor = activity.window.decorView
                val dm = activity.resources.displayMetrics
                repeat(2) {
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
                }
                val inner = count.width - count.paddingStart - count.paddingEnd
                assertTrue("count has its own space (width=${count.width}px)", count.width > 0)
                val needed = count.paint.measureText(text)
                assertTrue(
                    "12 selected needs ${needed.toInt()}px but only ${inner}px fit",
                    needed <= inner
                )
            }
        }
    }
}
