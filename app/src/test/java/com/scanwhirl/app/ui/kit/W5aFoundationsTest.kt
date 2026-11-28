package com.scanwhirl.app.ui.kit

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * W5a foundations: the harm each check prevents is a screen painted with a dark-only
 * literal after the person chose Light, a surprise dark startup, two independent
 * "save to Documents" switches, or new screens drifting off the one scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class W5aFoundationsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(1.0f)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 6_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    // ---- 1. one palette, resolved twice -----------------------------------

    @Test
    fun `light qualifier resolves the light tokens and the legacy aliases`() {
        // Android qualifier order puts uiMode (notnight) before density (xhdpi).
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-notnight-xhdpi")
        val surface = ContextCompat.getColor(app, R.color.tok_surface)
        val text = ContextCompat.getColor(app, R.color.tok_text)
        assertEquals("light surface is the paired white", 0xFFFFFFFF.toInt(), surface)
        assertEquals("light ink is tokens.css --text", 0xFF13252A.toInt(), text)
        assertEquals("legacy card name follows the token", surface,
            ContextCompat.getColor(app, R.color.glass_surface))
        assertEquals("legacy high ink follows the token", text,
            ContextCompat.getColor(app, R.color.text_hologram_hi))
        assertEquals("legacy danger follows --danger", ContextCompat.getColor(app, R.color.tok_danger),
            ContextCompat.getColor(app, R.color.laser_danger))
        assertEquals("kit paint follows the token", surface,
            SwStyle.color(app, R.color.rv_surface))
    }

    @Test
    fun `night qualifier resolves the shipped dark tokens and the legacy aliases`() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-night-xhdpi")
        val surface = ContextCompat.getColor(app, R.color.tok_surface)
        val text = ContextCompat.getColor(app, R.color.tok_text)
        assertEquals("dark surface is tokens.css --surface", 0xFF162024.toInt(), surface)
        assertEquals("dark ink is tokens.css --text", 0xFFE8F1F3.toInt(), text)
        assertEquals("legacy card name follows the token", surface,
            ContextCompat.getColor(app, R.color.glass_surface))
        assertEquals("legacy high ink follows the token", text,
            ContextCompat.getColor(app, R.color.text_hologram_hi))
        assertEquals("system bar icons flip with the qualifier", false,
            app.resources.getBoolean(R.bool.sw_light_system_bars))
    }

    @Test
    fun `gold stays behind the verified flag in both themes`() {
        Prefs.setAccent(app, Prefs.ACCENT_GOLD)
        com.scanwhirl.app.util.ProGate.setPro(app, true)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        assertEquals("no gold kit accent while GOLD_ENABLED is false",
            ContextCompat.getColor(app, R.color.tok_accent),
            SwStyle.color(app, R.color.cyan_primary))
    }

    // ---- 2. follow-system default -----------------------------------------

    @Test
    fun `theme defaults to follow system and keeps an explicit choice`() {
        assertEquals("a fresh install follows the system", Prefs.THEME_SYSTEM, Prefs.themeMode(app))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            Prefs.nightMode(Prefs.themeMode(app)))
        Prefs.setThemeMode(app, Prefs.THEME_LIGHT)
        assertEquals(Prefs.THEME_LIGHT, Prefs.themeMode(app))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, Prefs.nightMode(Prefs.themeMode(app)))
        Prefs.setThemeMode(app, Prefs.THEME_DARK)
        assertEquals(Prefs.THEME_DARK, Prefs.themeMode(app))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, Prefs.nightMode(Prefs.themeMode(app)))
    }

    // ---- 3. the one scale --------------------------------------------------

    @Test
    fun `type space shape touch and motion come from the one token set`() {
        val r = app.resources
        assertEquals(20f, SwType.TITLE_SP, 0f)
        assertEquals(17f, SwType.HEADING_SP, 0f)
        assertEquals(15f, SwType.BODY_SP, 0f)
        assertEquals(13f, SwType.SMALL_SP, 0f)
        assertEquals(12f, SwType.CAPTION_SP, 0f)
        assertEquals(20f, r.getDimension(R.dimen.sw_text_title) / r.displayMetrics.scaledDensity, 0.05f)
        assertEquals(16f, SwStyle.RADIUS_CARD_DP, 0f)
        assertEquals(12f, SwStyle.RADIUS_CONTROL_DP, 0f)
        assertEquals(24f, SwStyle.RADIUS_SHEET_DP, 0f)
        assertEquals(4f, SwStyle.SPACE_1_DP, 0f)
        assertEquals(24f, SwStyle.SPACE_6_DP, 0f)
        assertEquals(48f, SwStyle.TOUCH_MIN_DP, 0f)
        assertEquals(100L, SwMotion.SHORT)
        assertEquals(150L, SwMotion.MEDIUM)
        assertEquals(200L, SwMotion.SETTLE)
        assertEquals(300L, SwMotion.LONG)
        assertNotNull("standard easing exists", SwMotion.standard())
        assertNotNull("emphasized easing exists", SwMotion.emphasized())
        assertNotNull("sheet exit easing exists", SwMotion.emphasizedExit())
    }

    // ---- 3b. review fixes: no light-theme dark-on-dark --------------------

    @Test
    fun `category painters use themed tokens in both qualifiers`() {
        val cats = listOf(
            com.scanwhirl.app.util.CategoryRules.STUDY to R.color.cat_study,
            com.scanwhirl.app.util.CategoryRules.RECEIPTS to R.color.cat_receipts,
            com.scanwhirl.app.util.CategoryRules.IDS to R.color.cat_ids,
            com.scanwhirl.app.util.CategoryRules.FORMS to R.color.cat_forms,
            com.scanwhirl.app.util.CategoryRules.OTHER to R.color.cat_other
        )
        for (q in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-$q-xhdpi")
            for ((cat, res) in cats) {
                assertEquals("$cat painter follows $q token",
                    ContextCompat.getColor(app, res),
                    com.scanwhirl.app.util.CategoryStyle.color(cat, app))
            }
        }
    }

    @Test
    fun `all functional light surfaces clear AA for their ink`() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-notnight-xhdpi")
        fun lum(c: Int): Double {
            fun ch(shift: Int): Double {
                val v = (c shr shift and 0xFF) / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        fun ratio(a: Int, b: Int): Double {
            val x = lum(a); val y = lum(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        val surface2 = ContextCompat.getColor(app, R.color.tok_surface_2)
        val ink = ContextCompat.getColor(app, R.color.tok_text)
        assertTrue("snackbar/picker ink on surface-2 ${ratio(ink, surface2)}",
            ratio(ink, surface2) >= 4.5)
        // Alpha matters: a raw ratio against a translucent scrim ignored what
        // shows through. Composite EVERY scrim pair over every page background
        // (white, paper, dark scan) in both themes. White ink uses the deep scrim;
        // page-count / film-number / review-step badges use an opaque fill with
        // rv_ink, which is why raising the scrim alpha alone regressed them.
        fun composite(fg: Int, bg: Int): Int {
            val a = (fg ushr 24 and 0xFF) / 255.0
            fun ch(shift: Int) = (((fg shr shift and 0xFF) * a) +
                ((bg shr shift and 0xFF) * (1 - a))).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        val pages = listOf(
            "white" to 0xFFFFFF,
            "paper" to 0xFFE9E8E3.toInt(),
            "dark scan" to 0xFF162024.toInt()
        )
        val scrimPairs = listOf(
            "white ink on scrim" to (R.color.tok_on_scrim to R.color.tok_scrim),
            "badge ink on badge fill" to (R.color.rv_ink to R.color.rv_scrim_badge),
            "opaque page label" to (R.color.tok_on_scrim to R.color.tok_preview_backdrop)
        )
        for (q in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-$q-xhdpi")
            for ((name, pair) in scrimPairs) {
                val ink = ContextCompat.getColor(app, pair.first)
                val fill = ContextCompat.getColor(app, pair.second)
                for ((pageName, page) in pages) {
                    val ratioOnPage = ratio(ink, composite(fill, page))
                    assertTrue("$q $name over $pageName = $ratioOnPage", ratioOnPage >= 4.5)
                }
            }
        }
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-notnight-xhdpi")
        val text3 = ContextCompat.getColor(app, R.color.tok_text_3)
        for (bg in listOf(R.color.tok_surface, R.color.tok_surface_2,
            R.color.tok_surface_3, R.color.tok_canvas)) {
            assertTrue("text-3 on ${app.resources.getResourceEntryName(bg)} ${ratio(text3, ContextCompat.getColor(app, bg))}",
                ratio(text3, ContextCompat.getColor(app, bg)) >= 4.5)
        }
    }

    @Test
    fun `switch off track and thumb clear 3 to 1 on the card in both themes`() {
        fun lum(c: Int): Double {
            fun ch(shift: Int): Double {
                val v = (c shr shift and 0xFF) / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }
        fun ratio(a: Int, b: Int): Double {
            val x = lum(a); val y = lum(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        for (q in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-$q-xhdpi")
            val track = ContextCompat.getColorStateList(app, R.color.sel_switch_track)!!
            val thumb = ContextCompat.getColorStateList(app, R.color.sel_switch_thumb)!!
            // An empty state set means "disabled" to ColorStateList, so always
            // include state_enabled when asking for the normal OFF/ON colours.
            val enabled = intArrayOf(android.R.attr.state_enabled)
            val checked = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
            val offTrack = track.getColorForState(enabled, 0)
            val offThumb = thumb.getColorForState(enabled, 0)
            val onTrack = track.getColorForState(checked, 0)
            val onThumb = thumb.getColorForState(checked, 0)
            val offTrackDisabled = track.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0)
            val offThumbDisabled = thumb.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0)
            val card = ContextCompat.getColor(app, R.color.tok_surface)
            assertTrue("$q OFF track on card ${ratio(offTrack, card)}", ratio(offTrack, card) >= 3.0)
            assertTrue("$q OFF thumb on track ${ratio(offThumb, offTrack)}", ratio(offThumb, offTrack) >= 3.0)
            assertTrue("$q ON thumb on accent ${ratio(onThumb, onTrack)}", ratio(onThumb, onTrack) >= 3.0)
            assertTrue("$q disabled is distinguishable",
                offTrackDisabled != offTrack && offThumbDisabled != offThumb)
        }
    }

    // ---- 3c. API-qualified nav-bar icons ----------------------------------

    private fun themedNavigationBarColor(): Int {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val tv = TypedValue()
        assertTrue("navigationBarColor is set by the theme",
            activity.theme.resolveAttribute(android.R.attr.navigationBarColor, tv, true))
        return ContextCompat.getColor(activity, tv.resourceId)
    }

    @Test(timeout = 30_000)
    @Config(sdk = [24, 26])
    fun `API 24 to 26 keeps a dark nav bar for white system icons`() {
        assertEquals("dark nav bar on SDK ${RuntimeEnvironment.getApiLevel()}",
            ContextCompat.getColor(app, R.color.tok_nav_bar_dark), themedNavigationBarColor())
    }

    @Test(timeout = 30_000)
    @Config(sdk = [27, 35])
    fun `API 27 plus uses the themed nav bar with light icons`() {
        assertEquals("themed nav bar on SDK ${RuntimeEnvironment.getApiLevel()}",
            ContextCompat.getColor(app, R.color.tok_bg), themedNavigationBarColor())
    }

    @Test(timeout = 60_000)
    fun `switches are not clipped at 320dp and font 2 in Me or Settings`() {
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(2f)
        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                openMe(scenario)
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val dm = activity.resources.displayMetrics
                    repeat(3) {
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                        )
                        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    }
                    val toggle = activity.findViewById<SwitchCompat>(R.id.swMeAlsoSaveSwitch)
                    val card = toggle.parent as ViewGroup
                    val tb = Rect(); val cb = Rect()
                    toggle.getGlobalVisibleRect(tb); card.getGlobalVisibleRect(cb)
                    assertTrue("Me switch whole (track left) $tb vs $cb",
                        tb.left >= cb.left + card.paddingLeft - 1)
                    assertTrue("Me switch whole (track right) $tb vs $cb",
                        tb.right <= cb.right - card.paddingRight + 1)
                }
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val dm = activity.resources.displayMetrics
                    repeat(3) {
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                        )
                        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    }
                    val toggle = activity.findViewById<SwitchCompat>(R.id.switchAutoCopyToDocuments)
                    val card = toggle.parent as ViewGroup
                    val tb = Rect(); val cb = Rect()
                    toggle.getGlobalVisibleRect(tb); card.getGlobalVisibleRect(cb)
                    assertTrue("Settings switch whole (track left) $tb vs $cb",
                        tb.left >= cb.left + card.paddingLeft - 1)
                    assertTrue("Settings switch whole (track right) $tb vs $cb",
                        tb.right <= cb.right - card.paddingRight + 1)
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        }
    }

    // ---- 4. one "save to Documents" preference ----------------------------

    private fun openMe(scenario: ActivityScenario<ShellActivity>) {
        poll {
            scenario.onActivity { activity ->
                if (activity.findViewById<View>(R.id.swMeTrash) == null) {
                    activity.findViewById<View>(R.id.swNavMe)?.performClick()
                }
            }
            var found = false
            scenario.onActivity { found = it.findViewById<View>(R.id.swMeAlsoSaveSwitch) != null }
            found
        }
    }

    @Test(timeout = 60_000)
    fun `the Me toggle and the Settings switch are the same preference`() {
        ActivityScenario.launch(ShellActivity::class.java).use { shell ->
            openMe(shell)
            shell.onActivity { activity ->
                val toggle = activity.findViewById<SwitchCompat>(R.id.swMeAlsoSaveSwitch)
                assertNotNull("Me offers the Documents toggle", toggle)
                assertEquals("off by default", false, toggle!!.isChecked)
                toggle.performClick()
            }
            idle()
            assertEquals("the Me toggle writes the shared pref", true, Prefs.autoCopyToDocuments(app))
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            settings.onActivity { activity ->
                val sw = activity.findViewById<SwitchCompat>(R.id.switchAutoCopyToDocuments)
                assertNotNull("the Settings switch is still present", sw)
                assertEquals("Settings reads the Me choice", true, sw!!.isChecked)
                sw.performClick()
            }
            idle()
            assertEquals("the Settings switch writes the shared pref", false, Prefs.autoCopyToDocuments(app))
        }
        ActivityScenario.launch(ShellActivity::class.java).use { shell ->
            openMe(shell)
            shell.onActivity { activity ->
                assertEquals("Me reads the Settings choice", false,
                    activity.findViewById<SwitchCompat>(R.id.swMeAlsoSaveSwitch)!!.isChecked)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `Me names where the extra copy goes`() {
        ActivityScenario.launch(ShellActivity::class.java).use { shell ->
            openMe(shell)
            shell.onActivity { activity ->
                val row = activity.findViewById<ViewGroup>(R.id.swMeAlsoSave)
                assertNotNull("the row exists", row)
                val words = mutableListOf<String>()
                fun walk(v: View) {
                    if (v is TextView && v.text?.isNotBlank() == true) words += v.text.toString()
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(row!!)
                assertTrue("the row says what it toggles: $words",
                    words.contains("Also save to Documents"))
                assertTrue("the row says where files go: $words",
                    words.any { it.contains("Documents/ScanWhirl") })
            }
        }
    }

    @Test(timeout = 60_000)
    fun `Theme lives once in Settings and remembers the choice`() {
        // W5d V097: one appearance home. Me is a short hub and must not repeat the control.
        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            settings.onActivity { activity ->
                val row = activity.findViewById<View>(R.id.rowTheme)
                assertNotNull("Settings has a Theme row", row)
                assertEquals("Follow system",
                    activity.findViewById<TextView>(R.id.subTheme)!!.text.toString())
                row!!.performClick()
            }
            idle()
            settings.onActivity {
                val items = ShadowDialog.getLatestDialog()?.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("the theme sheet opens", items)
                val labels = (0 until items!!.childCount).map { items.getChildAt(it).contentDescription.toString() }
                assertTrue("the three choices are offered: $labels",
                    labels.containsAll(listOf("Follow system", "Light", "Dark")))
                items.getChildAt(1).performClick()
            }
            idle()
        }
        assertEquals("Light was chosen in Settings", Prefs.THEME_LIGHT, Prefs.themeMode(app))
        ActivityScenario.launch(ShellActivity::class.java).use { shell ->
            openMe(shell)
            shell.onActivity { activity ->
                assertNull("Me does not duplicate the Theme row",
                    activity.findViewById<View>(R.id.swMeTheme))
            }
        }
    }
}
