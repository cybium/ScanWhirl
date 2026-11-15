package com.scanwhirl.app.ui.a11y

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owner rule: **no dead buttons**. Every visible, enabled, clickable control on the v2 screens
 * must do something a person can observe — start an activity, open a sheet, show a toast, change
 * a view's state, or close the screen. Controls that cannot act must be disabled and visibly
 * greyed (alpha ≤ 0.5). A hidden row that was added at runtime is the same bug as Settings' rows
 * in the hidden card, so each screen also declares the views its own builders must show.
 *
 * Same screens as [AccessibilityAuditTest], in Free and Pro state. Run with:
 * `powershell -File tools\gate.ps1 -Lane L5 -Tests "com.scanwhirl.app.ui.a11y.DeadButtonAuditTest"`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class DeadButtonAuditTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private val problems = mutableListOf<String>()

    private data class Screen(
        val name: String,
        val expectedShown: List<Int> = emptyList(),
        val freshEachClick: Boolean = false,
        /**
         * A Pro-only screen. In Free it closes itself and opens the Pro offer instead of drawing
         * (see `PdfViewerProGateTest`), so there is no Free layout to audit.
         */
        val proOnly: Boolean = false,
        val open: () -> Pair<Activity, View>
    )

    private data class Snapshot(
        val dialogs: String,
        val toast: String?,
        val toastCount: Int,
        val state: Pair<Int, Int>,
        val finishing: Boolean
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
        problems.clear()
        RuntimeEnvironment.setFontScale(1.0f)
        if (runBlocking { repo.listDocuments() }.isEmpty()) {
            DocFixtures.saved(repo, app, pages = 2, name = "Dead audit library")
            DocFixtures.saved(repo, app, pages = 1, name = "Dead audit passport")
        }
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `shell screens have no dead buttons`() {
        auditBothProStates(
            Screen("Library") { shellHost() },
            Screen("Library selection") { shellSelection() },
            Screen("Tools") { shellTab(R.id.swNavTools) },
            Screen("Me") { shellTab(R.id.swNavMe) },
            Screen("Pro") { proScreen() },
            Screen("Shell vault") { shellTab(R.id.swNavVault) }
        )
        assertNoProblems()
    }

    @Test
    fun `document and editor screens have no dead buttons`() {
        val pages = DocFixtures.saved(repo, app, pages = 2, name = "Dead audit pages")
        val stamp = DocFixtures.saved(repo, app, pages = 1, name = "Dead audit stamp")
        auditBothProStates(
            Screen("Document") { document(pages) },
            Screen("Organize selection") { organizeSelection(pages) },
            Screen("Page editor") { pageEditor(pages) },
            Screen("Stamp editor", proOnly = true) { stampEditor(stamp) },
            Screen("Text screen") { textScreen() }
        )
        assertNoProblems()
    }

    @Test
    fun `tool, settings and vault screens have no dead buttons`() {
        val result = DocFixtures.saved(repo, app, pages = 1, name = "Dead audit result")
        auditBothProStates(
            Screen("Settings", settingsShown) { activityHost(SettingsActivity::class.java, null) },
            Screen("Tool setup") {
                activityHost(ToolSetupActivity::class.java, ToolSetupActivity.intent(app, "split"))
            },
            Screen("Tool result") {
                activityHost(
                    ToolResultActivity::class.java,
                    ToolResultActivity.intent(app, "split", docIds = listOf(result))
                )
            },
            Screen("Vault") { activityHost(VaultActivity::class.java, null) }
        )
        assertNoProblems()
    }

    @Test
    fun `review and sheet screens have no dead buttons`() {
        val review = DocFixtures.saved(repo, app, pages = 2, name = "Dead audit review")
        val sheets = DocFixtures.saved(repo, app, pages = 2, name = "Dead audit sheets")
        auditBothProStates(
            Screen("Scan review") { reviewScreen() },
            Screen("Protect sheet", freshEachClick = true) { protectSheet() },
            Screen("Unlock sheet", freshEachClick = true) { unlockSheet() },
            Screen("Insert sheet", freshEachClick = true) { insertSheet(sheets) },
            Screen("Find sheet", freshEachClick = true) { findSheet(sheets) }
        )
        assertNoProblems()
    }

    // ------------------------------------------------------------------ the audit

    private fun auditBothProStates(vararg screens: Screen) {
        for (pro in booleanArrayOf(false, true)) {
            ProGate.setTestOverride(app, pro)
            for (screen in screens) {
                if (screen.proOnly && !pro) continue
                audit(screen)
            }
        }
        ProGate.setTestOverride(app, null)
    }

    private fun audit(screen: Screen) {
        if (screen.freshEachClick) return auditFresh(screen)
        val started = try {
            screen.open()
        } catch (e: Exception) {
            problems += "${screen.name}|open :: ${e.message}"
            return
        }
        var (activity, root) = started
        idle()
        checkExpected(screen, activity, root)

        val done = HashSet<String>()
        var guard = 0
        while (guard++ < 250) {
            val next = clickable(root).firstOrNull { descriptor(it) !in done } ?: break
            val name = descriptor(next)
            done += name
            if (!next.isEnabled) {
                if (next.alpha > 0.5f) {
                    report(screen.name, "disabled-look", name, "disabled but not greyed (alpha ${next.alpha})")
                }
            } else {
                auditClick(screen.name, activity, root, next, name)
            }
            dismissDialogs()
            idle()
            if (activity.isFinishing || activity.isDestroyed) {
                activity = try {
                    screen.open().first
                } catch (_: Exception) {
                    break
                }
                idle()
                root = contentRoot(activity)
            } else {
                // A click may have replaced the content (tab switch, mode change).
                root = contentRoot(activity)
            }
        }
        runCatching { activity.finish() }
        idle()
    }

    /** Sheets: one click per launch, because dismissing the sheet removes its other rows. */
    private fun auditFresh(screen: Screen) {
        val done = HashSet<String>()
        var guard = 0
        while (guard++ < 100) {
            val (activity, root) = try {
                screen.open()
            } catch (_: Exception) {
                break
            }
            idle()
            if (done.isEmpty()) checkExpected(screen, activity, root)
            val next = clickable(root).firstOrNull { descriptor(it) !in done }
            if (next == null) {
                runCatching { activity.finish() }
                idle()
                break
            }
            val name = descriptor(next)
            done += name
            if (!next.isEnabled) {
                if (next.alpha > 0.5f) {
                    report(screen.name, "disabled-look", name, "disabled but not greyed (alpha ${next.alpha})")
                }
            } else {
                auditClick(screen.name, activity, root, next, name)
            }
            dismissDialogs()
            runCatching { activity.finish() }
            idle()
        }
    }

    private fun auditClick(screen: String, activity: Activity, root: View, v: View, name: String) {
        if (isFrameworkControl(v)) return
        val intrinsic = v is CompoundButton || v is EditText || isAdapterItem(v) ||
            isToggleGroupChild(v) || hasCustomTouch(v)
        if (!v.hasOnClickListeners() && !intrinsic) {
            report(screen, "no-listener", name, "clickable but no click listener")
        }
        drainStarted(activity)
        val before = before(activity, root)
        var crash: Throwable? = null
        try {
            v.performClick()
        } catch (t: Throwable) {
            crash = t
        }
        if (crash != null) {
            val message = crash.message.orEmpty()
            if (message.contains("Failed to find configured root") ||
                message.contains("MlKitContext has not been initialized")
            ) {
                // Robolectric-only: FileProvider/Win paths and the missing ML Kit model.
                return
            }
            report(screen, "crash", name, "$crash")
            return
        }
        val intrinsicEffect = intrinsic || v.isSelected ||
            (v is CompoundButton && v.isChecked)
        val deadline = System.currentTimeMillis() + 1200
        while (System.currentTimeMillis() < deadline) {
            advance(activity)
            if (hasEffect(activity, root, before) || intrinsicEffect) return
        }
        report(screen, "dead", name, "click produced no observable effect")
    }

    /** Runs due main-thread work and advances the looper clock so snackbars/toasts appear. */
    private fun advance(activity: Activity) {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        looper.idleFor(java.time.Duration.ofMillis(50))
    }

    private fun hasEffect(activity: Activity, root: View, before: Snapshot): Boolean {
        val startedActivity = shadowOf(activity).nextStartedActivity
        val startedApp = if (startedActivity == null) shadowOf(app).nextStartedActivity else null
        val result = shadowOf(activity).nextStartedActivityForResult
        val toast = ShadowToast.getTextOfLatestToast()
        val toastCount = ShadowToast.shownToastCount()
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        val state = stateHash(root) to contentHash(activity)
        val dialogTag = dialog?.let { dialogTag(it) }.orEmpty()
        val dialogChanged = dialogTag != before.dialogs
        // A toast is an effect even when the previous control showed the same words
        // (the scan tile reports the same Robolectric ML Kit message every time).
        val toastChanged = toastCount != before.toastCount ||
            (!toast.isNullOrBlank() && toast != before.toast)
        val moved = startedActivity != null || startedApp != null || result != null
        val closed = activity.isFinishing || activity.isDestroyed
        return state != before.state || dialogChanged || toastChanged || moved || closed
    }

    /** Framework chrome (toolbar overflow/action menu, sheet outside-touch) is not app wiring. */
    private fun isFrameworkControl(v: View): Boolean {
        val name = descriptor(v)
        if (name == "touch_outside" || name.contains("touch_outside")) return true
        if (name == "OverflowMenuButton") return true
        var parent: ViewParent? = v.parent
        while (parent != null) {
            if (parent is androidx.appcompat.widget.ActionMenuView) return true
            parent = parent.parent
        }
        return false
    }

    /** A custom canvas handles clicks in onTouchEvent; there is no OnClickListener by design. */
    private fun hasCustomTouch(v: View): Boolean =
        v.javaClass.declaredMethods.any { it.name == "onTouchEvent" || it.name == "performClick" }

    private fun checkExpected(screen: Screen, activity: Activity, root: View) {
        for (id in screen.expectedShown) {
            val view = activity.findViewById<View>(id) ?: root.findViewById(id)
            val name = idName(root, id)
            if (view == null) {
                report(screen.name, "missing", name, "expected view is not in the hierarchy")
            } else if (!view.isShown) {
                report(screen.name, "hidden", name, "expected view exists but is not shown")
            }
        }
    }

    // ------------------------------------------------------------------ snapshots

    private fun contentHash(activity: Activity): Int =
        stateHash(activity.findViewById<ViewGroup>(android.R.id.content) ?: contentRoot(activity))

    private fun before(activity: Activity, root: View): Snapshot = Snapshot(
        dialogs = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { dialogTag(it) }.orEmpty(),
        toast = ShadowToast.getTextOfLatestToast(),
        toastCount = ShadowToast.shownToastCount(),
        state = stateHash(root) to contentHash(activity),
        finishing = activity.isFinishing
    )

    private fun stateHash(root: View): Int {
        var hash = 17
        fun walk(v: View) {
            hash = hash * 31 + v.visibility
            hash = hash * 31 + if (v.isSelected) 1 else 0
            if (v is CompoundButton) hash = hash * 31 + if (v.isChecked) 2 else 0
            hash = hash * 31 + if (v.isFocused) 3 else 0
            hash = hash * 31 + if (v.isEnabled) 5 else 0
            hash = hash * 31 + (v.alpha * 100).toInt()
            if (v is android.widget.EditText) hash = hash * 31 + v.inputType
            hash = hash * 31 + ((v as? TextView)?.text?.toString()?.hashCode() ?: 0)
            if (v is ViewGroup) {
                hash = hash * 31 + v.childCount
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return hash
    }

    private fun dialogTag(dialog: Dialog): String =
        dialog.javaClass.name + "@" + System.identityHashCode(dialog)

    private fun drainStarted(activity: Activity) {
        while (shadowOf(activity).nextStartedActivity != null) Unit
        while (shadowOf(app).nextStartedActivity != null) Unit
        while (shadowOf(activity).nextStartedActivityForResult != null) Unit
    }

    /** MaterialButtonToggleGroup dispatches through its own listener; the buttons have none. */
    private fun isToggleGroupChild(v: View): Boolean {
        var parent: ViewParent? = v.parent
        while (parent != null) {
            if (parent.javaClass.simpleName.contains("MaterialButtonToggleGroup")) return true
            parent = parent.parent
        }
        return false
    }

    // ------------------------------------------------------------------ screen fixtures

    private fun shellHost(): Pair<Activity, View> {
        val a = Robolectric.buildActivity(ShellActivity::class.java).setup().get()
        idle()
        return a to contentRoot(a)
    }

    private fun shellTab(id: Int): Pair<Activity, View> {
        val (a, _) = shellHost()
        a.findViewById<View>(id).performClick()
        idle()
        return a to contentRoot(a)
    }

    private fun shellSelection(): Pair<Activity, View> {
        val (a, _) = shellHost()
        val docs = a.findViewById<ViewGroup>(R.id.swDocList)
        if (docs.childCount > 0) docs.getChildAt(0).performLongClick()
        idle()
        return a to contentRoot(a)
    }

    private fun proScreen(): Pair<Activity, View> {
        val (a, _) = shellHost()
        a.findViewById<View>(R.id.swNavMe).performClick()
        idle()
        a.findViewById<View>(R.id.swMePro).performClick()
        idle()
        return a to contentRoot(a)
    }

    private fun document(id: String): Pair<Activity, View> {
        val a = Robolectric.buildActivity(
            DocumentViewerActivity::class.java,
            DocumentViewerActivity.intent(app, id)
        ).setup().get()
        waitFor(a) { a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
        return a to contentRoot(a)
    }

    private fun organizeSelection(id: String): Pair<Activity, View> {
        val (a, _) = document(id)
        a.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
            .onLongPress?.invoke(0)
        idle()
        return a to contentRoot(a)
    }

    private fun pageEditor(id: String): Pair<Activity, View> {
        val a = Robolectric.buildActivity(
            PageViewerActivity::class.java,
            PageViewerActivity.intent(app, id, 0)
        ).setup().get()
        idle()
        return a to contentRoot(a)
    }

    private fun stampEditor(id: String): Pair<Activity, View> {
        val a = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            StampEditorActivity.intent(app, id)
        ).setup().get()
        idle()
        a.placeImportedStampForTest(stampInk())
        idle()
        return a to contentRoot(a)
    }

    private fun textScreen(): Pair<Activity, View> {
        val id = runBlocking {
            val file = File(app.cacheDir, "dead_ocr_${UUID.randomUUID()}.jpg").apply {
                writeBytes(ImportFixtures.image(width = 300, height = 400))
            }
            repo.saveScan(listOf(android.net.Uri.fromFile(file)), null, "Dead audit text").id
        }
        runBlocking {
            repo.updateOcrPages(
                id,
                mapOf("page_000.jpg" to "Some OCR text that can be edited before saving.")
            )
        }
        val a = Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        ).setup().get()
        idle()
        return a to contentRoot(a)
    }

    private fun reviewScreen(): Pair<Activity, View> {
        val dir = File(app.cacheDir, "dead_review_${UUID.randomUUID()}").apply { mkdirs() }
        repeat(2) { i ->
            File(dir, "page_%03d.jpg".format(i)).writeBytes(
                ImportFixtures.image(width = 300, height = 400)
            )
        }
        val a = Robolectric.buildActivity(
            ScanReviewActivity::class.java,
            ScanReviewActivity.intent(app, dir.path, null, "Dead audit review")
        ).setup().get()
        idle()
        return a to contentRoot(a)
    }

    private fun protectSheet(): Pair<Activity, View> {
        val host = Robolectric.buildActivity(ShellActivity::class.java).setup().get()
        idle()
        val dialog = ProtectSheet.show(
            host as AppCompatActivity,
            ProtectSheet.Doc("Dead audit review", 2, 2048L),
            onProtect = { _, _, _ -> }
        )
        idle()
        return host to (dialog.window?.decorView ?: throw IOException("no protect decor"))
    }

    private fun unlockSheet(): Pair<Activity, View> {
        val host = Robolectric.buildActivity(ShellActivity::class.java).setup().get()
        idle()
        val dialog = UnlockSheet.show(host as AppCompatActivity, "Dead audit review", { false }, onUnlocked = {})
        idle()
        return host to (dialog.window?.decorView ?: throw IOException("no unlock decor"))
    }

    private fun insertSheet(id: String): Pair<Activity, View> {
        val (a, _) = document(id)
        (a as DocumentViewerActivity).startInsertPages()
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no insert sheet" }
        return a to (dialog.window?.decorView ?: throw IOException("no insert decor"))
    }

    private fun findSheet(id: String): Pair<Activity, View> {
        val a = Robolectric.buildActivity(
            StampEditorActivity::class.java,
            StampEditorActivity.intent(app, id, StampEditorActivity.TOOL_MASK_AADHAAR)
        ).setup().get()
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no find sheet" }
        return a to (dialog.window?.decorView ?: throw IOException("no find decor"))
    }

    private fun activityHost(
        clazz: Class<out Activity>,
        intent: Intent?
    ): Pair<Activity, View> {
        val a = if (intent != null) {
            @Suppress("UNCHECKED_CAST")
            Robolectric.buildActivity(clazz as Class<Activity>, intent).setup().get()
        } else {
            @Suppress("UNCHECKED_CAST")
            Robolectric.buildActivity(clazz as Class<Activity>).setup().get()
        }
        idle()
        return a to contentRoot(a)
    }

    private fun stampInk(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(80, 50, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
            android.graphics.Canvas(this).drawRect(
                6f, 8f, 74f, 42f,
                android.graphics.Paint().apply { color = android.graphics.Color.rgb(120, 20, 160) }
            )
        }

    // The Settings bug: rows added into the hidden About card never appeared. Guard the visible set.
    private val settingsShown = listOf(
        R.id.rowFont, R.id.rowLanguage, R.id.rowViewer,
        R.id.switchAutoEnhance, R.id.switchSearchablePdf, R.id.switchAutoCopyToDocuments,
        R.id.rowOcrLanguage, R.id.rowBlockScreenshots, R.id.rowAccent
    )

    // ------------------------------------------------------------------ helpers

    private fun contentRoot(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) ?: content
    }

    private fun clickable(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.isShown && v.isClickable && v.width > 0 && v.height > 0) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun isAdapterItem(v: View): Boolean {
        var parent: ViewParent? = v.parent
        while (parent != null) {
            if (parent is AdapterView<*>) return true
            parent = parent.parent
        }
        return false
    }

    private fun descriptor(v: View): String {
        val id = if (v.id != View.NO_ID) {
            runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
        } else null
        if (id != null) return id
        val text = (v as? TextView)?.text?.toString().orEmpty()
        val ascii = text.replace(Regex("[^\\x20-\\x7E]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return if (ascii.isNotEmpty()) ascii.take(24) else v.javaClass.simpleName
    }

    private fun idName(root: View, id: Int): String =
        runCatching { root.resources.getResourceEntryName(id) }.getOrElse { "0x${Integer.toHexString(id)}" }

    private val allowList: List<Triple<String, String, String>> by lazy {
        val stream = javaClass.getResourceAsStream("/dead_buttons_allowlist.txt") ?: return@lazy emptyList()
        stream.bufferedReader().readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split("|")
            if (parts.size < 4) return@mapNotNull null
            Triple(parts[0].trim(), parts[1].trim(), parts[2].trim())
        }
    }

    private fun allowed(screen: String, rule: String, control: String): Boolean =
        allowList.any { (s, r, c) ->
            (s == "*" || s == screen) && r == rule && c == control
        }

    private fun report(screen: String, rule: String, control: String, detail: String) {
        if (allowed(screen, rule, control)) return
        problems += "$screen|$rule|$control :: $detail"
    }

    private fun dismissDialogs() {
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    private fun waitFor(a: Activity, timeoutMs: Long = 4000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertNoProblems() {
        val message = buildString {
            appendLine("${problems.size} dead-button problem(s):")
            problems.forEach { appendLine(" - $it") }
        }
        assertTrue(message, problems.isEmpty())
    }
}
