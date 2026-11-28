package com.scanwhirl.app.ui.kit

import android.app.Application
import android.os.Looper
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
        val scrim = ContextCompat.getColor(app, R.color.tok_scrim)
        val onScrim = ContextCompat.getColor(app, R.color.tok_on_scrim)
        assertTrue("page scrim label ${ratio(onScrim, scrim)}", ratio(onScrim, scrim) >= 4.5)
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
            val offTrack = track.getColorForState(intArrayOf(), 0)
            val offThumb = thumb.getColorForState(intArrayOf(), 0)
            val onTrack = track.getColorForState(intArrayOf(android.R.attr.state_checked), 0)
            val onThumb = thumb.getColorForState(intArrayOf(android.R.attr.state_checked), 0)
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
    fun `Me exposes system light and dark and remembers the choice`() {
        ActivityScenario.launch(ShellActivity::class.java).use { shell ->
            openMe(shell)
            shell.onActivity { activity ->
                val row = activity.findViewById<View>(R.id.swMeTheme)
                assertNotNull("Me has a Theme row", row)
                assertEquals("Follow system",
                    activity.findViewById<TextView>(R.id.swMeThemeValue)!!.text.toString())
                row!!.performClick()
            }
            idle()
            shell.onActivity { activity ->
                val items = ShadowDialog.getLatestDialog()?.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("the theme sheet opens", items)
                // Option rows carry their label as contentDescription (SwSheet.setOptions).
                val labels = (0 until items!!.childCount).map { items.getChildAt(it).contentDescription.toString() }
                assertTrue("the three choices are offered: $labels",
                    labels.containsAll(listOf("Follow system", "Light", "Dark")))
                items.getChildAt(1).performClick()
            }
            idle()
        }
        assertEquals("Light was chosen in Me", Prefs.THEME_LIGHT, Prefs.themeMode(app))
        ActivityScenario.launch(SettingsActivity::class.java).use { settings ->
            settings.onActivity { activity ->
                assertEquals("Settings names the Me choice",
                    "Light", activity.findViewById<TextView>(R.id.subTheme)!!.text.toString())
            }
        }
    }
}
