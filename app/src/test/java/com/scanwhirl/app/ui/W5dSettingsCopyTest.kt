package com.scanwhirl.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
 * W5d harm checks for Settings and Me. Each name says what a person would get wrong
 * if the check were deleted: a duplicate setting, two meanings for one glyph, two names
 * for one preference, misaligned rows at 320dp/2.0, or sheets that do not preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class W5dSettingsCopyTest {

    private lateinit var app: Application
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(activity: SettingsActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun visibleTexts(activity: SettingsActivity): List<String> {
        fun collect(v: View, out: MutableList<String>) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
        }
        val out = mutableListOf<String>()
        collect(activity.window.decorView, out)
        return out
    }

    /** Same pixel comparison the kit rows use: tint both icons, then compare the render. */
    private fun iconBitmap(iv: ImageView?, resId: Int): Bitmap? {
        val actual = iv?.drawable ?: return null
        val expected = ContextCompat.getDrawable(context, resId) ?: return null
        val w = actual.intrinsicWidth.coerceAtLeast(1)
        val h = actual.intrinsicHeight.coerceAtLeast(1)
        val tint = ContextCompat.getColor(context, R.color.cyan_primary)
        DrawableCompat.setTint(actual.mutate(), tint)
        DrawableCompat.setTint(expected.mutate(), tint)
        fun render(d: android.graphics.drawable.Drawable): Bitmap {
            d.setBounds(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(bmp))
            return bmp
        }
        return render(expected)
    }

    @Test(timeout = 60_000)
    fun noDuplicateSettingTitlesInSettings() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val texts = visibleTexts(activity)
                val font = activity.getString(R.string.settings_font)
                val size = activity.getString(R.string.cz_text_size_title)
                assertTrue("the font row is named Font: $texts", font == "Font" && texts.count { it == font } == 1)
                assertTrue("exactly one Text size row remains: $texts", texts.count { it == size } == 1)
                assertTrue("Font and Text size are different words: $font / $size", font != size)
                // Both live in the same Appearance card.
                assertNotNull(activity.findViewById<View>(R.id.rowFont))
                assertNotNull(activity.findViewById<View>(R.id.rowTextSize))
                assertTrue(
                    "Theme, Font and Text size share one Appearance card",
                    activity.findViewById<View>(R.id.rowTheme).parent ===
                        activity.findViewById<View>(R.id.rowFont).parent &&
                        activity.findViewById<View>(R.id.rowFont).parent ===
                        activity.findViewById<View>(R.id.rowTextSize).parent
                )
            }
        }
    }

    @Test(timeout = 60_000)
    fun appAndOcrLanguageRowsUseDifferentIcons() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val appRow = activity.findViewById<ViewGroup>(R.id.rowLanguage)
                val ocrRow = activity.findViewById<ViewGroup>(R.id.rowOcrLanguage)
                assertNotNull("App language row exists", appRow)
                assertNotNull("OCR language row exists", ocrRow)
                val appIcon = appRow.getChildAt(0) as? ImageView
                val ocrIcon = ocrRow.getChildAt(0) as? ImageView
                assertNotNull("App language leads with an icon", appIcon)
                assertNotNull("OCR language leads with an icon", ocrIcon)
                val enGlobe = iconBitmap(appIcon, R.drawable.ic_sw_app_language)
                val enOcr = iconBitmap(ocrIcon, R.drawable.ic_sw_ocr_language)
                assertNotNull("globe fixture renders", enGlobe)
                assertNotNull("OCR fixture renders", enOcr)
                assertTrue("the two glyphs are different", !enGlobe!!.sameAs(enOcr!!))
                assertTrue("App language keeps the globe", enGlobe.sameAs(renderIcon(appIcon!!)))
                assertTrue("OCR language uses its own glyph", enOcr.sameAs(renderIcon(ocrIcon!!)))
            }
        }
    }

    private fun renderIcon(iv: ImageView): Bitmap {
        val d = iv.drawable.mutate()
        DrawableCompat.setTint(d, ContextCompat.getColor(context, R.color.cyan_primary))
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        d.setBounds(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bmp))
        return bmp
    }

    @Test(timeout = 60_000)
    fun oneDismissalPerSheet() {
        // D5: a header X plus a footer Cancel (or "Got it") is two controls for one
        // dismissal. Cancel sheets keep only Cancel; an informational About sheet keeps
        // only its named close.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowTheme).performClick()
            }
            idle()
            scenario.onActivity {
                val dialog = ShadowDialog.getLatestDialog()!!
                assertFalse("theme sheet: Cancel is the one dismissal",
                    dialog.findViewById<View>(R.id.swSheetClose).isVisible)
                assertTrue(dialog.findViewById<View>(R.id.swSheetSecondary).isVisible)
            }
        }
        ActivityScenario.launch(com.scanwhirl.app.ui.shell.ShellActivity::class.java).use { shell ->
            shell.onActivity { activity ->
                activity.findViewById<View>(com.scanwhirl.app.R.id.swNavMe).performClick()
                idle()
                activity.findViewById<View>(com.scanwhirl.app.R.id.swMeAbout).performClick()
            }
            idle()
            shell.onActivity {
                val dialog = ShadowDialog.getLatestDialog()!!
                assertTrue("About keeps its header close",
                    dialog.findViewById<View>(R.id.swSheetClose).isVisible)
                assertFalse("About has no duplicate footer dismissal",
                    dialog.findViewById<View>(R.id.swSheetPrimary).isVisible)
            }
        }
    }

    @Test(timeout = 60_000)
    fun fontAndTextSizeRowsUseDifferentGlyphs() {
        // D6: "TT" on both rows read as one icon for two meanings. The W5a-2 kit
        // ships a font glyph and a size glyph; they must render differently.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val font = iconBitmap(
                    activity.findViewById<ViewGroup>(R.id.rowFont).getChildAt(0) as ImageView,
                    R.drawable.ic_sw_font
                )
                val size = iconBitmap(
                    activity.findViewById<ViewGroup>(R.id.rowTextSize).getChildAt(0) as ImageView,
                    R.drawable.ic_sw_text_size
                )
                assertNotNull("font glyph renders", font)
                assertNotNull("text-size glyph renders", size)
                assertTrue("Font and Text size glyphs differ", !font!!.sameAs(size!!))
            }
        }
    }

    @Test(timeout = 60_000)
    fun oneNameForTheAutoSavePreference() {
        val en = context
        val hi = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale("hi")) }
        )
        assertEquals("Settings and Me use one preference name (en)",
            en.getString(R.string.settings_auto_copy_to_documents),
            en.getString(R.string.me_also_save_to_documents))
        assertEquals("Settings and Me use one preference name (hi)",
            hi.getString(R.string.settings_auto_copy_to_documents),
            hi.getString(R.string.me_also_save_to_documents))
        assertEquals("one where-line (en)",
            en.getString(R.string.settings_auto_copy_to_documents_summary),
            en.getString(R.string.me_also_save_to_documents_summary))
        assertEquals("one where-line (hi)",
            hi.getString(R.string.settings_auto_copy_to_documents_summary),
            hi.getString(R.string.me_also_save_to_documents_summary))
        assertEquals("the name is the plain action", "Also save to Documents",
            en.getString(R.string.settings_auto_copy_to_documents))
        assertTrue("the where-line names the folder",
            en.getString(R.string.settings_auto_copy_to_documents_summary)
                .contains("Documents/ScanWhirl"))
    }

    @Test(timeout = 60_000)
    fun rowsShareIconAndTextXAt320DpFont200() {
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(2f)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                layout(activity)
                val appRow = activity.findViewById<ViewGroup>(R.id.rowLanguage)
                val ocrRow = activity.findViewById<ViewGroup>(R.id.rowOcrLanguage)
                val appIcon = appRow.getChildAt(0)
                val ocrIcon = ocrRow.getChildAt(0)
                val appText = appRow.getChildAt(1)
                val ocrText = ocrRow.getChildAt(1)
                assertEquals("icon x at 320dp/2.0", appIcon.left, ocrIcon.left)
                assertEquals("icon right edge at 320dp/2.0", appIcon.right, ocrIcon.right)
                assertEquals("label x at 320dp/2.0", appText.left, ocrText.left)
                assertTrue("both rows are full 48dp targets",
                    appRow.height >= Math.round(48f * activity.resources.displayMetrics.density) &&
                        ocrRow.height >= Math.round(48f * activity.resources.displayMetrics.density))
            }
        }
    }

    @Test(timeout = 60_000)
    fun fontSheetPreviewUsesThreeFacesAndTheRightTitle() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowFont).performClick()
            }
            idle()
            scenario.onActivity { activity ->
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("the Font sheet opens", dialog)
                assertEquals("Font", dialog!!.findViewById<TextView>(com.scanwhirl.app.R.id.swSheetTitle)?.text?.toString()
                    ?: activity.getString(R.string.settings_font))
                val items = dialog.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("the sheet lists font choices", items)
                val faces = (0 until items!!.childCount).map {
                    (items.getChildAt(it) as ViewGroup).getChildAt(0) as TextView
                }.map { it.typeface }
                assertEquals(3, faces.size)
                assertNotSame("Serif is a real preview", faces[0], faces[1])
                assertNotSame("Monospace is a real preview", faces[1], faces[2])
                assertEquals(Typeface.SERIF, faces[1])
                assertEquals(Typeface.MONOSPACE, faces[2])
            }
        }
    }

    @Test(timeout = 60_000)
    fun textSizeSheetPreviewScalesItsLabels() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.rowTextSize).performClick()
            }
            idle()
            scenario.onActivity {
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("the Text size sheet opens", dialog)
                val items = dialog!!.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("the sheet lists scale choices", items)
                val sizes = (0 until items!!.childCount).map {
                    ((items.getChildAt(it) as ViewGroup).getChildAt(0) as TextView).textSize
                }
                assertEquals(4, sizes.size)
                assertTrue("Small is visibly smaller than Largest: $sizes", sizes[0] < sizes[3])
                assertTrue("the four scales are distinct: $sizes", sizes.toSet().size == 4)
            }
        }
    }
}
