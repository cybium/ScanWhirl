package com.scanwhirl.app.ui.a11y

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.PageViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.protect.ProtectSheet
import com.scanwhirl.app.ui.protect.UnlockSheet
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.ToolSetupActivity
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Accessibility audit — an always-on walk of every v2 screen that can be started in
 * Robolectric. It is the accessibility twin of the L2 security audit: every screen is
 * measured at 360dp width and font scale 1.3 and 2.0, then the view tree is checked for
 *
 *  1. clickable views with no content description and no visible text;
 *  2. touch targets smaller than 48x48dp;
 *  3. text contrast below 4.5:1 (body) or 3:1 (large) against the real background layers;
 *  4. text that clips, ellipsizes or overlaps a sibling at large font scales;
 *  5. meaningful images with no description;
 *  6. sheets whose title is not the accessibility pane title (focus starts at the title).
 *
 * Justified exceptions live in `app/src/test/resources/a11y_allowlist.txt`, one per line:
 * `screen|rule|key|reason`. `*` matches any screen. A key is the view id, or the text
 * prefix when the view has no id. Every exception must name its owning lane in the reason.
 *
 * Findings for screens owned by other lanes are listed in `docs/audit/2026-11-15/ACCESSIBILITY.md`
 * and allow-listed until that lane fixes them; this keeps the test green without pretending
 * the app is clean.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class AccessibilityAuditTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private val problems = mutableListOf<String>()
    private var allowedHits = 0

    private data class Allow(val screen: String, val rule: String, val key: String, val reason: String) {
        fun matches(screen: String, rule: String, key: String): Boolean =
            (this.screen == "*" || this.screen == screen) &&
                this.rule == rule &&
                (this.key == "*" || key == this.key || key.startsWith("${this.key}#"))
    }

    private val allowList: List<Allow> by lazy {
        val stream = javaClass.getResourceAsStream("/a11y_allowlist.txt") ?: return@lazy emptyList()
        stream.bufferedReader().readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            Allow(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim())
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        problems.clear()
        allowedHits = 0
        RuntimeEnvironment.setFontScale(1.0f)
        DocFixtures.saved(repo, app, pages = 2, name = "Audit library")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

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

    /** Runs [block] at the two required font scales. */
    private fun atScales(block: () -> Unit) {
        for (scale in floatArrayOf(1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            block()
        }
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ------------------------------------------------------------------ launchers

    private fun contentRoot(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) ?: content
    }

    private fun auditActivity(
        name: String,
        clazz: Class<out Activity>,
        intent: Intent? = null,
        before: (Activity) -> Unit = {}
    ) {
        val scenario: ActivityScenario<Activity> = if (intent != null) {
            ActivityScenario.launch(intent)
        } else {
            @Suppress("UNCHECKED_CAST")
            ActivityScenario.launch(clazz as Class<Activity>)
        }
        scenario.use { s ->
            s.onActivity { a ->
                idle()
                before(a)
                idle()
                audit(a, name, contentRoot(a))
            }
        }
    }

    private fun auditShell(name: String, before: (ShellActivity) -> Unit = {}) {
        ActivityScenario.launch(ShellActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                before(a)
                idle()
                audit(a, name, contentRoot(a))
            }
        }
    }

    private fun auditSheet(name: String, open: (AppCompatActivity) -> Dialog) {
        ActivityScenario.launch(ShellActivity::class.java).use { s ->
            s.onActivity { a ->
                idle()
                val dialog = open(a)
                idle()
                val decor = dialog.window?.decorView
                if (decor != null) {
                    audit(a, name, decor)
                    checkPaneTitle(name, decor)
                }
                dialog.dismiss()
            }
        }
    }

    // ------------------------------------------------------------------ screens

    @Test
    fun `shell tabs are accessible`() {
        atScales {
            auditShell("Library")
            auditShell("Library selection") { a ->
                val docs = a.findViewById<ViewGroup>(R.id.swDocList)
                assertTrue("a seeded document card is needed", docs.childCount > 0)
                docs.getChildAt(0).performLongClick()
                idle()
            }
            // UI-B1 #2: the Create sheet, opened from the centre Scan action.
            auditSheet("Create sheet") { host ->
                host.findViewById<View>(R.id.swNavScan).performClick()
                idle()
                requireNotNull(ShadowDialog.getLatestDialog()) { "no Create sheet" }
            }
            auditShell("Tools") { a ->
                a.findViewById<View>(R.id.swNavTools).performClick()
                idle()
            }
            auditShell("Me") { a ->
                a.findViewById<View>(R.id.swNavMe).performClick()
                idle()
            }
            auditShell("Pro") { a ->
                a.findViewById<View>(R.id.swNavMe).performClick()
                idle()
                a.findViewById<View>(R.id.swMePro).performClick()
                idle()
            }
            auditShell("Shell vault") { a ->
                a.findViewById<View>(R.id.swNavVault).performClick()
                idle()
            }
        }
        assertNoProblems()
    }

    @Test
    fun `organize grid and document viewer are accessible`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Audit document")
        atScales {
            auditActivity(
                "Document",
                DocumentViewerActivity::class.java,
                DocumentViewerActivity.intent(app, id)
            ) { a ->
                pollUntil {
                    a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
            }
            auditActivity(
                "Organize selection",
                DocumentViewerActivity::class.java,
                DocumentViewerActivity.intent(app, id)
            ) { a ->
                pollUntil {
                    a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                a.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                    .onLongPress?.invoke(0)
                idle()
            }
            auditActivity(
                "Draft bar",
                DocumentViewerActivity::class.java,
                DocumentViewerActivity.intent(app, id)
            ) { a ->
                pollUntil {
                    a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                a.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                    .onLongPress?.invoke(0)
                idle()
                a.findViewById<View>(R.id.btnSelRotate).performClick()
                idle()
                pollUntil {
                    a.findViewById<View>(R.id.draftBar)?.visibility == View.VISIBLE
                }
            }
        }
        assertNoProblems()
    }

    @Test
    fun `page editor and stamp editor are accessible`() {
        val pages = DocFixtures.saved(repo, app, pages = 2, name = "Audit pages")
        val stampId = DocFixtures.saved(repo, app, pages = 1, name = "Audit stamp")
        atScales {
            auditActivity(
                "Page editor",
                PageViewerActivity::class.java,
                PageViewerActivity.intent(app, pages, 0)
            )
            auditActivity(
                "Stamp editor",
                StampEditorActivity::class.java,
                StampEditorActivity.intent(app, stampId)
            )
        }
        assertNoProblems()
    }

    @Test
    fun `text settings tools and vault are accessible`() {
        val docId = runBlocking {
            repo.saveScan(
                listOf(android.net.Uri.fromFile(File(app.cacheDir, "a11y_${System.nanoTime()}.jpg").apply {
                    writeBytes(ImportFixtures.image(width = 300, height = 400))
                })),
                null,
                "Audit text"
            ).id
        }
        runBlocking {
            repo.updateOcrPages(
                docId,
                mapOf("page_000.jpg" to "Readable text for the accessibility audit.\nA second line.")
            )
        }
        atScales {
            auditActivity(
                "Text screen",
                OcrEditorActivity::class.java,
                Intent(app, OcrEditorActivity::class.java)
                    .putExtra(OcrEditorActivity.EXTRA_DOC_ID, docId)
            )
            auditActivity("Settings", SettingsActivity::class.java)
            auditActivity(
                "Tool setup",
                ToolSetupActivity::class.java,
                ToolSetupActivity.intent(app, "split")
            )
        }
        val resultDoc = DocFixtures.saved(repo, app, pages = 1, name = "Audit result")
        atScales {
            auditActivity(
                "Tool result",
                ToolResultActivity::class.java,
                ToolResultActivity.intent(app, "split", docIds = listOf(resultDoc))
            )
            auditActivity("Vault", VaultActivity::class.java)
        }
        assertNoProblems()
    }

    @Test
    fun `review and protect sheets are accessible`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Audit review")
        val actionsDoc = DocFixtures.saved(repo, app, pages = 2, name = "Audit actions")
        atScales {
            val staging = File(app.cacheDir, "a11y_review_${System.nanoTime()}").apply { mkdirs() }
            File(staging, "page_001.jpg").writeBytes(ImportFixtures.image(width = 300, height = 400))
            auditActivity(
                "Scan review",
                ScanReviewActivity::class.java,
                ScanReviewActivity.intent(app, staging.path, null, "Audit review")
            )
            auditSheet("Protect sheet") { host ->
                ProtectSheet.show(
                    host,
                    ProtectSheet.Doc("Audit review", 2, 2048L),
                    onProtect = { _, _, _ -> }
                )
            }
            auditSheet("Unlock sheet") { host ->
                UnlockSheet.show(
                    host,
                    "Audit review",
                    verify = { false },
                    onUnlocked = {}
                )
            }
            auditSheet("Actions sheet") { host ->
                com.scanwhirl.app.ui.DocumentActionsSheet.show(host, actionsDoc)
                idle()
                requireNotNull(ShadowDialog.getLatestDialog())
            }
        }
        assertNoProblems()
    }

    @Test
    fun `insert and mask sheets are accessible`() {
        val doc = DocFixtures.saved(repo, app, pages = 2, name = "Audit sheets")
        atScales {
            ActivityScenario.launch<DocumentViewerActivity>(
                DocumentViewerActivity.intent(app, doc)
            ).use { s ->
                s.onActivity { a ->
                    idle()
                    pollUntil {
                        a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                    }
                    a.startInsertPages()
                    idle()
                    auditLatestSheet(a, "Insert sheet")
                }
            }
            ActivityScenario.launch<StampEditorActivity>(
                StampEditorActivity.intent(app, doc, StampEditorActivity.TOOL_MASK_AADHAAR)
            ).use { s ->
                s.onActivity { a ->
                    idle()
                    pollUntil { ShadowDialog.getLatestDialog()?.isShowing == true }
                    auditLatestSheet(a, "Mask sheet")
                }
            }
        }
        assertNoProblems()
    }

    private fun auditLatestSheet(activity: Activity, name: String) {
        val dialog = ShadowDialog.getLatestDialog() ?: return
        val decor = dialog.window?.decorView ?: return
        audit(activity, name, decor)
        checkPaneTitle(name, decor)
        dialog.dismiss()
    }

    // ------------------------------------------------------------------ the audit

    private fun audit(activity: Activity, screen: String, root: View) {
        val density = activity.resources.displayMetrics.density
        val w = Math.round(activity.resources.configuration.screenWidthDp * density)
        val h = Math.round(activity.resources.configuration.screenHeightDp * density)
        val bar = Math.round(48f * density)
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

        val minTouch = Math.round(48f * density)
        val scale = RuntimeEnvironment.getFontScale()
        val label = "$screen @ ${"%.1f".format(scale)}x"

        fun walk(v: View) {
            if (v.isShown && v.width > 0 && v.height > 0) {
                val skip = v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (!skip) {
                    if (v.isClickable && !isLabelled(v)) {
                        report(screen, "label", v, "$label: clickable ${describe(v)} has no description or text")
                    }
                    if (v.isClickable && (v.width < minTouch || v.height < minTouch)) {
                        report(
                            screen, "touch", v,
                            "$label: ${describe(v)} is ${dp(v.width, density)}x${dp(v.height, density)}dp " +
                                "(minimum 48x48dp)"
                        )
                    }
                    if (v is TextView) {
                        checkContrast(screen, label, v)
                        checkTextFit(screen, label, v)
                    }
                    if (v is ImageView && !v.isClickable) checkImage(screen, label, v)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        checkOverlaps(screen, label, root)
    }

    private fun isLabelled(v: View): Boolean {
        if (!v.contentDescription.isNullOrBlank()) return true
        if (v is TextView) {
            if (!v.text.isNullOrBlank()) return true
            if (v is EditText && !v.hint.isNullOrBlank()) return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child.visibility == View.VISIBLE && isLabelled(child)) return true
            }
        }
        return false
    }

    private fun checkImage(screen: String, label: String, v: ImageView) {
        if (v.drawable == null) return
        if (!v.contentDescription.isNullOrBlank()) return
        // An image inside a labelled clickable card is part of that card's label.
        var p: View? = v.parent as? View
        while (p != null) {
            if (p.isClickable && isLabelled(p)) return
            p = p.parent as? View
        }
        report(screen, "image", v, "$label: image ${describe(v)} conveys no description")
    }

    private fun checkContrast(screen: String, label: String, v: TextView) {
        if (!v.isEnabled || v.alpha < 0.99f) return
        val hasText = !v.text.isNullOrBlank()
        val hint = if (v is EditText && v.text.isNullOrEmpty()) v.hint else null
        if (!hasText && hint.isNullOrBlank()) return
        var fg = if (hasText) v.currentTextColor else v.currentHintTextColor
        if (Color.alpha(fg) < 32) {
            // A selector can resolve to a transparent state in Robolectric; the default colour
            // is what the user actually sees.
            val fallback = if (hasText) v.textColors?.defaultColor else v.hintTextColors?.defaultColor
            if (fallback != null && Color.alpha(fallback) > Color.alpha(fg)) fg = fallback
        }
        if (Color.alpha(fg) == 0) return
        val bg = backgroundOf(v)
        val ratio = contrast(fg, bg)
        val sp = v.textSize / v.resources.displayMetrics.scaledDensity
        val bold = v.typeface?.isBold == true
        val large = sp >= 18f || (sp >= 14f && bold)
        val need = if (large) 3.0 else 4.5
        if (ratio + 0.05 < need) {
            val shown = (if (hasText) v.text else hint)?.toString().orEmpty().take(28)
            report(
                screen, "contrast", v,
                "$label: ${describe(v)} \"$shown\" is ${"%.2f".format(ratio)}:1 on " +
                    "#${Integer.toHexString(bg).takeLast(6)} (needs $need:1, ${"%.1f".format(sp)}sp)"
            )
        }
    }

    private fun checkTextFit(screen: String, label: String, v: TextView) {
        val shown = v.text?.toString().orEmpty().ifEmpty { v.hint?.toString().orEmpty() }
        if (shown.isEmpty()) return
        if (v is EditText && !v.hint.isNullOrEmpty()) {
            val need = v.paint.measureText(v.hint.toString())
            val avail = v.width - v.totalPaddingLeft - v.totalPaddingRight
            if (need > avail + 0.5f) {
                report(
                    screen, "clip", v,
                    "$label: ${describe(v)} hint needs ${need.toInt()}px but ${avail}px fit"
                )
            }
        }
        val layout = v.layout ?: return
        val availW = v.width - v.totalPaddingLeft - v.totalPaddingRight
        for (line in 0 until layout.lineCount) {
            val lineWidth = layout.getLineWidth(line)
            if (lineWidth > availW + 0.5f) {
                report(
                    screen, "clip", v,
                    "$label: ${describe(v)} line $line is ${lineWidth.toInt()}px wide but " +
                        "${availW}px fit between paddings"
                )
            }
            if (layout.getEllipsisCount(line) > 0) {
                report(
                    screen, "clip", v,
                    "$label: ${describe(v)} line $line is ellipsized " +
                        "(${layout.getEllipsisCount(line)} chars cut)"
                )
            }
        }
        val top = max(v.totalPaddingTop, v.compoundPaddingTop)
        val bottom = max(v.totalPaddingBottom, v.compoundPaddingBottom)
        val needed = layout.height + top + bottom
        if (needed > v.height + 0.5f) {
            report(
                screen, "clip", v,
                "$label: ${describe(v)} needs ${needed}px (layout ${layout.height} + pads " +
                    "$top/$bottom) but is ${v.height}px tall"
            )
        }
        val parent = v.parent as? View
        if (parent != null && !inScrollChain(v)) {
            var right = v.right
            var bottomEdge = v.bottom
            var p: View? = parent
            while (p != null && p !== v.rootView) {
                right += p.left
                bottomEdge += p.top
                p = p.parent as? View
            }
            if (right > v.rootView.width) {
                report(
                    screen, "clip", v,
                    "$label: ${describe(v)} right edge $right exceeds screen ${v.rootView.width}"
                )
            }
            if (bottomEdge > v.rootView.height) {
                report(
                    screen, "clip", v,
                    "$label: ${describe(v)} bottom edge $bottomEdge exceeds screen ${v.rootView.height}"
                )
            }
        }
    }

    private fun checkOverlaps(screen: String, label: String, root: View) {
        fun walkGroup(g: ViewGroup) {
            val texts = (0 until g.childCount).mapNotNull { g.getChildAt(it) as? TextView }
                .filter { it.isShown && !it.text.isNullOrBlank() && it.background == null }
            for (i in texts.indices) for (j in i + 1 until texts.size) {
                val a = texts[i]
                val b = texts[j]
                val ow = min(a.right, b.right) - max(a.left, b.left)
                val oh = min(a.bottom, b.bottom) - max(a.top, b.top)
                if (ow <= 0 || oh <= 0) continue
                val smaller = min(a.width * a.height, b.width * b.height)
                if (smaller > 0 && ow * oh > smaller * 0.4f) {
                    report(
                        screen, "clip", a,
                        "$label: ${describe(a)} overlaps ${describe(b)} by ${ow * oh}px²"
                    )
                }
            }
            for (i in 0 until g.childCount) (g.getChildAt(i) as? ViewGroup)?.let { walkGroup(it) }
        }
        (root as? ViewGroup)?.let { walkGroup(it) }
    }

    private fun inScrollChain(v: View): Boolean {
        var p = v.parent as? View
        while (p != null) {
            val name = p.javaClass.simpleName
            if (name.contains("Scroll") || name.contains("ViewPager")) return true
            p = p.parent as? View
        }
        return false
    }

    private fun checkPaneTitle(screen: String, decor: View) {
        val pane = ViewCompat.getAccessibilityPaneTitle(decor)
        if (pane.isNullOrBlank()) {
            report(
                screen, "focus", decor,
                "sheet has no accessibility pane title; TalkBack starts on the first control " +
                    "instead of the title"
            )
        }
    }

    // ------------------------------------------------------------------ colour

    private fun backgroundOf(v: View): Int {
        val layers = ArrayList<Int>()
        var cur: View? = v
        while (cur != null) {
            // A Material button/card paints its fill through backgroundTint, not only through
            // the drawable's own colour.
            val tint = cur.backgroundTintList?.let { list ->
                list.getColorForState(cur.drawableState, list.defaultColor)
            }
            if (tint != null && Color.alpha(tint) > 128) {
                layers += tint
            } else {
                drawableColor(cur.background)?.let { layers += it }
            }
            cur = cur.parent as? View
        }
        var bg = Color.rgb(0x0E, 0x13, 0x16) // void_abyss, the window ink
        for (i in layers.indices.reversed()) bg = composite(layers[i], bg)
        return bg
    }

    private fun drawableColor(d: Drawable?): Int? = when (d) {
        null -> null
        is ColorDrawable -> d.color.takeIf { Color.alpha(it) > 0 }
        is GradientDrawable -> {
            val solid = d.color?.defaultColor
            if (solid != null && Color.alpha(solid) > 0) solid
            else d.colors?.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        }
        // A ripple is an overlay/mask, not the surface: its white mask layer must never
        // become the measured background (it made dark rows look white).
        is RippleDrawable -> null
        is StateListDrawable -> drawableColor(d.current)
        is com.google.android.material.shape.MaterialShapeDrawable -> d.fillColor?.defaultColor
        is LayerDrawable -> (0 until d.numberOfLayers)
            .mapNotNull { drawableColor(d.getDrawable(it)) }
            .lastOrNull()
        is InsetDrawable -> drawableColor(d.drawable)
        else -> null
    }

    private fun composite(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255.0
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(fg), Color.red(bg)),
            mix(Color.green(fg), Color.green(bg)),
            mix(Color.blue(fg), Color.blue(bg))
        )
    }

    private fun contrast(fg: Int, bg: Int): Double {
        val text = composite(fg, bg)
        val l1 = max(luminance(text), luminance(bg))
        val l2 = min(luminance(text), luminance(bg))
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(c)) +
            0.7152 * channel(Color.green(c)) +
            0.0722 * channel(Color.blue(c))
    }

    // ------------------------------------------------------------------ reporting

    private fun report(screen: String, rule: String, v: View?, detail: String) {
        val key = keyOf(v)
        if (allowList.any { it.matches(screen, rule, key) }) {
            allowedHits++
            return
        }
        problems += "$screen|$rule|$key :: $detail"
    }

    private fun keyOf(v: View?): String {
        if (v == null) return "pane"
        if (v.id != View.NO_ID) {
            return try {
                v.resources.getResourceEntryName(v.id)
            } catch (_: Exception) {
                "0x${Integer.toHexString(v.id)}"
            }
        }
        val text = (v as? TextView)?.text?.toString().orEmpty()
        if (text.isBlank()) return v.javaClass.simpleName
        // ASCII-only key: the allow-list must survive encoding round-trips through the report.
        val ascii = text.replace(Regex("[^\\x20-\\x7E]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (ascii.isEmpty()) v.javaClass.simpleName else ascii.take(24).trim()
    }

    private fun describe(v: View): String {
        val text = (v as? TextView)?.text?.toString().orEmpty()
            .ifEmpty { (v as? TextView)?.hint?.toString().orEmpty() }
        val shown = if (text.length > 32) text.take(32) + "…" else text
        return "[${v.javaClass.simpleName} id=${keyOf(v)} \"$shown\"]"
    }

    private fun dp(px: Int, density: Float): Int = Math.round(px / density)

    private fun assertNoProblems() {
        val message = buildString {
            appendLine("${problems.size} accessibility problem(s); $allowedHits allow-listed:")
            problems.forEach { appendLine(" - $it") }
        }
        assertTrue(message, problems.isEmpty())
    }
}
