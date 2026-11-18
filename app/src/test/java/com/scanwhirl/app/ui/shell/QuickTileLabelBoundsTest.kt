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
 * Lead's 1.2.3-test3 phone check: on Home the six Hindi quick-tool labels ran together on one
 * line ("फ़ाइल छोटी करें हस्ताक्षर करें सुरक्षित करें …") and on the Tools tab an English tile
 * label ("Batch tools") spilled out of its column.
 *
 * A tool-tile label is never wider than the tile that owns it and never draws outside it: it is
 * the column's width, centred, at most two lines, and two neighbouring labels can never overlap.
 * Checked in English and in Hindi, at 360dp, at 100 % and 130 % system font.
 */
abstract class QuickTileLabelBoundsBase {

    protected lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
        Prefs.setQuickTools(app, Prefs.DEFAULT_QUICK_TOOLS)
        // Phone language: the class qualifier decides English or Hindi.
        Prefs.setLocaleTag(app, "")
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Bounds doc")
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
        repeat(2) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
    }

    private fun offsetX(v: View, ancestor: View): Int {
        var x = 0
        var c: View? = v
        while (c != null && c !== ancestor) {
            x += c.left
            c = c.parent as? View
        }
        return x
    }

    private fun offsetY(v: View, ancestor: View): Int {
        var y = 0
        var c: View? = v
        while (c != null && c !== ancestor) {
            y += c.top
            c = c.parent as? View
        }
        return y
    }

    /** Problems for one tile's label: it must sit inside its tile and use at most two lines. */
    private fun tileProblems(tile: View, root: View): List<String> {
        val label = tile.findViewById<TextView>(R.id.swToolLabel)
            ?: return listOf("tile has no label")
        val out = mutableListOf<String>()
        val tag = "\"${label.text}\""
        val tileL = offsetX(tile, root)
        val tileR = tileL + tile.width
        val labelL = offsetX(label, root)
        val labelR = labelL + label.width
        if (labelL < tileL) out += "$tag starts at $labelL, left of its tile at $tileL"
        if (labelR > tileR) out += "$tag ends at $labelR, right of its tile at $tileR (width ${tile.width}dp-px)"
        // The drawn lines themselves must also stay inside the label's own box.
        val l = label.layout
        if (l == null) {
            out += "$tag has no text layout"
            return out
        }
        if (l.lineCount > 2) out += "$tag uses ${l.lineCount} lines (max 2)"
        val avail = label.width - label.totalPaddingLeft - label.totalPaddingRight
        for (line in 0 until l.lineCount) {
            if (l.getLineMax(line) > avail + 0.5f) {
                out += "$tag line $line is ${l.getLineMax(line)}px but its box fits $avail px (${l.lineCount} lines)"
            }
        }
        val sp = label.textSize / label.resources.displayMetrics.scaledDensity
        if (sp < 10.99f) out += "$tag is ${"%.2f".format(sp)}sp (floor is 11sp)"
        return out
    }

    private fun labelRect(label: TextView, root: View): IntArray {
        val l = offsetX(label, root)
        val t = offsetY(label, root)
        return intArrayOf(l, t, l + label.width, t + label.height)
    }

    private fun overlap(a: IntArray, b: IntArray): Boolean =
        a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    @Test
    fun `tool tile labels stay inside their column at 100 and 130 percent font`() {
        val problems = mutableListOf<String>()
        val diag = mutableListOf<String>()
        for (scale in floatArrayOf(1.0f, 1.3f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    val root = layoutRoot(activity)
                    val scaled = activity.resources.displayMetrics.scaledDensity
                    // Home quick row: five columns (the sixth tool wraps to row two).
                    for (id in intArrayOf(R.id.swQuickRow, R.id.swQuickRow2, R.id.swQuickRow3)) {
                        val row = activity.findViewById<ViewGroup>(id)
                        if (!row.isVisible) continue
                        val labels = mutableListOf<Pair<TextView, IntArray>>()
                        for (i in 0 until row.childCount) {
                            val tile = row.getChildAt(i)
                            if (tile !is ToolTile) continue
                            problems += tileProblems(tile, root).map { "@$scale quick: $it" }
                            val label = tile.findViewById<TextView>(R.id.swToolLabel)!!
                            labels += label to labelRect(label, root)
                            val l = label.layout
                            for (line in 0 until (l?.lineCount ?: 0)) {
                                if (l!!.getEllipsisCount(line) > 0) {
                                    problems += "@$scale quick: \"${label.text}\" is ellipsized (5 columns, 2 lines must fit)"
                                }
                            }
                            diag += "@$scale quick \"${label.text}\" tile=${tile.width} label=${label.width} " +
                                "lines=${l?.lineCount} sp=${"%.2f".format(label.textSize / scaled)} " +
                                "lineMax=${(0 until (l?.lineCount ?: 0)).joinToString(",") { l!!.getLineMax(it).toInt().toString() }}"
                        }
                        for (i in labels.indices) for (j in i + 1 until labels.size) {
                            if (overlap(labels[i].second, labels[j].second)) {
                                problems += "@$scale quick: \"${labels[i].first.text}\" overlaps " +
                                    "\"${labels[j].first.text}\" (${labels[i].second.toList()} vs ${labels[j].second.toList()})"
                            }
                        }
                    }
                    // Tools tab.
                    activity.findViewById<View>(R.id.swNavTools).performClick()
                    idle()
                    layoutRoot(activity)
                    val body = activity.findViewById<ViewGroup>(R.id.swToolsBody)
                    for (tile in ToolsHubScreen.tiles(body)) {
                        problems += tileProblems(tile, root).map { "@$scale hub: $it" }
                        val label = tile.findViewById<TextView>(R.id.swToolLabel)!!
                        val l = label.layout
                        diag += "@$scale hub \"${label.text}\" tile=${tile.width} label=${label.width} " +
                            "lines=${l?.lineCount} lw=${l?.width} lh=${l?.height} h=${label.height} " +
                            "maxLines=${label.maxLines} ell=${label.ellipsize} sp=${"%.2f".format(label.textSize / scaled)}"
                    }
                }
            }
        }
        assertTrue(
            "${problems.size} label problem(s):\n" + problems.joinToString("\n") +
                "\n--- geometry ---\n" + diag.joinToString("\n"),
            problems.isEmpty()
        )
    }
}

/** The phone the lead tested: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class QuickTileLabelBoundsEnTest : QuickTileLabelBoundsBase()

/** The same phone in Hindi, where the six quick labels ran together. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class QuickTileLabelBoundsHiTest : QuickTileLabelBoundsBase()
