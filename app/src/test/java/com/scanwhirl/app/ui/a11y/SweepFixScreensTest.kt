package com.scanwhirl.app.ui.a11y
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.ProBilling
import com.scanwhirl.app.util.ProGate
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * T22-FIX-ME-SCREENS — the defects the 2026-11-19 real-phone sweep (T17-DEVICE-SWEEP) found at
 * 360dp width and font scale 1.0:
 *
 *  H3  Pro (Free) opens with the price panel and "Unlock Pro" behind the floating dock;
 *  M3  a clickable strip at the top of a scrolled Settings list with no name;
 *  M4  Trash's overflow button is 40x48dp;
 *  M7  Settings / Me / the document More sheet announce clickable rows with no name;
 *  M6  a PDF/A result shows a blank white preview card.
 *
 * Every assertion here measures the real view tree at 360dp, so it fails on the shipped build and
 * passes only when the screen really changed. Nothing here relaxes the existing audits
 * (AccessibilityAuditTest, DeadButtonAuditTest): this is the extra "the node a screen reader
 * focuses owns a name" and "the CTA is above the dock" rule they do not check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SweepFixScreensTest {


    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    /** Answers nothing: the Pro screen must not wait on (or invent) a Play price here. */
    private class SilentGateway : ProBilling.Gateway {
        override fun setListener(listener: ((ProBilling.Event) -> Unit)?) = Unit
        override fun connect(onReady: (Boolean) -> Unit) = Unit
        override fun price(onPrice: (String?) -> Unit) = Unit
        override fun launch(activity: Activity?) = Unit
        override fun queryPurchases(onResult: (List<ProBilling.Owned>?) -> Unit) = Unit
        override fun acknowledge(purchase: ProBilling.Owned, onDone: (Boolean) -> Unit) = Unit
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProBilling.resetForTests()
        ProBilling.gatewayFactory = { SilentGateway() }
        ProGate.setPro(app, false)
        // Free: the paywall is the state the sweep photographed.
        ProGate.setTestOverride(app, false)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        ProBilling.resetForTests()
        RuntimeEnvironment.setFontScale(1.0f)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------ helpers

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun contentRoot(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) ?: content
    }

    /** The sweep's phone shape: 360dp wide, 800dp tall, system bars inset as on the device. */
    private fun layoutAt360(activity: Activity, root: View) {
        val d = activity.resources.displayMetrics.density
        val w = Math.round(360f * d)
        val h = Math.round(800f * d)
        val bar = Math.round(48f * d)
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

    /** A view's rectangle in the root's coordinates (scroll offsets applied). */
    private fun rootBounds(v: View): Rect {
        var left = 0
        var top = 0
        var cur: View = v
        while (true) {
            left += cur.left
            top += cur.top
            val parent = cur.parent as? View ?: break
            left -= parent.scrollX
            top -= parent.scrollY
            cur = parent
        }
        return Rect(left, top, left + v.width, top + v.height)
    }

    /**
     * What the person can actually see of [v] inside the root: null when every ancestor clips it
     * away. This is the same clipping the phone applies, computed without a real window.
     */
    private fun visibleInRoot(v: View): Rect? {
        if (v.width <= 0 || v.height <= 0) return null
        val rect = rootBounds(v)
        var cur: View? = v.parent as? View
        while (cur != null) {
            if (!rect.intersect(rootBounds(cur))) return null
            cur = cur.parent as? View
        }
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }

    private fun fullyVisible(v: View): Boolean {
        val seen = visibleInRoot(v) ?: return false
        return seen.width() == v.width && seen.height() == v.height
    }

    // ---- the a11y rule the sweep asks for: named + 48dp, on the node that is focused ----

    private fun clickables(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.isClickable) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /**
     * The name a screen reader gets from the focused node itself. A clickable container whose
     * label sits on an unmerged child TextView has none — that is exactly M7.
     */
    private fun ownName(v: View): String {
        v.contentDescription?.toString()?.let { if (it.isNotBlank()) return it }
        if (v is TextView && !v.text.isNullOrBlank()) return v.text.toString()
        return ""
    }

    private fun idOf(v: View): String = if (v.id == View.NO_ID) {
        v.javaClass.simpleName
    } else {
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.javaClass.simpleName)
    }

    private fun assertNamedAndBig(screen: String, root: View, activity: Activity) {
        val min = Math.round(48f * activity.resources.displayMetrics.density)
        val problems = mutableListOf<String>()
        for (v in clickables(root)) {
            if (v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO) continue
            if (visibleInRoot(v) == null) continue
            val name = ownName(v)
            if (name.isBlank()) {
                problems += "$screen: clickable ${idOf(v)} has no accessible name of its own"
            }
            if (v.width < min || v.height < min) {
                problems += "$screen: clickable ${idOf(v)} is ${v.width}x${v.height}px " +
                    "(${Math.round(v.width / activity.resources.displayMetrics.density)}x" +
                    "${Math.round(v.height / activity.resources.displayMetrics.density)}dp), needs 48x48dp"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ------------------------------------------------------------------ H3 Pro

    private fun openProFree(activity: ShellActivity) {
        activity.findViewById<View>(R.id.swNavMe).performClick()
        idle()
        activity.findViewById<View>(R.id.swMePro).performClick()
        idle()
        pollUntil { activity.findViewById<View>(R.id.swProScreen) != null }
    }

    @Test(timeout = 60_000)
    fun `pro free price and unlock are fully above the dock at 360dp font 1`() {
        RuntimeEnvironment.setFontScale(1.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                openProFree(a)
                val root = contentRoot(a)
                layoutAt360(a, root)

                val price = a.findViewById<View>(R.id.swProPrice)
                val buy = a.findViewById<View>(R.id.swProBuy)
                val pill = a.findViewById<View>(R.id.swShellNav)
                val disc = a.findViewById<View>(R.id.swNavScan)
                assertNotNull("the Free paywall shows its price line", price)
                assertNotNull("the Free paywall shows Unlock Pro", buy)
                assertNotNull("the floating dock is on screen", pill)

                val pillRect = rootBounds(pill)
                val dockTop = minOf(
                    pillRect.top,
                    disc?.let { rootBounds(it).top } ?: pillRect.top
                )

                assertTrue(
                    "the price panel is not shown without a swipe (H3)",
                    fullyVisible(price)
                )
                assertTrue(
                    "Unlock Pro is not shown without a swipe (H3)",
                    fullyVisible(buy)
                )
                val priceRect = rootBounds(price)
                val buyRect = rootBounds(buy)
                assertFalse(
                    "the price panel sits under the floating dock (H3): " +
                        "$priceRect vs dock top ${dockTop}",
                    Rect.intersects(priceRect, pillRect) || priceRect.bottom > dockTop
                )
                assertFalse(
                    "Unlock Pro sits under the floating dock (H3): " +
                        "$buyRect vs dock top ${dockTop}",
                    Rect.intersects(buyRect, pillRect) || buyRect.bottom > dockTop
                )
                // A pinned bar that floats far above the dock reads as a broken screen: it must
                // hug the dock, leaving only the shell's own breathing room.
                val d = a.resources.displayMetrics.density
                val gapDp = Math.round((dockTop - buyRect.bottom) / d)
                assertTrue(
                    "the pinned offer hugs the dock: ${gapDp}dp between Unlock Pro " +
                        "(bottom ${buyRect.bottom}) and the dock (top $dockTop), " +
                        "page ${root.height}, price ${priceRect}, buy ${buyRect}",
                    gapDp in 0..24
                )
                // …and it must not eat the page: the promise and the tool grid still scroll in
                // their own area above it.
                val screen = a.findViewById<View>(R.id.swProScreen)
                val kids = (0 until (screen as ViewGroup).childCount).map { screen.getChildAt(it) }
                val scroller = kids.filterIsInstance<android.widget.ScrollView>().firstOrNull()
                val bar = kids.firstOrNull { it !== scroller && it.isShown }
                assertNotNull("the paywall scrolls its copy in its own area", scroller)
                assertNotNull("the price and Unlock sit in a pinned bar", bar)
                assertTrue(
                    "the pinned offer leaves room for the promise: bar ${bar!!.height}px of " +
                        "${screen.height}px",
                    bar.height * 5 <= screen.height * 2
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun `pro free unlock is still reachable at font 2`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                openProFree(a)
                val root = contentRoot(a)
                layoutAt360(a, root)

                val buy = a.findViewById<View>(R.id.swProBuy)
                val pill = a.findViewById<View>(R.id.swShellNav)
                val dockTop = rootBounds(pill).top
                assertTrue("Unlock Pro is on screen at 2.0x", fullyVisible(buy))
                assertTrue(
                    "Unlock Pro is above the dock at 2.0x (bottom ${rootBounds(buy).bottom}, " +
                        "dock top $dockTop)",
                    rootBounds(buy).bottom <= dockTop
                )
                val min = Math.round(48f * a.resources.displayMetrics.density)
                assertTrue("Unlock Pro keeps a 48dp target at 2.0x", buy.height >= min)
            }
        }
    }

    // ------------------------------------------------------------------ M7 + M3 Settings

    @Test(timeout = 60_000)
    fun `settings rows carry their own name and a 48dp target`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                val root = contentRoot(a)
                layoutAt360(a, root)
                assertNamedAndBig("Settings", root, a)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `the unnamed strip at the top of scrolled settings is the text language row and is named`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                val root = contentRoot(a)
                layoutAt360(a, root)

                // M3: the 336x11dp clickable in the sweep is this row, clipped by the ScrollView
                // because the list is scrolled — not a stray clickable.
                val row = a.findViewById<View>(R.id.rowOcrLanguage)
                assertNotNull("the Text language row exists", row)
                val min = Math.round(48f * a.resources.displayMetrics.density)
                assertTrue(
                    "the Text language row is a full 48dp target, not an 11dp strip: " +
                        "${row.height}px",
                    row.height >= min
                )
                val name = ownName(row)
                assertTrue(
                    "the Text language row has no name when its label is scrolled off (M3/M7)",
                    name.isNotBlank()
                )
                assertTrue(
                    "the name is the row's own label, got \"$name\"",
                    name.contains(a.getString(R.string.settings_ocr_language), ignoreCase = true)
                )
            }
        }
    }

    // ------------------------------------------------------------------ M7 Me

    @Test(timeout = 60_000)
    fun `me rows carry their own name and a 48dp target`() {
        ActivityScenario.launch(ShellActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                a.findViewById<View>(R.id.swNavMe).performClick()
                idle()
                val root = contentRoot(a)
                layoutAt360(a, root)
                assertNamedAndBig("Me", root, a)
            }
        }
    }

    // ------------------------------------------------------------------ M4 + M7 Trash

    @Test(timeout = 60_000)
    fun `trash actions carry their own name and a 48dp target`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Sweep trash")
        runBlocking { repo.moveToTrash(id) }
        ActivityScenario.launch(TrashActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                assertTrue(
                    "the trash row is loaded",
                    pollUntil {
                        a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.trashList)
                            ?.adapter?.itemCount == 1
                    }
                )
                idle()
                val root = contentRoot(a)
                layoutAt360(a, root)
                assertNamedAndBig("Trash", root, a)
            }
        }
    }

    // ------------------------------------------------------------------ M7 the document More sheet

    @Test(timeout = 60_000)
    fun `document more sheet rows carry their own name and a 48dp target`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Sweep sheet")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { s ->
            s.onActivity { a ->
                idle()
                pollUntil { a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
                a.findViewById<View>(R.id.btnMore).performClick()
                assertTrue(
                    "the More sheet opens",
                    pollUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
                )
                idle()
                val dialog = ShadowDialog.getLatestDialog()
                val decor = dialog.window?.decorView
                assertNotNull("the More sheet has a window", decor)
                layoutAt360(a, decor!!)
                assertNamedAndBig("Document More sheet", decor, a)
                dialog.dismiss()
            }
        }
    }

    // ------------------------------------------------------------------ M6 PDF/A result

    @Test(timeout = 60_000)
    fun `a pdf result shows a typed file card instead of a blank white preview`() {
        val pdf = File(app.cacheDir, "documents/result/id-card-pdfa.pdf").apply {
            parentFile?.mkdirs()
            writeText(
                "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
                    "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
                    "trailer<</Root 1 0 R>>\n%%EOF\n"
            )
        }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "pdfa", files = listOf(pdf), mime = "application/pdf")
        ).use { s ->
            s.onActivity { a ->
                idle()
                val root = contentRoot(a)
                layoutAt360(a, root)

                val badge = a.findViewById<TextView>(R.id.toolResultTypeBadge)
                assertNotNull(
                    "a lone PDF result shows a type card, not an empty white box (M6)",
                    badge
                )
                assertEquals("PDF", badge.text.toString())
                assertEquals(
                    "id-card-pdfa.pdf",
                    a.findViewById<TextView>(R.id.toolResultFileName).text.toString()
                )
                val preview = a.findViewById<View>(R.id.toolResultPreview)
                assertFalse(
                    "the blank white preview box is no longer shown (M6)",
                    preview.isShown
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun `the pdf type badge exists in both languages`() {
        val en = resDir("values")
        val hi = resDir("values-hi")
        for (dir in listOf(en, hi)) {
            val found = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
                .orEmpty()
                .any { file ->
                    Regex("""<string name="tools_result_type_pdf">\s*PDF\s*</string>""")
                        .containsMatchIn(file.readText())
                }
            assertTrue("tools_result_type_pdf missing in ${dir.name}", found)
        }
    }

    private fun resDir(locale: String): File {
        val module = listOf(File("."), File("app"), File(".."))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: throw AssertionError("cannot find app/src/main/res")
        return File(module, "src/main/res/$locale").apply {
            assertTrue("missing $path", isDirectory)
        }
    }
}
