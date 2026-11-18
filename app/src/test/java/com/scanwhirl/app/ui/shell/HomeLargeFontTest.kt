package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolTile
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
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
 * T23-FIX-HOME-200 — the 200 % font defects the device sweep found on build a55b4223
 * (DEVICE_SWEEP 2026-11-19: H1 EN, H2 HI, M1, M2, M5, M8).
 *
 * UI_PLAN §0.8 binds this: at 200 % font, unclipped text beats density; no letter-spacing on
 * Devanagari; Hindi body/meta start at 14 sp. A label must never break inside a word, must never
 * be silently dropped, and no Home chrome (top trust pill, All chip) may run off the screen.
 *
 * Host-test limits: Robolectric lays text out with the platform text engine on a synthetic
 * 360dp screen. It proves the wrapping/geometry rules, not the final pixels — a phone re-check is
 * still required for visual acceptance.
 */
abstract class HomeLargeFontBase {

    protected lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setQuickTools(app, Prefs.DEFAULT_QUICK_TOOLS)
        Prefs.setLocaleTag(app, "")
        RuntimeEnvironment.setFontScale(2.0f)
        DocFixtures.saved(ScanRepository(app), app, pages = 2, name = "ID card")
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Passport photo")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
        Prefs.setLocaleTag(app, "")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Lays the shell out exactly as a 360dp wide phone would. */
    private fun layoutRoot(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    /** A line break is legal only at whitespace or beside punctuation, never inside a word. */
    private fun isWordChar(c: Char): Boolean =
        !c.isWhitespace() && c !in "·…-/()[],.|•"

    private fun labelProblems(tv: TextView, what: String): List<String> {
        val out = mutableListOf<String>()
        val layout = tv.layout ?: return listOf("$what has no text layout")
        val text = tv.text
        for (line in 0 until layout.lineCount) {
            if (layout.getEllipsisCount(line) > 0) {
                out += "$what line $line is ellipsized (\"$text\")"
            }
            val end = layout.getLineEnd(line)
            if (end <= 0 || end >= text.length) continue
            var last = end
            while (last > 0 && text[last - 1].isWhitespace()) last--
            if (last == 0 || last >= text.length) continue
            // The first character after the line's content must be a boundary (whitespace or
            // punctuation). If it is a word character, the line ended inside a word.
            if (isWordChar(text[last - 1]) && isWordChar(text[last])) {
                val from = (last - 6).coerceAtLeast(0)
                val to = (end + 6).coerceAtMost(text.length)
                out += "$what line $line breaks inside a word: \"${text.substring(from, to)}\""
            }
        }
        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            if (layout.getLineMax(line) > avail + 0.5f) {
                out += "$what line $line is ${layout.getLineMax(line)}px, box fits $avail px (\"$text\")"
            }
        }
        if (layout.height + tv.totalPaddingTop + tv.totalPaddingBottom > tv.height + 1) {
            out += "$what is clipped: text needs ${layout.height}px, view has ${tv.height}px (\"$text\")"
        }
        return out
    }

    @Test
    fun `home tile labels wrap between words and are never dropped at 200 percent`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val root = layoutRoot(activity)
                val problems = mutableListOf<String>()
                val expected = listOf(
                    R.string.quick_merge,
                    R.string.quick_compress,
                    R.string.quick_sign,
                    R.string.quick_protect,
                    R.string.quick_to_word,
                    R.string.quick_read_text
                ).map { activity.getString(it) }
                val found = mutableListOf<String>()
                var tiles = 0
                for (rowId in intArrayOf(R.id.swQuickRow, R.id.swQuickRow2, R.id.swQuickRow3)) {
                    val row = activity.findViewById<ViewGroup>(rowId) ?: continue
                    if (!row.isVisible) continue
                    for (i in 0 until row.childCount) {
                        val tile = row.getChildAt(i) as? ToolTile ?: continue
                        tiles++
                        val label = tile.labelView
                        if (label.text.isBlank()) {
                            problems += "quick tile ${tile.tool?.id} has no label"
                            continue
                        }
                        found += label.text.toString()
                        problems += labelProblems(label, "quick \"${label.text}\"")
                        if (label.width > tile.width + 1) {
                            problems += "quick \"${label.text}\" is ${label.width}px wide in a ${tile.width}px tile"
                        }
                        if (AppLanguage.showingHindi(activity)) {
                            if (label.letterSpacing != 0f) {
                                problems += "Hindi quick \"${label.text}\" has letter-spacing ${label.letterSpacing}"
                            }
                        }
                    }
                }
                assertTrue("the quick row rendered tiles", tiles > 0)
                for (text in expected) {
                    if (text !in found) problems += "quick label \"$text\" is missing (found $found)"
                }
                // H1/H2 fix: large text gets fewer, wider columns instead of 69dp slivers.
                val firstRow = activity.findViewById<ViewGroup>(R.id.swQuickRow)
                val perRow = (0 until firstRow.childCount).count { firstRow.getChildAt(it) is ToolTile }
                if (perRow > 3) {
                    problems += "at 200 % Home uses $perRow quick columns (needs <= 3 so a label fits a word)"
                }

                // M1 at 200 %: the row meta keeps the date readable too.
                val docList = activity.findViewById<ViewGroup>(R.id.swDocList)
                if (docList.childCount > 0) {
                    val meta = (docList.getChildAt(0) as com.scanwhirl.app.ui.kit.DocCard).metaView
                    val metaLayout = meta.layout
                    if (metaLayout == null) {
                        problems += "the row meta is not laid out"
                    } else {
                        for (line in 0 until metaLayout.lineCount) {
                            if (metaLayout.getEllipsisCount(line) > 0) {
                                problems += "row meta line $line is ellipsized (\"${meta.text}\")"
                            }
                        }
                        if (!meta.text.contains(activity.getString(R.string.shell_today))) {
                            problems += "the row meta lost its date (\"${meta.text}\")"
                        }
                    }
                }

                // The create row (ID card / Photos / Import PDF) is Home chrome too: H1 showed
                // "Import PDF" painting past its tile at 200 %.
                for (id in intArrayOf(R.id.swCreateIdCard, R.id.swCreatePhotos, R.id.swCreateImport)) {
                    val card = activity.findViewById<ViewGroup>(id)
                    val label = card.getChildAt(card.childCount - 1) as TextView
                    problems += labelProblems(label, "create \"${label.text}\"")
                    if (label.left < 0 || label.right > card.width + 1) {
                        problems += "create \"${label.text}\" box [${label.left},${label.right}] leaves its card (0..${card.width})"
                    }
                }

                // The top trust pill must stay inside the screen, fully readable (H1/H2).
                val pillText = activity.getString(R.string.trust_offline_no_ads)
                val pill = findText(activity.findViewById(R.id.swTabContent), pillText)
                    ?: findText(root, pillText)
                if (pill == null) {
                    problems += "the top trust pill \"$pillText\" is missing"
                } else {
                    val right = rightIn(pill, root)
                    if (right > root.width - 1) {
                        problems += "the top trust pill ends at ${right}px, past the screen (${root.width}px)"
                    }
                    problems += labelProblems(pill, "trust pill")
                }

                assertTrue(
                    "${problems.size} large-font problem(s):\n" + problems.joinToString("\n"),
                    problems.isEmpty()
                )
            }
        }
    }

    @Test
    fun `all chip and clear search are 48dp touch targets at 200 percent`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val density = activity.resources.displayMetrics.density
                val all = findText(activity.findViewById(R.id.swFolderRow), activity.getString(R.string.folder_all))
                assertTrue("the All folder chip exists on Home", all != null)
                assertTrue(
                    "the All chip is ${all!!.width}x${all.height}px, needs 48dp = ${(48 * density).toInt()}px",
                    all.width >= 48 * density - 1 && all.height >= 48 * density - 1
                )

                activity.findViewById<View>(R.id.swNavTools).performClick()
                idle()
                activity.findViewById<android.widget.EditText>(R.id.swToolsSearch).setText("read")
                idle()
                val clear = activity.findViewById<View>(R.id.swToolsClear)
                assertTrue("the clear-search button appears", clear.isVisible)
                assertTrue(
                    "clear search is ${clear.width}x${clear.height}px, needs 48dp = ${(48 * density).toInt()}px",
                    clear.width >= 48 * density - 1 && clear.height >= 48 * density - 1
                )
            }
        }
    }

    @Test
    fun `all folder chip is a 48dp touch target at normal font too`() {
        RuntimeEnvironment.setFontScale(1.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val density = activity.resources.displayMetrics.density
                val all = findText(activity.findViewById(R.id.swFolderRow), activity.getString(R.string.folder_all))
                assertTrue("the All folder chip exists on Home", all != null)
                assertTrue(
                    "the All chip is ${all!!.width}x${all.height}px at 100%, needs 48dp = ${(48 * density).toInt()}px (M8: it was 44dp)",
                    all.width >= 48 * density - 1 && all.height >= 48 * density - 1
                )
            }
        }
    }

    @Test
    fun `library row meta keeps the whole date at normal font`() {
        RuntimeEnvironment.setFontScale(1.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                assertTrue("the library has a row", list.childCount > 0)
                val card = list.getChildAt(0) as com.scanwhirl.app.ui.kit.DocCard
                val meta = card.metaView
                val layout = meta.layout
                assertTrue("the meta is laid out", layout != null)
                assertTrue("the meta may use two lines (M1)", meta.maxLines >= 2)
                for (line in 0 until layout!!.lineCount) {
                    assertTrue(
                        "the meta is cut on line $line (\"${meta.text}\")",
                        layout.getEllipsisCount(line) == 0
                    )
                }
                assertTrue(
                    "the date must still be readable (\"${meta.text}\")",
                    meta.text.contains(activity.getString(R.string.shell_today))
                )
                if (AppLanguage.showingHindi(activity)) {
                    val sp = meta.textSize / activity.resources.displayMetrics.scaledDensity
                    assertTrue("Hindi meta is ${"%.2f".format(sp)}sp, UI_PLAN 0.8 needs >= 14sp", sp >= 13.99f)
                }
            }
        }
    }

    @Test
    fun `home list bottom padding clears the floating dock at 200 percent`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layoutRoot(activity)
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                val dock = activity.dockClearancePx()
                assertTrue("the dock has a measured clearance", dock > 0)
                assertTrue(
                    "the list bottom padding ${list.paddingBottom}px must cover the dock (${dock}px)",
                    list.paddingBottom >= dock
                )
            }
        }
    }

    private fun findText(root: View?, text: String): TextView? {
        if (root == null) return null
        if (root is TextView && root.text?.toString() == text && root.isVisible) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun rightIn(view: View, root: View): Int {
        var x = view.width
        var current: View? = view
        while (current != null && current !== root) {
            x += current.left
            current = current.parent as? View
        }
        return x
    }
}

/** The phone the sweep used: 360dp wide, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class HomeLargeFontEnTest : HomeLargeFontBase()

/** The same phone in Hindi (H2), where labels were clipped/ellipsized and matras split. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class HomeLargeFontHiTest : HomeLargeFontBase()
