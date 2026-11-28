package com.scanwhirl.app.ui.kit

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.shell.ToolsHubScreen
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Council lane N (Names and registry). The decisions file is final: §1 overrides the
 * naming-system proposal where they disagree. Three guarantees are pinned here:
 *
 *  1. every decided EN value and its Hindi pair are actually in the resource files;
 *  2. the OCR concept is one tile - `to_text`/`hindi_text` are gone, old names still search;
 *  3. at 100 % font, 360dp and 411dp, EN and HI, every tool tile label fits in one or two
 *     whole-word lines with no clipping and no ellipsis (design at 100 % first).
 */
class ToolNamesCouncilParityTest {

    private val valuesDir = File("src/main/res/values")
    private val hiDir = File("src/main/res/values-hi")

    private fun value(dir: File, key: String): String? {
        val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
            .orEmpty()
        for (file in files) {
            val xml = file.readText(Charsets.UTF_8)
            val m = Regex(
                "<string\\s+name=\"$key\"[^>]*>(.*?)</string>",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml) ?: continue
            return m.groupValues[1].trim()
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("\\'", "'")
        }
        return null
    }

    /** EN | HI, exactly as decided in COUNCIL_DECISIONS §1 (base: NAMING_SYSTEM §3). */
    private val decided = mapOf(
        // tool tiles - COUNCIL §1 tile table
        "tool_import_pdf" to ("Import PDF" to "PDF आयात करें"),
        "tool_extract" to ("Extract" to "पन्ने निकालें"),
        "tool_organize_pages" to ("Reorder" to "क्रम बदलें"),
        "tool_insert_pages" to ("Add pages" to "पन्ने जोड़ें"),
        "tool_folders" to ("Folders" to "फ़ोल्डर"),
        "tools_tool_batch" to ("Batch" to "एक साथ"),
        "tool_form_ready_tile" to ("Form photo" to "फ़ॉर्म फ़ोटो"),
        "tool_pdf_to_images" to ("PDF to JPG" to "PDF से JPG"),
        "tool_to_word" to ("Word" to "Word"),
        "tools_tool_to_powerpoint" to ("Slides" to "स्लाइड"),
        "tool_to_excel" to ("Excel" to "Excel"),
        "tool_to_text" to ("Save .txt" to ".txt सहेजें"),
        "tool_zip" to ("ZIP" to "ZIP"),
        "tool_searchable_pdf" to ("OCR PDF" to "OCR PDF"),
        "tool_searchable_pdf_sub" to ("Searchable PDF" to "खोजने लायक PDF बनाएँ"),
        "tool_draw" to ("Draw" to "लकीरें बनाएँ"),
        "tool_watermark" to ("Watermark" to "छाप लगाएँ"),
        "tool_page_numbers" to ("Page numbers" to "पन्नों पर नंबर"),
        "tool_protect" to ("Protect" to "सुरक्षित करें"),
        "tool_unlock" to ("Unlock" to "ताला खोलें"),
        "tool_remove_metadata" to ("Clear metadata" to "मेटाडेटा हटाएँ"),
        "tool_read_text" to ("OCR" to "OCR"),
        "tool_hindi_text" to ("Hindi" to "हिंदी"),
        // job and group headings - COUNCIL §1 "Headings"
        "tools_group_text" to ("OCR" to "OCR"),
        "tools_job_smaller" to ("Compress & resize" to "छोटा करें"),
        "tools_job_organise" to ("Combine & organize" to "जोड़ें और सजाएँ"),
        "tools_job_markup" to ("Sign & mark up" to "हस्ताक्षर और निशान"),
        "tools_job_private" to ("Privacy" to "निजता"),
        "tools_job_text" to ("OCR & search" to "OCR और खोजें"),
        "tools_job_format" to ("Convert" to "फ़ॉर्मैट बदलें"),
        "tools_job_new" to ("Scan & import" to "स्कैन और आयात"),
        // quick tools and dock
        "quick_to_word" to ("Word" to "Word"),
        "quick_read_text" to ("OCR" to "OCR"),
        "dock_read_text" to ("OCR" to "OCR"),
        "reader_dock_text" to ("OCR" to "OCR"),
        "btn_ocr_text" to ("OCR" to "OCR"),
        // document actions and buttons - COUNCIL §1 "Actions and buttons"
        "doc_actions" to ("Actions" to "कार्रवाई"),
        "doc_actions_header" to ("Document actions" to "दस्तावेज़ की कार्रवाई"),
        "doc_action_edit_crop" to ("Edit & crop" to "बदलें और काटें"),
        "btn_vault_unhide" to ("Remove from Vault" to "तिजोरी से निकालें"),
        "ocr_copy" to ("Copy text" to "नकल करें"),
        "ocr_save_txt" to ("Save .txt" to ".txt सहेजें"),
        "ocr_w5_retry_txt" to ("Try again" to "फिर कोशिश करें"),
        "tools_result_done" to ("Done" to "हो गया"),
        "tools_search_hint" to ("Find a tool, e.g. \"OCR\"" to "काम खोजें, जैसे \"OCR\""),
        "tools_no_match" to ("No tool for “%1\$s”" to "“%1\$s” के लिए कोई काम नहीं"),
        // OCR screen
        "ocr_title" to ("Extract text (OCR)" to "लिखा हुआ निकालें (OCR)"),
        "ocr_screen_title" to ("OCR" to "OCR"),
        // Settings - COUNCIL §1 "Settings"
        "me_pro_summary" to ("All Pro tools" to "सभी Pro काम"),
        "me_settings_summary" to ("Look & language" to "रंग-रूप और भाषा"),
        "settings_font" to ("Text size" to "लिखाई का आकार"),
        "settings_greeting" to ("Home status line" to "मुख्य पन्ने की स्थिति-पंक्ति"),
        "settings_searchable_pdf" to ("Searchable PDFs" to "खोजने लायक PDF"),
        "settings_auto_copy_to_documents" to ("Save copy to Documents" to "Documents में प्रति सहेजें"),
        "settings_block_screenshots" to ("Block screenshots" to "दस्तावेज़ों की तस्वीर रोकें"),
        "settings_pro_summary" to ("All Pro tools" to "सभी Pro काम"),
        // counts and empty states
        "pro_see_all" to ("See all %1\$d Pro tools ›" to "Pro के सभी %1\$d काम ›"),
        "create_disc_desc" to ("Scan or import a document" to "स्कैन करें या दस्तावेज़ आयात करें"),
        "home_empty_title" to ("No documents yet" to "अभी कोई दस्तावेज़ नहीं"),
        "home_empty_body" to ("Your scans stay on this phone. No account, no upload." to "आपके स्कैन इसी फ़ोन पर रहते हैं। कोई खाता नहीं, फ़ाइलें बाहर नहीं जातीं।"),
        "home_no_match" to ("No matches" to "कोई नतीजा नहीं"),
        "w7_tools_empty_title" to ("No tools yet" to "अभी कोई काम नहीं"),
        "w7_tools_empty_body" to ("No tool is ready in this build. Your documents are in the Library." to "इस बिल्ड में अभी कोई काम तैयार नहीं। आपके दस्तावेज़ कागज़ात में हैं।"),
        "w7_tools_error_title" to ("Tools didn't load" to "काम नहीं खुले"),
        "ocr_empty" to ("No text found" to "कोई लिखा हुआ नहीं मिला")
    )

    @Test
    fun `every decided English and Hindi name is applied`() {
        val problems = mutableListOf<String>()
        for ((key, pair) in decided) {
            val en = value(valuesDir, key)
            val hi = value(hiDir, key)
            if (en != pair.first) problems += "$key EN: '$en' (expected '${pair.first}')"
            if (hi != pair.second) problems += "$key HI: '$hi' (expected '${pair.second}')"
        }
        assertTrue(
            "Decided names not applied:\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolNamesCouncilRegistryTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `OCR is one tile and the duplicate text tiles are gone`() {
        assertNull("to_text must not be a tile", ToolRegistry.byId("to_text"))
        assertNull("hindi_text must not be a tile", ToolRegistry.byId("hindi_text"))
        val ocr = ToolRegistry.all.filter { app.getString(it.title) == "OCR" }
        assertEquals("exactly one OCR tile", listOf("read_text"), ocr.map { it.id })

        for (alias in listOf("ocr", "read text", "to text", "extract text", "txt", "हिंदी", "लिखा हुआ")) {
            assertTrue(
                "old alias '$alias' must still find the OCR tile",
                ToolRegistry.search(app, alias).any { it.id == "read_text" }
            )
        }
    }

    @Test
    fun `old tool names still find the renamed tools`() {
        val aliases = mapOf(
            "to word" to "to_word",
            "to powerpoint" to "to_powerpoint",
            "powerpoint" to "to_powerpoint",
            "to excel" to "to_excel",
            "extract pages" to "extract",
            "organize pages" to "organize_pages",
            "insert pages" to "insert_pages",
            "tags" to "folders",
            "batch tools" to "batch_tools",
            "protect pdf" to "protect",
            "unlock pdf" to "unlock",
            "remove metadata" to "remove_metadata",
            "searchable pdf" to "searchable_pdf",
            "searchable" to "searchable_pdf",
            "search pdf" to "searchable_pdf",
            "zip export" to "zip",
            "pdf to images" to "pdf_to_images",
            "images" to "pdf_to_images",
            "pictures" to "pdf_to_images",
            "photos" to "pdf_to_images",
            "form ready" to "form_ready"
        )
        for ((alias, id) in aliases) {
            assertTrue(
                "'$alias' must still find $id",
                ToolRegistry.search(app, alias).any { it.id == id }
            )
        }
    }

    @Test
    fun `counts are derived from the merged catalogue`() {
        // LX added link_index: Pro, READY, one free use (old -> new: 37->38, 36->37,
        // 16->17 pro, 15->16 on sale). The formatted Pro count still comes from proCount().
        assertEquals(38, ToolRegistry.all.size)
        assertEquals(37, ToolRegistry.all.count { it.status == ToolStatus.READY })
        assertEquals(1, ToolRegistry.all.count { it.status == ToolStatus.SOON })
        assertEquals(17, ToolRegistry.all.count { it.pro })
        assertEquals(16, ToolRegistry.proCount())
    }

    @Test
    fun `See all count is a format filled from proCount not a hard-coded number`() {
        val n = ToolRegistry.proCount()
        for (res in listOf(R.string.pro_see_all, R.string.pro_more_tools)) {
            val shown = app.getString(res, n)
            assertTrue(
                "the shown number (${shown}) must equal proCount()=$n",
                shown.contains(n.toString())
            )
            // A sentinel proves the number comes from the argument, not from the string.
            val sentinel = app.getString(res, 4242)
            assertTrue(
                "the count slot must be a %1\$d argument, not hard-coded: $sentinel",
                sentinel.contains("4242")
            )
        }
    }
}

/** 100 % font, whole-word, no-clip tile labels at both phone widths, in both languages. */
abstract class ToolTileNameFitBase {

    private lateinit var app: android.app.Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false)
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Names doc")
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A line break is whole-word only when a space sits on one side of the break. */
    private fun midWordBreaks(text: String, layout: android.text.Layout): List<Int> {
        val bad = mutableListOf<Int>()
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            val before = text.getOrNull(end - 1)
            val after = text.getOrNull(end)
            if (before?.isWhitespace() != true && after?.isWhitespace() != true) bad += line
        }
        return bad
    }

    @Test
    fun `every tool tile label is one or two whole-word lines with no clipping`() {
        RuntimeEnvironment.setFontScale(1.0f)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                activity.findViewById<View>(R.id.swNavTools).performClick()
                idle()
                val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
                val dm = activity.resources.displayMetrics
                val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
                val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                repeat(2) { root.measure(w, h); root.layout(0, 0, dm.widthPixels, dm.heightPixels) }

                val tiles = ToolsHubScreen.tiles(activity.findViewById(R.id.swToolsBody))
                assertTrue("the hub draws tiles", tiles.isNotEmpty())
                val problems = mutableListOf<String>()
                for (tile in tiles) {
                    val label: TextView = tile.labelView
                    val text = label.text.toString()
                    val layout = label.layout ?: run {
                        problems += "$text: never laid out"
                        continue
                    }
                    // NAMING_SYSTEM §1.9: a longer Devanagari tile grows to three lines
                    // rather than being ellipsized; English keeps the one/two-line rule.
                    val isHindi = label.resources.configuration.locales.get(0).language == "hi"
                    val maxLines = if (isHindi) 3 else 2
                    if (layout.lineCount !in 1..maxLines) {
                        problems += "$text: ${layout.lineCount} lines at 100% (max $maxLines)"
                    }
                    val broken = midWordBreaks(text, layout)
                    if (broken.isNotEmpty()) problems += "$text: mid-word break after line $broken"
                    val avail = label.width - label.totalPaddingLeft - label.totalPaddingRight
                    for (line in 0 until layout.lineCount) {
                        if (layout.getEllipsisCount(line) != 0) {
                            problems += "$text: ellipsized on line $line"
                        }
                        if (layout.getLineMax(line) > avail + 0.5f) {
                            problems += "$text: line $line wider than its column"
                        }
                    }
                    if (layout.height + label.totalPaddingTop + label.totalPaddingBottom >
                        label.height + 1
                    ) {
                        problems += "$text: clipped"
                    }
                }
                assertTrue(
                    "100% tile labels: ${problems.joinToString(" | ")}",
                    problems.isEmpty()
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ToolTileNameFitEn360Test : ToolTileNameFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h780dp-xhdpi")
class ToolTileNameFitHi360Test : ToolTileNameFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h780dp-xhdpi")
class ToolTileNameFitEn411Test : ToolTileNameFitBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w411dp-h780dp-xhdpi")
class ToolTileNameFitHi411Test : ToolTileNameFitBase()
