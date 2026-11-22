package com.scanwhirl.app.sh2

import android.app.Application
import android.os.Looper
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.ui.DocumentViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * SH2 rows from COPY_DIET §2 (sh2 rows), §1 roles and §3 owned bindings.
 * The first group is a static resource check for removed/stale strings; the second
 * measures the real Compress sheet's reading hierarchy in EN and HI.
 */
class Sh2CopyDietResourceTest {

    private fun xml(locale: String, name: String): String {
        // "en" is the base values folder; Hindi only from values-hi (never the base).
        val dir = if (locale == "en") "src/main/res/values" else "src/main/res/values-$locale"
        val f = File(dir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    private fun value(locale: String, key: String): String? {
        val all = listOf("strings.xml", "strings_cb.xml", "strings_u4.xml", "strings_protect.xml")
            .map { xml(locale, it) }.joinToString("\n")
        val regex = Regex("""<string name="$key"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(all)?.groupValues?.get(1)
    }

    @Test
    fun `stale quick editor instruction string is deleted in both locales`() {
        for (locale in listOf("en", "hi")) {
            assertFalse(
                "u4_quick_editor_hint must be gone from values-$locale",
                listOf("strings.xml", "strings_u4.xml").any {
                    File("src/main/res/values-$locale", it).exists() &&
                        File("src/main/res/values-$locale", it).readText(Charsets.UTF_8)
                            .contains("u4_quick_editor_hint")
                }
            )
        }
    }

    @Test
    fun `sh2 copy diet rows are applied with EN HI parity`() {
        val expected = mapOf(
            "cb_quick_editor_title" to ("Quick tools" to "झटपट काम"),
            "cb_quick_max_reached" to ("Choose up to 6 tools" to "अधिकतम 6 काम चुनें"),
            "u4_quick_reset" to ("Reset" to "पहले जैसा करें"),
            "compress_level_title" to ("Compress" to "फ़ाइल छोटी करें"),
            "compress_mode_quality" to ("Quality" to "गुणवत्ता"),
            "compress_mode_target" to ("Maximum file size" to "फ़ाइल का अधिकतम आकार"),
            "compress_target_label" to ("Maximum file size" to "फ़ाइल का अधिकतम आकार"),
            "compress_target_required" to ("Enter a maximum size" to "अधिकतम आकार लिखें"),
            "compress_target_placeholder" to ("100 KB" to "100 KB"),
            "protect_sheet_note" to (
                "A password is needed to open this PDF" to "यह PDF खोलने के लिए पासवर्ड चाहिए"
                ),
            "protect_aes_note" to (
                "Use a strong password: 10+ characters" to "मज़बूत पासवर्ड रखें: 10 या अधिक अक्षर"
                )
        )
        for ((key, pair) in expected) {
            assertEquals("EN $key", pair.first, value("en", key))
            assertEquals("HI $key", pair.second, value("hi", key))
        }
        assertFalse("no rejected साइज़ loanword", (value("hi", "compress_mode_target") ?: "").contains("साइज़"))
    }

    @Test
    fun `old copy is gone and the precise password error stays`() {
        val old = mapOf(
            "cb_quick_editor_title" to "Customize quick tools",
            "cb_quick_editor_hint" to "Tap to add or remove",
            "cb_quick_save" to "Done",
            "compress_mode_quality" to "Choose quality",
            "compress_mode_target" to "Aim for a file size",
            "compress_target_label" to "Custom size (optional)",
            "compress_target_required" to "Type the size you need",
            "protect_sheet_note" to "in any PDF app, on any device"
        )
        for ((key, stale) in old) {
            assertFalse("stale '$stale' on $key", (value("en", key) ?: "").contains(stale))
        }
        assertEquals(
            "password_charset is a precise malformed-input error, not a length-only warning",
            "Use at least 6 characters. Letters, numbers, symbols, Hindi and emoji all work. " +
                "Only an incomplete character pair is not accepted.",
            value("en", "password_charset")
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class Sh2CopyDietRoleEnTest : Sh2CopyDietRoleBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class Sh2CopyDietRoleHiTest : Sh2CopyDietRoleBase()

abstract class Sh2CopyDietRoleBase {

    private lateinit var app: Application
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test(timeout = 30_000)
    fun `compress sheet uses P for facts and S for guidance`() {
        app = ApplicationProvider.getApplicationContext()
        CompressFlow.estimateForTest = { _, _ -> 120_000L }
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Role diet doc")
        val controller: ActivityController<DocumentViewerActivity> = Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        )
        val activity = controller.setup().get()
        try {
            val deadline = System.currentTimeMillis() + 8_000L
            while (activity.findViewById<TextView>(R.id.pageCount)?.text?.isNullOrEmpty() != false &&
                System.currentTimeMillis() < deadline
            ) { idle(); Thread.sleep(10) }
            activity.findViewById<View>(R.id.btnMore).performClick()
            idle()
            ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.rowMoreCompress).performClick()
            val ready = System.currentTimeMillis() + 8_000L
            while (ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.editCompressTarget) == null &&
                System.currentTimeMillis() < ready
            ) { idle(); Thread.sleep(10) }
            val decor = requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView
            val density = app.resources.displayMetrics.density
            val p = ContextCompat.getColor(app, R.color.sw_sh2_text_primary)
            val s = ContextCompat.getColor(app, R.color.sw_sh2_text_secondary)

            fun hasPrimarySpan(text: CharSequence): Boolean =
                text is android.text.Spanned &&
                    text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                        .any { it.foregroundColor == p }

            val name = decor.findViewById<TextView>(R.id.compressSourceName)
            assertEquals("filename is P", p, name.currentTextColor)
            assertEquals("filename is 16sp", 16f * density, name.textSize, 1f)

            val meta = decor.findViewById<TextView>(R.id.compressSourceMeta)
            assertEquals("supporting meta is S", s, meta.currentTextColor)
            assertEquals("meta is 14sp", 14f * density, meta.textSize, 1f)
            assertTrue("page count and size are P spans", hasPrimarySpan(meta.text))

            val levelGroup = decor.findViewById<android.view.ViewGroup>(R.id.levelGroup)
            val estimateDeadline = System.currentTimeMillis() + 8_000L
            while (!hasPrimarySpan((levelGroup.getChildAt(1) as TextView).text) &&
                System.currentTimeMillis() < estimateDeadline
            ) { idle(); Thread.sleep(10) }
            val row = levelGroup.getChildAt(1) as RadioButton
            assertTrue("measured estimate contains P spans", hasPrimarySpan(row.text))
            assertTrue(
                "measured estimate keeps an S connector",
                row.text is android.text.Spanned &&
                    (row.text as android.text.Spanned)
                        .getSpans(0, row.text.length, ForegroundColorSpan::class.java)
                        .any { it.foregroundColor == s }
            )

            val field = decor.findViewById<EditText>(R.id.editCompressTarget)
            assertEquals("entered numbers are P", p, field.currentTextColor)
            assertEquals("entered numbers are 16sp", 16f * density, field.textSize, 1f)
            assertEquals("placeholder is S", s, field.currentHintTextColor)

            val helper = decor.findViewById<TextView>(R.id.compressTargetHint)
            assertEquals("helper is S", s, helper.currentTextColor)
            assertEquals("helper is 14sp", 14f * density, helper.textSize, 1f)

            for (id in listOf(R.id.v2t_quick_20, R.id.v2t_quick_50, R.id.v2t_quick_100, R.id.v2t_quick_200)) {
                val chip = decor.findViewById<Chip>(id)
                assertNotNull("chip $id exists", chip)
                assertEquals("enabled chip label is P", p, chip.currentTextColor)
                assertEquals("enabled chip is 14sp", 14f * density, chip.textSize, 1f)
            }
        } finally {
            idle()
            CompressFlow.estimateForTest = null
            RuntimeEnvironment.setFontScale(1f)
            controller.destroy()
        }
    }
}
