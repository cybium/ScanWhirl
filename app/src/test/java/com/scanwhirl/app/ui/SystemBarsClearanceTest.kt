package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.backup.BackupActivity
import com.scanwhirl.app.ui.protect.LockToolsActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * T-FIX-INSETS-BACKUP — no screen's content may start under the status bar.
 *
 * The owner's Redmi reports a 108 px status bar and a 126 px navigation bar. On the shipped
 * build Backup & restore drew its title at y=54 px: BackupActivity.buildScreen() padded its own
 * root with dp(20/18) and never called View.applySystemBarsPadding(), while BaseActivity turns
 * decor fitting off. LockToolsActivity had the same omission.
 *
 * Three checks:
 *  - Backup & restore: a dispatched 108/126 px inset moves the title below the status bar and
 *    the last control above the navigation bar (red on the shipped code: title top 36-54 px).
 *  - Protect / Unlock chooser: same rule for the second screen that lacked the call.
 *  - Guard: every <activity> declared in AndroidManifest.xml is launched with an empty intent in
 *    Robolectric and must clear a dispatched status-bar inset, or (when it cannot start without
 *    extras) its source must handle WindowInsets itself. A declared screen that does neither
 *    fails this test, so the next new screen cannot ship with a title under the clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SystemBarsClearanceTest {

    private lateinit var app: Application

    /**
     * Screens that may draw under the status bar on purpose. Empty today: every declared screen
     * pads its content root. Each entry must name the screen and a concrete reason (an in-app
     * camera preview that has to run full screen is the only shape that belongs here); an entry
     * with a blank reason fails the guard rather than silencing it.
     */
    private val fullScreenExceptions = emptyMap<String, String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        com.scanwhirl.app.store.vault.VaultSession.close()
    }

    // ------------------------------------------------------------------ the two fixed screens

    @Test
    fun `backup and restore clears the status bar and the navigation bar`() {
        val controller = Robolectric.buildActivity(BackupActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = contentRoot(activity)
            val screenHeight = dispatchBars(activity, root)

            val title = textView(activity, activity.getString(R.string.backup_title))
            assertNotNull("Backup & restore shows its title", title)
            val titleTop = boundsIn(root, title!!).top
            assertTrue(
                "the title starts at ${titleTop}px, under the ${STATUS_BAR}px status bar " +
                    "(owner's Redmi: title half hidden at y=54px)",
                titleTop >= STATUS_BAR
            )

            // The last interactive control, and the very last line (the no-internet note).
            val lastControl = activity.findViewById<View>(R.id.backupReminder)
            assertNotNull("the reminder checkbox is on the screen", lastControl)
            val lastControlBottom = boundsIn(root, lastControl).bottom
            assertTrue(
                "the last control ends at ${lastControlBottom}px, inside the ${NAV_BAR}px " +
                    "navigation bar (page ${screenHeight}px)",
                lastControlBottom <= screenHeight - NAV_BAR
            )
            val lastLine = lastLeaf(root)
            assertNotNull("the screen has content", lastLine)
            val lastLineBottom = boundsIn(root, lastLine!!).bottom
            assertTrue(
                "the last line ends at ${lastLineBottom}px, inside the ${NAV_BAR}px navigation " +
                    "bar (page ${screenHeight}px)",
                lastLineBottom <= screenHeight - NAV_BAR
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `protect and unlock chooser clears the status bar and the navigation bar`() {
        val intent = LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT)
        val controller = Robolectric.buildActivity(LockToolsActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val root = contentRoot(activity)
            val screenHeight = dispatchBars(activity, root)

            val title = textView(activity, activity.getString(R.string.tool_protect))
            assertNotNull("the chooser shows its title", title)
            val titleTop = boundsIn(root, title!!).top
            assertTrue(
                "the chooser title starts at ${titleTop}px, under the ${STATUS_BAR}px status bar",
                titleTop >= STATUS_BAR
            )
            val lastControl = lastLeaf(root) ?: title
            val lastBottom = boundsIn(root, lastControl).bottom
            assertTrue(
                "the last line ends at ${lastBottom}px, inside the ${NAV_BAR}px navigation bar " +
                    "(page ${screenHeight}px)",
                lastBottom <= screenHeight - NAV_BAR
            )
        } finally {
            controller.close()
        }
    }

    // ------------------------------------------------------------------ the guard

    @Test
    fun `every declared activity clears a dispatched status bar inset or handles insets itself`() {
        val declared = declaredActivities()
        assertTrue(
            "the guard found almost no activities in AndroidManifest.xml (${declared.size}); " +
                "it would pass without checking anything",
            declared.size >= 20
        )

        for ((name, reason) in fullScreenExceptions) {
            assertTrue("full-screen exception $name needs a real reason", reason.isNotBlank())
            assertTrue("full-screen exception $name is not a declared activity", name in declared)
        }

        val failures = mutableListOf<String>()
        val runtimeChecked = mutableListOf<String>()
        val sourceChecked = mutableListOf<String>()

        for (name in declared) {
            if (name in fullScreenExceptions) continue
            val source = sourceFile(name)
            if (source == null) {
                failures += "$name has no source file under app/src/main/java"
                continue
            }
            val runtime = try {
                runtimeClearance(name)
            } catch (error: Throwable) {
                null
            }
            if (runtime != null) {
                runtimeChecked += name
                if (runtime < STATUS_BAR) {
                    failures += "$name: visible content starts at ${runtime}px, under the " +
                        "${STATUS_BAR}px status bar (content root does not apply the inset)"
                }
                continue
            }
            val text = source.readText()
            val handles = INSET_CALLS.any { text.contains(it) }
            if (handles) {
                sourceChecked += name
            } else {
                failures += "$name neither started with an empty intent nor handles WindowInsets " +
                    "in ${source.name} (looked for ${INSET_CALLS.joinToString()})"
            }
        }

        assertTrue(
            "the guard proved nothing at runtime; it must launch at least one screen:\n" +
                "checked at runtime: $runtimeChecked\nchecked by source: $sourceChecked",
            runtimeChecked.isNotEmpty()
        )
        assertTrue(
            "screens whose content starts under the status bar:\n" + failures.joinToString("\n") +
                "\n\nruntime-checked (${runtimeChecked.size}): ${runtimeChecked.joinToString()}" +
                "\nsource-checked (${sourceChecked.size}): ${sourceChecked.joinToString()}",
            failures.isEmpty()
        )
    }

    // ------------------------------------------------------------------ launching and measuring

    /**
     * Launches [name] with an empty intent and returns where its visible content starts after a
     * 108 px status bar is dispatched, or null when the screen cannot be opened with an empty
     * intent (it needs extras, finishes immediately, or its own setup fails in Robolectric).
     */
    private fun runtimeClearance(name: String): Int? {
        @Suppress("UNCHECKED_CAST")
        val clazz = Class.forName(name) as Class<Activity>
        val controller = Robolectric.buildActivity(clazz, Intent())
        try {
            controller.setup()
            val activity = controller.get()
            if (activity.isFinishing) return null
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
            if (content.childCount == 0) return null
            val root = content.getChildAt(0) ?: return null
            dispatchBars(activity, root)
            return visibleTop(root)
        } finally {
            runCatching { controller.close() }
        }
    }

    /**
     * The top of what the person sees, in content-root pixels. The root's padding is honoured;
     * when it has laid-out children the topmost child decides (a full-bleed child at 0 under a
     * padded root is exactly the defect).
     */
    private fun visibleTop(root: View): Int {
        var top = root.paddingTop
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.height <= 0) continue
                top = minOf(top, child.top)
            }
        }
        return top
    }

    /** A 108 px status bar and a 126 px navigation bar, exactly like the owner's phone. */
    private fun dispatchBars(activity: Activity, root: View): Int {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, STATUS_BAR, 0, NAV_BAR))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        val density = activity.resources.displayMetrics.density
        val w = Math.round(360f * density)
        val h = Math.round(800f * density)
        val passW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val passH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(passW, passH)
            root.layout(0, 0, w, h)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return h
    }

    // ------------------------------------------------------------------ view helpers

    private fun contentRoot(activity: Activity): View {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0) ?: content
    }

    /** The first text view whose label is exactly [text] (the screen title). */
    private fun textView(activity: Activity, text: String): TextView? {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.text?.toString() == text) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(contentRoot(activity))
        return out.firstOrNull()
    }

    /** The deepest last child of [root]: the bottom line of the screen's own content. */
    private fun lastLeaf(root: View): View? {
        var current: View? = root
        while (current is ViewGroup && current.childCount > 0) {
            current = current.getChildAt(current.childCount - 1)
        }
        return current
    }

    /** A view's rectangle in [root]'s coordinates (scroll offsets applied). */
    private fun boundsIn(root: View, view: View): Rect {
        var left = 0
        var top = 0
        var cur: View = view
        while (true) {
            left += cur.left
            top += cur.top
            if (cur === root) break
            val parent = cur.parent as? View ?: break
            left -= parent.scrollX
            top -= parent.scrollY
            cur = parent
        }
        return Rect(left, top, left + view.width, top + view.height)
    }

    // ------------------------------------------------------------------ manifest and sources

    /** Fully-qualified class names of every <activity> element in the app manifest. */
    private fun declaredActivities(): List<String> {
        val manifest = File(moduleRoot(), "src/main/AndroidManifest.xml")
        assertTrue("missing ${manifest.path}", manifest.isFile)
        val text = manifest.readText().replace("\r\n", "\n")
        return ACTIVITY_TAG.findAll(text)
            .map { it.groupValues[1] }
            .map { if (it.startsWith(".")) "com.scanwhirl.app$it" else it }
            .distinct()
            .sorted()
            .toList()
    }

    private fun sourceFile(fqcn: String): File? =
        File(moduleRoot(), "src/main/java/" + fqcn.replace('.', '/') + ".kt").takeIf { it.isFile }

    /** The Gradle unit-test working directory is the app module (or the repo root). */
    private fun moduleRoot(): File =
        listOf(File("."), File("app"), File(".."))
            .firstOrNull { File(it, "src/main/java").isDirectory }
            ?: throw AssertionError("cannot find app/src/main/java from ${File(".").absolutePath}")

    private companion object {
        /** The owner's phone: 108 px status bar, 126 px navigation bar. */
        const val STATUS_BAR = 108
        const val NAV_BAR = 126

        /** Any of these in a screen's source means its content root fits the system bars. */
        val INSET_CALLS = listOf(
            "applySystemBarsPadding",
            "setOnApplyWindowInsetsListener",
            "WindowInsetsCompat",
            "onApplyWindowInsets"
        )

        val ACTIVITY_TAG = Regex("""<activity\s+android:name="([^"]+)"""")
    }
}
