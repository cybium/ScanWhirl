package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * G2 — Home must stay readable at 100 %, 130 % and 200 % system font.
 *
 * The device report showed, at 200 %: the HUD status line losing its size
 * segment, the list header cut to "5 docume…", and card metadata ellipsized.
 * All three were `maxLines="1"` text. They now wrap (a generous line budget with
 * ellipsis only as a last resort), and this suite asserts that **no important
 * line is ellipsized** at each scale, that the sort / Select / dock controls stay
 * on screen and ≥ 48dp, and that the last card can scroll clear of the raised Scan
 * disc.
 *
 * Host-test limits (read before trusting a green run): Robolectric lays text out
 * with the JVM's native text engine on a synthetic screen with synthetic insets. It
 * proves the line budget / wrapping / geometry rules, **not** how the real app looks
 * on a device or emulator at 200 % — visual acceptance is an on-device check.
 */
abstract class HomeFontScaleBase {

    protected lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        Prefs.setShowGreeting(app, true)
        RuntimeEnvironment.setFontScale(1.0f)
        // Real-world-ish library: long names, many pages, a MB-sized figure.
        val names = listOf(
            "Document 3", "Quarterly tax return and supporting receipts 2026", "Passport scan",
            "Lease agreement", "Medical report", "Utility bill"
        )
        names.forEachIndexed { i, name ->
            val uris = (0 until if (i == 0) 28 else 2).map { jpegUri() }
            runBlocking { repo.saveScan(uris, null, name) }
        }
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun jpegUri(): Uri {
        val uri = Uri.parse("content://fontscale/p_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        return uri
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

    private fun layoutAs(root: View, activity: android.app.Activity) {
        val config = activity.resources.configuration
        val density = activity.resources.displayMetrics.density
        val w = Math.round(config.screenWidthDp * density)
        val h = Math.round(config.screenHeightDp * density)
        val bar = Math.round(24f * density) // synthetic status / navigation bar
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, bar, 0, bar))
                .build()
        )
        repeat(3) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
        }
    }

    private fun top(v: View, root: View): Int {
        var t = 0
        var c: View? = v
        while (c != null && c !== root) { t += c.top; c = c.parent as? View }
        return t
    }

    private fun bottom(v: View, root: View) = top(v, root) + v.height

    private fun assertFullyShown(tv: TextView, what: String, scale: Float, problems: MutableList<String>) {
        val layout = tv.layout
        if (layout == null) { problems += "$what @ $scale: no layout"; return }
        val availW = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            if (layout.getEllipsisCount(line) > 0) problems += "$what @ $scale: line $line ellipsized \"${tv.text}\""
            // getLineMax ignores trailing wrap whitespace, which is never drawn.
            if (layout.getLineMax(line) > availW + 0.5f) problems += "$what @ $scale: line $line is ${layout.getLineMax(line)}px but box fits $availW (w=${tv.width}, lines=${layout.lineCount}) \"${tv.text}\""
        }
        val need = layout.height + tv.totalPaddingTop + tv.totalPaddingBottom
        if (need > tv.height + 0.5f) problems += "$what @ $scale: needs ${need}px but is ${tv.height}px tall"
    }

    @Test
    fun `home status header cards and controls stay readable at 100 130 and 200 percent`() {
        val problems = mutableListOf<String>()
        for (scale in floatArrayOf(1.0f, 1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.scanList)
                    assertTrue("documents load", pollUntil { list.adapter!!.itemCount == 6 })
                    val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                    layoutAs(root, activity)
                    val density = activity.resources.displayMetrics.density

                    // 1. HUD status line shows the whole readout (period · count · size).
                    val status = activity.findViewById<TextView>(R.id.statusLine)
                    assertTrue(status.isShown)
                    assertFullyShown(status, "statusLine", scale, problems)
                    assertTrue(
                        "status shows the size segment",
                        status.text.toString().contains("MB") || status.text.toString().contains(" KB")
                    )

                    // 2. List header: count + sort + Select — all visible, never ellipsized.
                    val header = activity.findViewById<View>(R.id.listHeader)
                    assertTrue("list header shown", header.isShown)
                    val count = activity.findViewById<TextView>(R.id.headerCount)
                    assertFullyShown(count, "headerCount", scale, problems)
                    assertFullyShown(activity.findViewById(R.id.sortSelector), "sortSelector", scale, problems)
                    assertFullyShown(activity.findViewById(R.id.btnSelect), "btnSelect", scale, problems)
                    for (id in intArrayOf(R.id.sortSelector, R.id.btnSelect)) {
                        val v = activity.findViewById<View>(id)
                        assertTrue("control $id has width", v.width > 0)
                        assertTrue("control $id ≥ 48dp tall @ $scale", v.height >= 48 * density - 1)
                        assertTrue("control $id ≥ 48dp wide @ $scale", v.width >= 48 * density - 1)
                        assertTrue("control $id inside the screen @ $scale", v.right + (v.parent as View).left <= root.width)
                    }

                    // 3. Card metadata: no ellipsis on any attached card.
                    var cards = 0
                    for (i in 0 until list.childCount) {
                        val card = list.getChildAt(i)
                        card.findViewById<TextView>(R.id.scanMeta)?.let {
                            cards++
                            assertFullyShown(it, "scanMeta[$i]", scale, problems)
                            assertTrue("meta keeps its size figure @ $scale", it.text.contains("KB") || it.text.contains("MB"))
                        }
                        card.findViewById<TextView>(R.id.scanName)?.let {
                            assertFullyShown(it, "scanName[$i]", scale, problems)
                        }
                    }
                    assertTrue("at least one card laid out @ $scale", cards > 0)

                    // 4. Dock: Tools / Scan / Import all on screen and tappable.
                    val dock = activity.findViewById<View>(R.id.dockRoot)
                    assertTrue(dock.isShown)
                    for (id in intArrayOf(R.id.btnTools, R.id.btnScan, R.id.btnImport)) {
                        val v = activity.findViewById<View>(id)
                        assertTrue("dock $id shown @ $scale", v.isShown && v.width > 0 && v.height > 0)
                        assertTrue("dock $id inside the screen @ $scale", bottom(v, root) <= root.height)
                    }
                    for (id in intArrayOf(R.id.scanLabel)) {
                        assertFullyShown(activity.findViewById(id), "scanLabel", scale, problems)
                    }
                    for (id in intArrayOf(R.id.btnTools, R.id.btnImport)) {
                        activity.findViewById<ViewGroup>(id).let { group ->
                            for (i in 0 until group.childCount) (group.getChildAt(i) as? TextView)
                                ?.let { assertFullyShown(it, "dock label", scale, problems) }
                        }
                    }

                    // 5. The list scrolls clear of the dock and the raised Scan disc.
                    assertTrue("list keeps drawing under its padding", !list.clipToPadding)
                    val protrusion = -(dock.layoutParams as ViewGroup.MarginLayoutParams).topMargin
                    assertTrue(
                        "list bottom padding ${list.paddingBottom}px covers the ${protrusion}px disc @ $scale",
                        list.paddingBottom >= protrusion
                    )
                    // M06: at large text the header scrolls away with the list. Scrolling
                    // down collapses it first (a programmatic scrollBy does not), so the end
                    // of the list is measured in the collapsed state a person actually sees.
                    val bar = activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.homeAppBar)
                    val collapsible = (bar.getChildAt(0).layoutParams as
                        com.google.android.material.appbar.AppBarLayout.LayoutParams).scrollFlags != 0
                    if (scale < 1.3f) assertTrue("100% header never collapses", !collapsible)
                    if (scale >= 2.0f) assertTrue("200% header scrolls away on this phone", collapsible)
                    if (collapsible) bar.setExpanded(false, false)
                    list.scrollToPosition(list.adapter!!.itemCount - 1)
                    layoutAs(root, activity)
                    list.scrollBy(0, 100000) // to the very end
                    layoutAs(root, activity)
                    val last = (0 until list.childCount).map { list.getChildAt(it) }
                        .maxByOrNull { it.bottom }!!
                    val discTop = top(activity.findViewById(R.id.scanButtonDisc), root)
                    assertTrue(
                        "last card bottom ${bottom(last, root)} must clear the Scan disc top $discTop @ $scale",
                        bottom(last, root) <= discTop
                    )
                    val barTop = top(activity.findViewById(R.id.bottomBar), root)
                    assertTrue("last card clears the dock bar @ $scale", bottom(last, root) <= barTop)
                    assertEquals(
                        "scrolled to end",
                        false, list.canScrollVertically(1)
                    )
                }
            }
        }
        assertTrue("${problems.size} problem(s):\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}

/** Narrowest realistic phone — the case that clips first. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class HomeFontScaleNarrowTest : HomeFontScaleBase()

/** A typical 411dp-wide phone (the device-report geometry). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h731dp-xxhdpi")
class HomeFontScalePhoneTest : HomeFontScaleBase()
