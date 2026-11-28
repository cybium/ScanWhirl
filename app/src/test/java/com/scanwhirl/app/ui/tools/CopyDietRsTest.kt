package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * COPY_DIET §1 roles and §2 "Results / RS" rows. The hero is P-hero white 28sp, the one receipt is
 * S14, and the retired duplicate sentences never appear on the result screen.
 */
abstract class CopyDietRsBase {

    protected val app: Application get() = ApplicationProvider.getApplicationContext()
    protected val repo get() = ScanRepository(app)
    protected val isHi: Boolean get() = app.resources.configuration.locales[0].language == "hi"
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun clearProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    protected fun visibleTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString().trim()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    protected fun resultIntent() = ToolResultActivity.intent(
        app, "compress",
        docIds = listOf(DocFixtures.saved(repo, app, pages = 1, name = "Receipt")),
        beforeBytes = 1_600_000L, targetBytes = 100L
    )

    @Test(timeout = 30_000)
    fun `facts and prominent location use the shared type and colour roles`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, resultIntent())
            controller.setup()
            idle()
            val activity = controller.get()
            val density = app.resources.displayMetrics.density
            val hero = activity.findViewById<TextView>(R.id.toolResultSummary)
            assertEquals(com.scanwhirl.app.ui.kit.SwType.HEADING_SP * density, hero.textSize, 1f)
            // W5a: P-hero and S roles resolve through the one token palette.
            assertEquals(app.getColor(R.color.tok_text), hero.currentTextColor)
            val receipt = activity.findViewById<TextView>(R.id.toolResultName)
            assertEquals(com.scanwhirl.app.ui.kit.SwType.BODY_SP * density, receipt.textSize, 1f)
            assertEquals(app.getColor(R.color.tok_text), receipt.currentTextColor)
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `limit chip carries only the limit argument`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, resultIntent())
            controller.setup()
            idle()
            val chip = controller.get().findViewById<TextView>(R.id.rsChip).text.toString()
            val expected = if (isHi) "100 B से ज़्यादा" else "Above 100 B"
            assertEquals(expected, chip)
        } finally {
            controller?.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `deleted duplicate sentences are not visible`() {
        var controller: ActivityController<ToolResultActivity>? = null
        try {
            controller = Robolectric.buildActivity(ToolResultActivity::class.java, resultIntent())
            controller.setup()
            idle()
            val texts = visibleTexts(controller.get().window!!.decorView)
            val retired = if (isHi) {
                listOf("फ़ाइल अब", "नई प्रति;", "मूल कागज़ात में सुरक्षित है।", "पहले %1\$s")
            } else {
                listOf(
                    "File now", "New copy; original kept.", "Original kept in Library.",
                    "Compressed ·", "Resized ·", "Saved ·", "Before %1\$s"
                )
            }
            retired.forEach { phrase ->
                assertFalse("retired copy is gone: $phrase ($texts)",
                    texts.any { it.contains(phrase, ignoreCase = true) })
            }
        } finally {
            controller?.destroy()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CopyDietRsEnTest : CopyDietRsBase() {

    @Test
    fun `exact EN Results copy`() {
        assertEquals("Size limit not reached", app.getString(R.string.cp1_compress_header_missed))
        assertEquals("Could not make it smaller", app.getString(R.string.cp1_compress_header_grew))
        assertEquals("No smaller copy made. Original kept.", app.getString(R.string.compress_grew_warning))
        assertEquals("Original kept", app.getString(R.string.v2t_new_copy_original_kept))
        assertEquals("Remove new copy", app.getString(R.string.ux130_l3_result_remove_copy))
        assertEquals("Compress", app.getString(R.string.v2t_compress_this_copy))
        assertEquals("Protect", app.getString(R.string.v2t_protect_this_copy))
        assertEquals("Saved in Library", app.getString(R.string.rs_where_library))
        assertEquals("Save a copy to keep this file", app.getString(R.string.rs_unsaved_output))
        assertEquals("Preview unavailable", app.getString(R.string.rs_state_only))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CopyDietRsHiTest : CopyDietRsBase() {

    @Test
    fun `exact HI Results copy`() {
        // COPY_DIET's proposed "साइज़" is a rejected glossary loanword; use the accepted
        // "आकार" for size so the binding Hindi glossary invariant stays green.
        assertEquals("आकार की सीमा तक नहीं पहुँचे", app.getString(R.string.cp1_compress_header_missed))
        assertEquals("फ़ाइल छोटी नहीं कर सके", app.getString(R.string.cp1_compress_header_grew))
        assertEquals("छोटी प्रति नहीं बनी। मूल फ़ाइल वैसी ही है।", app.getString(R.string.compress_grew_warning))
        assertEquals("मूल फ़ाइल वैसी ही है", app.getString(R.string.v2t_new_copy_original_kept))
        assertEquals("नई प्रति हटाएँ", app.getString(R.string.ux130_l3_result_remove_copy))
        assertEquals("छोटा करें", app.getString(R.string.v2t_compress_this_copy))
        assertEquals("सुरक्षित करें", app.getString(R.string.v2t_protect_this_copy))
        assertEquals("कागज़ात में सहेजी गई", app.getString(R.string.rs_where_library))
        assertEquals("फ़ाइल रखने के लिए प्रति सहेजें", app.getString(R.string.rs_unsaved_output))
        assertEquals("झलक उपलब्ध नहीं है", app.getString(R.string.rs_state_only))
    }
}
