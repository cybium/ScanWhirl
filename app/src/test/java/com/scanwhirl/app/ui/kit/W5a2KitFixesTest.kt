package com.scanwhirl.app.ui.kit

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W5a-2 kit fixes. Each check names the harm it prevents: broken/detached glyphs,
 * one icon carrying two meanings, invisible chevrons on a card, a word split
 * mid-glyph at font 2.0, or a row whose columns drift at 320dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class W5a2KitFixesTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun source(relative: String): String {
        val file = java.io.File("src/main/$relative")
        assertTrue("missing source $relative", file.exists())
        return file.readText()
    }

    private fun lum(c: Int): Double {
        fun ch(shift: Int): Double {
            val v = (c shr shift and 0xFF) / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun ratio(a: Int, b: Int): Double {
        val x = lum(a); val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    /** Alpha matters: a raw colour ratio over a translucent scrim is a false green. */
    private fun composite(fg: Int, bg: Int): Int {
        val a = (fg ushr 24 and 0xFF) / 255.0
        fun ch(shift: Int) = (((fg shr shift and 0xFF) * a) +
            ((bg shr shift and 0xFF) * (1 - a))).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- 1. every redrawn glyph is one coherent stroke family -----------------

    private val redrawn = listOf(
        "ic_palette", "ic_sym_signature", "ic_sym_call_merge", "ic_sym_call_split",
        "ic_sym_approval", "ic_sym_ink_eraser", "ic_sym_visibility_off",
        "ic_sw_clear_metadata", "ic_sym_travel_explore", "ic_sym_person_off",
        "ic_sym_wifi_off", "ic_chevron_right", "ic_sym_chevron_right", "ic_sw_chev_d",
        "ic_sym_delete", "ic_delete", "ic_sym_lock", "ic_sym_lock_open", "ic_lock",
        "ic_unlock", "ic_sw_app_language", "ic_sw_ocr_language", "ic_sw_font",
        "ic_sw_text_size", "ic_sw_save_documents", "ic_sw_tag", "ic_sw_batch",
        "ic_sw_link_index", "ic_sw_pdfa", "ic_sw_backup", "ic_sw_bin_empty",
        "ic_sw_radio_off", "ic_sw_radio_on", "ic_sw_checkbox_off", "ic_sw_checkbox_on"
    )

    @Test
    fun `every redrawn icon inflates at 24dp with one stroke weight`() {
        for (name in redrawn) {
            val res = app.resources.getIdentifier(name, "drawable", app.packageName)
            assertNotEquals("$name resource missing", 0, res)
            val drawable = ContextCompat.getDrawable(app, res)
            assertNotNull("$name does not inflate", drawable)
            val dp24 = (24f * app.resources.displayMetrics.density).toInt()
            assertEquals("$name width", dp24, drawable!!.intrinsicWidth)
            assertEquals("$name height", dp24, drawable.intrinsicHeight)
            val xml = source("res/drawable/$name.xml")
            assertTrue("$name must use white ink so the host tint applies", xml.contains("#FFFFFFFF"))
            val weights = Regex("strokeWidth=\"([0-9.]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
            if (weights.isNotEmpty()) {
                assertTrue("$name must be a transparent-fill stroke glyph",
                    xml.contains("fillColor=\"#00000000\""))
                assertTrue("$name mixes stroke weights: $weights", weights.size == 1)
                assertTrue("$name stroke is not the kit 1.75/2dp weight: $weights",
                    weights == setOf("1.75") || weights == setOf("2"))
            } else {
                // Dot-only glyphs (drag handle) are a single filled set.
                assertTrue("$name must be a fill-only glyph", xml.contains("fillColor=\"#FFFFFFFF\""))
            }
        }
    }

    @Test
    fun `theme palette dots sit inside the ring instead of outside it`() {
        val xml = source("res/drawable/ic_palette.xml")
        assertTrue("palette ring is one closed stroke", xml.contains("a8.6,8.6"))
        val fill = xml.substringAfter("fillColor=\"#FFFFFFFF\"")
        val dots = Regex("M([0-9.]+),([0-9.]+)a").findAll(fill).map {
            it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }.toList()
        assertEquals("three palette dots", 3, dots.size)
        for ((x, y) in dots) {
            val distance = Math.hypot(x - 12.0, y - 12.0)
            assertTrue("dot $x,$y is outside the ring ($distance)", distance <= 7.25)
        }
    }

    // ---- 2. distinct icon per meaning ----------------------------------------

    @Test
    fun `four settings meanings have four different glyph resources`() {
        val glyphs = listOf(
            R.drawable.ic_sw_app_language,  // translate
            R.drawable.ic_sw_ocr_language,  // text-scan
            R.drawable.ic_sw_font,          // font family
            R.drawable.ic_sw_text_size      // size
        )
        assertEquals("one icon must not carry two settings meanings", 4, glyphs.toSet().size)
        for (res in glyphs) assertNotNull(ContextCompat.getDrawable(app, res))
    }

    @Test
    fun `tool meanings that collided now have distinct icons`() {
        fun icon(id: String) = checkNotNull(ToolRegistry.byId(id)).icon
        assertNotEquals("Page numbers vs Link index", icon("page_numbers"), icon("link_index"))
        assertNotEquals("ZIP vs PDF/A", icon("zip"), icon("pdfa"))
        assertNotEquals("Protect vs Unlock", icon("protect"), icon("unlock"))
        assertNotEquals("Read text vs Clear metadata", icon("read_text"), icon("remove_metadata"))
        assertNotEquals("Batch vs ID card", icon("batch_tools"), icon("id_card"))
        assertEquals("Batch owns the one stack glyph", R.drawable.ic_sw_batch, icon("batch_tools"))
        assertEquals("Clear metadata no longer uses an eye", R.drawable.ic_sw_clear_metadata, icon("remove_metadata"))
    }

    @Test
    fun `Batch uses one glyph in the registry, the Pro tile and the selection chip`() {
        // V099 acceptance: the tool hub/result fixed it, but three glyphs were still
        // visible until the Pro and selection bindings were unified too.
        val pro = source("java/com/scanwhirl/app/ui/shell/ProScreen.kt")
        val selection = source("java/com/scanwhirl/app/ui/shell/LibraryScreen.kt")
        assertTrue("Pro Batch tile must bind ic_sw_batch",
            pro.contains("R.drawable.ic_sw_batch to R.string.pro_feat_batch"))
        assertTrue("Selection Batch action must bind ic_sw_batch",
            selection.contains("R.id.swSelBatch, R.drawable.ic_sw_batch"))
        assertFalse("Pro Batch tile must not use the old check-circle glyph",
            pro.contains("R.drawable.ic_sym_check_circle to R.string.pro_feat_batch"))
        assertFalse("Selection Batch must not use the old page-grid glyph",
            selection.contains("R.id.swSelBatch, R.drawable.ic_sym_view_module"))
    }

    // ---- 3. composited contrast of UI affordances -----------------------------

    @Test
    fun `chevrons clear 3 to 1 composited on the card in both themes`() {
        assertTrue("theme still maps colorOutline to the faint line token",
            source("res/values/themes.xml").contains(
                "<item name=\"colorOutline\">@color/sf_outline</item>"
            ))
        for (q in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-$q-xhdpi")
            val ink = ContextCompat.getColor(app, R.color.sf_outline)
            val card = ContextCompat.getColor(app, R.color.tok_surface)
            val composited = composite(ink, card)
            assertTrue("$q chevron on card = ${ratio(composited, card)}",
                ratio(composited, card) >= 3.0)
        }
    }

    @Test
    fun `functional hairline clears 3 to 1 and the grab clears it on the real sheet`() {
        // V089 acceptance: the cited dark screenshot is the Android platform photo
        // picker, whose sheet is ~#2f3036 — NOT rv_surface (#162024). Compositing
        // against rv_surface made the night assertion vacuous (old and new token
        // were both #9db2b8). Test the dark token against the real dark sheet.
        val platformDarkSheet = 0xFF2F3036.toInt()
        for (q in listOf("notnight", "night")) {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-$q-xhdpi")
            val card = ContextCompat.getColor(app, R.color.tok_surface)
            val sheet = ContextCompat.getColor(app, R.color.rv_surface)
            val worstGrabSurface = if (q == "night") platformDarkSheet else sheet
            for ((name, res, bg) in listOf(
                Triple("hairline", R.color.tok_hairline_functional, card),
                Triple("grab", R.color.tok_drag_handle, worstGrabSurface)
            )) {
                val fg = ContextCompat.getColor(app, res)
                val value = ratio(composite(fg, bg), bg)
                assertTrue("$q $name on surface = $value", value >= 3.0)
            }
            if (q == "night") {
                val nightHandle = ContextCompat.getColor(app, R.color.tok_drag_handle)
                assertNotEquals("night grab token must differ from the old #9db2b8",
                    0xFF9DB2B8.toInt(), nightHandle)
                assertTrue("night grab on the real platform sheet ${ratio(composite(nightHandle, platformDarkSheet), platformDarkSheet)}",
                    ratio(composite(nightHandle, platformDarkSheet), platformDarkSheet) >= 3.0)
            }
        }
    }

    // ---- 4. no mid-word break, aligned columns --------------------------------

    @Test
    fun `kit labels allow two whole lines and ellipsize instead of splitting a word`() {
        val label = SwStyle.twoLineLabel(app, 15f, ContextCompat.getColor(app, R.color.tok_text))
        assertEquals(2, label.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, label.ellipsize)
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NONE, label.hyphenationFrequency)
        assertEquals(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE, label.breakStrategy)
        assertTrue("SwChip caps with an ellipsis",
            source("java/com/scanwhirl/app/ui/kit/SwChip.kt").contains("TruncateAt.END"))
        assertTrue("TrustPill caps with an ellipsis",
            source("java/com/scanwhirl/app/ui/kit/TrustPill.kt").contains("TruncateAt.END"))
        assertTrue("sheet option rows use the whole-word label",
            source("java/com/scanwhirl/app/ui/kit/SwSheet.kt").contains("SwStyle.twoLineLabel"))
    }

    @Test
    fun `kit list row aligns icon text and chevron at 320dp and font 2`() {
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(2f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val row = SwSettingRow(activity).apply {
            bind(R.drawable.ic_sw_app_language, "App language", "Choose the language")
        }
        val width = activity.resources.displayMetrics.widthPixels
        repeat(2) {
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        val pad = row.paddingLeft
        val gap = SwStyle.dp(activity, SwStyle.SPACE_3_DP)
        val icon = Rect(); val title = Rect(); val chevron = Rect()
        row.iconView.getGlobalVisibleRect(icon)
        row.titleView.getGlobalVisibleRect(title)
        row.chevronView.getGlobalVisibleRect(chevron)
        assertEquals("icon starts at the card padding", pad, icon.left)
        assertEquals("text starts one token gap after the icon", icon.right + gap, title.left)
        assertEquals("chevron ends at the matching right padding",
            row.width - row.paddingRight, chevron.right)
        assertEquals("chevron is vertically centred with the icon",
            icon.centerY().toDouble(), chevron.centerY().toDouble(), 1.0)
        assertTrue("title never hyphenates", row.titleView.hyphenationFrequency ==
            android.text.Layout.HYPHENATION_FREQUENCY_NONE)
        assertTrue("row fits 320dp", row.width <= width)
        assertEquals("chevron uses the functional affordance colour",
            ContextCompat.getColor(activity, R.color.sf_outline),
            (row.chevronView.imageTintList ?: error("chevron tint missing")).defaultColor)
    }
}
