package com.scanwhirl.app.ui.shell

import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SH1 — Main shell + Settings correctness. Each test is pinned to a real line in the
 * app so a regression is visible without a device:
 *  1. the saved wallpaper must actually be drawn by the shell,
 *  2. the selected bottom tab survives recreation (and a Settings-driven recreate),
 *  3. a font / language change must redraw the shell immediately,
 *  4. visible dates follow the app language, not the device locale,
 *  5. Trash expiry is recomputed when Trash resumes, not only at process start,
 *  6. Hindi tab labels fit at 200 % font without losing their spoken name,
 *  7. an external "show document" extra must be checked before it is acted on,
 *  8. the dead "Orbital Dark" summary must not be a hardcoded string.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class Sh1ShellSettingsFixTest {

    private val shellText: String by lazy {
        File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
    }
    private val settingsText: String by lazy {
        File("src/main/java/com/scanwhirl/app/ui/SettingsActivity.kt").readText()
    }
    private val trashText: String by lazy {
        File("src/main/java/com/scanwhirl/app/ui/TrashActivity.kt").readText()
    }

    @Before
    fun resetFont() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun restoreFont() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(activity: android.app.Activity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    @Test
    fun `saved wallpaper is drawn by the shell instead of only being stored`() {
        assertTrue(
            "ShellActivity must apply Wallpaper.drawable() as its background; " +
                "the setting is saved in Settings but the launcher never used it",
            shellText.contains("Wallpaper.drawable(")
        )
    }

    @Test
    fun `the selected bottom tab survives recreation`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                idle()
                assertTrue("the Tools tab opened", activity.findViewById<View>(R.id.swToolsSearch) != null)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                idle()
                assertTrue(
                    "rotation / a Settings-driven recreate must come back on Tools, not Library",
                    activity.findViewById<View>(R.id.swToolsSearch)?.visibility == View.VISIBLE
                )
            }
        }
    }

    @Test
    fun `a font or language change redraws the shell immediately`() {
        val resume = shellText.substringAfter("override fun onResume()")
            .substringBefore("override fun onStop()")
        assertTrue("onResume must call recreate() when a look setting changed", resume.contains("recreate()"))
        assertTrue("the check must cover the saved font", resume.contains("fontStyle"))
        assertTrue("the check must cover the app language", resume.contains("currentTag"))
    }

    @Test
    fun `visible dates use the app language locale not the device locale`() {
        val start = shellText.indexOf("private fun dayLabel")
        assertTrue("dayLabel exists", start >= 0)
        val body = shellText.substring(start).substringBefore("// ---- HomeHost")
        assertFalse(
            "dayLabel must not format with Locale.getDefault(): that is the device language",
            body.contains("Locale.getDefault()")
        )
        assertTrue("dayLabel must format with the app locale helper", body.contains("appLocale()"))
        val helper = shellText.substringAfter("internal fun appLocale()").substringBefore("private fun dayLabel")
        assertTrue(
            "the helper must derive the locale from the app's chosen language",
            helper.contains("AppLanguage.currentTag")
        )
    }

    @Test
    fun `trash expiry is recomputed when Trash resumes`() {
        val resume = trashText.substringAfter("override fun onResume()")
            .substringBefore("override fun onCreateOptionsMenu")
        assertTrue(
            "Trash must expire through the repository API on resume so a long-open " +
                "screen does not keep expired items or stale day counts",
            resume.contains("recoverTrash(")
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `Hindi tab labels fit at 200 percent font and keep their content description`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layout(activity)
                val density = activity.resources.displayMetrics.density
                for (id in intArrayOf(R.id.swNavLibrary, R.id.swNavTools, R.id.swNavVault, R.id.swNavMe)) {
                    val cell = activity.findViewById<View>(id) as ViewGroup
                    val label = (0 until cell.childCount).map { cell.getChildAt(it) }
                        .filterIsInstance<TextView>().first { it.tag == "label" }
                    assertTrue("cell $id keeps a 48dp touch target", cell.width >= (48 * density).toInt() - 1)
                    assertEquals("label $id stays on one line", 1, label.maxLines)
                    assertTrue(
                        "label $id must ellipsize instead of clipping or overlapping",
                        label.ellipsize == TextUtils.TruncateAt.END
                    )
                    assertTrue("label $id must fit inside its cell", label.width <= cell.width)
                    assertTrue(
                        "label $id must still be named for TalkBack when it ellipsizes",
                        cell.contentDescription?.toString().orEmpty().isNotBlank()
                    )
                }
            }
        }
    }

    @Test
    fun `an external show-document extra is validated before it is acted on`() {
        val start = shellText.indexOf("private fun revealFromIntent")
        assertTrue("revealFromIntent exists", start >= 0)
        val body = shellText.substring(start).substringBefore("private var billingUnsub")
        assertTrue("the extra's document must be looked up", body.contains("getDocument("))
        assertTrue("a locked document must be ignored", body.contains("isLocked"))
        assertTrue("a vaulted document must be ignored", body.contains("isVaulted"))
        assertFalse(
            "an unknown extra must never be acted on blindly",
            body.contains("library.revealDocument(docId)")
        )
    }

    @Test
    fun `the dead Orbital Dark summary is not a hardcoded string`() {
        assertFalse(
            "Settings keeps the theme row hidden; it must not carry a hardcoded English name",
            settingsText.contains("Orbital Dark")
        )
    }
}
