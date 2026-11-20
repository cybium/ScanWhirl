package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ATLAS-F2 (001_library_2.0): the category chip rail sliced "Receipts 1" at the screen edge
 * with nothing to say it scrolled, and the row meta broke inside a size ("1 page · 4" /
 * "KB · Today").
 *
 * A rail may be wider than a 320dp phone; it must advertise that with an edge fade, keep
 * every chip on whole words, and leave end padding so the last chip can rest fully visible.
 * The meta keeps a number glued to its unit with a non-breaking space.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class LibraryRailMetaFitTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        RuntimeEnvironment.setFontScale(1.0f)
        DocFixtures.saved(repo, app, pages = 1, name = "Receipt August")
        DocFixtures.saved(repo, app, pages = 2, name = "Physics notes")
        DocFixtures.saved(repo, app, pages = 3, name = "Offer letter")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        Prefs.setLocaleTag(app, "")
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun layout(activity: ShellActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun textViews(root: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (root is TextView) out += root
        if (root is ViewGroup) for (i in 0 until root.childCount) textViews(root.getChildAt(i), out)
        return out
    }

    private fun launchReady(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            poll {
                var ready = false
                scenario.onActivity { activity ->
                    ready = activity.findViewById<ViewGroup>(R.id.swDocList)?.childCount == 3
                }
                ready
            }
            scenario.onActivity { activity ->
                layout(activity)
                block(activity)
            }
        }
    }

    private fun checkRail(activity: ShellActivity, scrollId: Int, rowId: Int, label: String) {
        val scrollView = activity.findViewById<View>(scrollId)
        assertNotNull("$label is shown", scrollView)
        val scroll = scrollView!!
        assertTrue(
            "$label advertises that it scrolls (edge fade) instead of looking sliced",
            scroll.isHorizontalFadingEdgeEnabled
        )
        val rowView = activity.findViewById<ViewGroup>(rowId)
        assertNotNull("$label has chips", rowView)
        val row = rowView!!
        assertTrue("$label has chips", row.childCount > 0)
        assertTrue(
            "$label leaves end padding so the last chip can rest fully visible",
            row.paddingRight >= (12 * activity.resources.displayMetrics.density).toInt()
        )
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as TextView
            val text = chip.text.toString()
            val layout = chip.layout
            assertNotNull("$label chip \"$text\" was laid out", layout)
            val lay = layout!!
            for (line in 0 until lay.lineCount) {
                assertEquals("$label chip \"$text\" is ellipsized", 0, lay.getEllipsisCount(line))
            }
            val needed = lay.height + chip.totalPaddingTop + chip.totalPaddingBottom
            assertTrue(
                "$label chip \"$text\" needs ${needed}px but its view is ${chip.height}px tall",
                needed <= chip.height + 0.5f
            )
            val avail = chip.width - chip.totalPaddingLeft - chip.totalPaddingRight
            val longest = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                .maxOfOrNull { chip.paint.measureText(it) } ?: 0f
            assertTrue(
                "$label chip \"$text\" would break a word (longest ${longest.toInt()}px of ${avail}px)",
                longest <= avail + 0.5f
            )
        }
    }

    @Test
    fun `the library chip rails show that they scroll at 100 and 200 percent`() {
        for (scale in floatArrayOf(1.0f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            launchReady { activity ->
                checkRail(activity, R.id.swFolderScroll, R.id.swFolderRow, "folder rail @ ${scale}x")
                checkRail(activity, R.id.swChipScroll, R.id.swChipRow, "category rail @ ${scale}x")
            }
        }
    }

    @Test
    fun `row meta keeps a number with its unit at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        launchReady { activity ->
            val metas = textViews(activity.findViewById(R.id.swDocList))
                .filter { it.id == R.id.swDocMeta }
            assertTrue("the library renders row meta", metas.isNotEmpty())
            for (meta in metas) {
                val text = meta.text.toString()
                assertTrue(
                    "\"$text\" keeps the size on one line (no break inside a number and unit)",
                    Regex("[0-9]\\u00A0(KB|MB|B)").containsMatchIn(text)
                )
                assertTrue(
                    "\"$text\" must not use a plain space before the unit",
                    !Regex("[0-9] (KB|MB|B)").containsMatchIn(text)
                )
                assertTrue(
                    "\"$text\" keeps the page count together",
                    !Regex("[0-9] (page|pages|पेज)").containsMatchIn(text)
                )
                val layout = meta.layout
                assertNotNull("\"$text\" was laid out", layout)
                val lay = layout!!
                val avail = meta.width - meta.totalPaddingLeft - meta.totalPaddingRight
                for (line in 0 until lay.lineCount) {
                    assertTrue(
                        "\"$text\" line $line is ${lay.getLineWidth(line).toInt()}px wide " +
                            "but only ${avail}px fit",
                        lay.getLineWidth(line) <= avail + 0.5f
                    )
                    assertEquals("\"$text\" is ellipsized", 0, lay.getEllipsisCount(line))
                }
                val needed = lay.height + meta.totalPaddingTop + meta.totalPaddingBottom
                assertTrue(
                    "\"$text\" needs ${needed}px but the view is ${meta.height}px tall",
                    needed <= meta.height + 0.5f
                )
            }
        }
    }

    @Test
    fun `the rails and the meta hold in Hindi at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        RuntimeEnvironment.setQualifiers("hi-rIN-w320dp-h640dp-xhdpi")
        launchReady { activity ->
            checkRail(activity, R.id.swFolderScroll, R.id.swFolderRow, "folder rail (hi)")
            checkRail(activity, R.id.swChipScroll, R.id.swChipRow, "category rail (hi)")
            val metas = textViews(activity.findViewById(R.id.swDocList))
                .filter { it.id == R.id.swDocMeta }
            assertTrue("the Hindi library renders row meta", metas.isNotEmpty())
            for (meta in metas) {
                val layout = meta.layout
                assertNotNull("Hindi meta was laid out", layout)
                val lay = layout!!
                val avail = meta.width - meta.totalPaddingLeft - meta.totalPaddingRight
                for (line in 0 until lay.lineCount) {
                    // Ink width (getLineMax excludes trailing whitespace, which does not
                    // paint). UI-W4 hides the direct Share at 200%, so the text column is
                    // wider and a line can end on a space; counting that space would fail
                    // a line that visibly fits.
                    assertTrue(
                        "Hindi meta line $line is ${lay.getLineMax(line).toInt()}px wide " +
                            "but only ${avail}px fit (\"${meta.text}\")",
                        lay.getLineMax(line) <= avail + 0.5f
                    )
                    assertEquals("Hindi meta is ellipsized", 0, lay.getEllipsisCount(line))
                }
            }
        }
    }
}
