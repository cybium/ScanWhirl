package com.scanwhirl.app.ux130.l4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UX-L4 resource contract. The lane owns two dedicated resource files
 * (`strings_ux130_l4.xml` in values/ and values-hi/) so it cannot collide with
 * another lane's EN/HI edits. Every new string uses the `ux130_l4_` prefix.
 *
 * These are plain unit checks that read the source files, so they compile on
 * the base commit and fail there (RED) until the lane files exist. They are
 * deliberately in addition to the existing global parity / glossary tests.
 */
class L4StringsTest {

    private val enFile = File("src/main/res/values/strings_ux130_l4.xml")
    private val hiFile = File("src/main/res/values-hi/strings_ux130_l4.xml")

    /** The decided English copy from UX_FIX_PLAN §L4 and UX4_viewer_editor.md. */
    private val decided = linkedMapOf(
        "ux130_l4_select_pages" to "Select pages",
        "ux130_l4_select_page" to "Select page",
        "ux130_l4_discard_unavailable" to
            "Discard is unavailable in this layout. Keep the copy or change orientation.",
        "ux130_l4_all_pages" to "All pages",
        "ux130_l4_finish_editing" to "Finish editing",
        "ux130_l4_save_as_txt" to "Save as .txt",
        "ux130_l4_discard_edits" to "Discard edits",
        "ux130_l4_discard_edits_confirm" to "Discard unsaved edits?",
        "ux130_l4_go_to_page" to "Go to page",
        "ux130_l4_go_to_page_invalid" to "Enter a page number from 1 to %1\$d.",
        "ux130_l4_page_zoom" to "Page %1\$d of %2\$d. Zoom page.",
        "ux130_l4_zoom_page" to "Zoom page",
        "ux130_l4_close_zoom" to "Close zoom",
    )

    @Test
    fun `lane string files exist with EN and HI entries`() {
        assertTrue("missing EN lane file ${enFile.path}", enFile.isFile)
        assertTrue("missing HI lane file ${hiFile.path}", hiFile.isFile)
        val en = read(enFile)
        val hi = read(hiFile)
        val missingEn = decided.keys.minus(en.keys).sorted()
        val missingHi = decided.keys.minus(hi.keys).sorted()
        assertTrue("EN lane file missing keys: $missingEn", missingEn.isEmpty())
        assertTrue("HI lane file missing keys: $missingHi", missingHi.isEmpty())
    }

    @Test
    fun `decided English wording is exact`() {
        val en = read(enFile)
        val wrong = decided.filter { (key, text) -> en[key] != text }
            .map { (key, text) -> "$key = \"${en[key]}\" (expected \"$text\")" }
        assertTrue("Wrong English copy:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun `Hindi is natural and avoids rejected loanwords`() {
        val hi = read(hiFile)
        val rejected = listOf(
            "पेज", "पाठ", "टेक्स्ट", "कॉपी", "शेयर", "सेव", "स्क्रीन", "डिफ़ॉल्ट",
            "क्रॉप", "फ़िल्टर", "टूल", "डाउनलोड", "अनडू", "रीडू", "ऑफ़लाइन",
            // HINDI_REVIEW_1.3.0 consistency glossary: पन्ना, प्रति, दृश्य, बिना सहेजे.
            "पृष्ठ", "प्रतिलिपि", "लेआउट", "असहेजे",
        )
        val offenders = mutableListOf<String>()
        for ((key, text) in hi) {
            for (term in rejected) {
                if (text.contains(term)) offenders.add("$key (\"$text\") has \"$term\"")
            }
            if (text.contains('—') || text.contains('–')) {
                offenders.add("$key has a long dash")
            }
        }
        assertTrue(
            "Hindi lane copy is not natural:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `format specifiers match by position and type across EN and HI`() {
        val en = read(enFile)
        val hi = read(hiFile)
        val present = decided.keys.filter { en.containsKey(it) && hi.containsKey(it) }
        val mismatches = present.filter { key ->
            specs(en.getValue(key)) != specs(hi.getValue(key))
        }.map { key -> "$key: en=${specs(en.getValue(key))} hi=${specs(hi.getValue(key))}" }
        assertTrue(
            "Format specifier mismatches:\n${mismatches.joinToString("\n")}",
            mismatches.isEmpty(),
        )
    }

    private fun read(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val xml = file.readText(Charsets.UTF_8)
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val out = mutableMapOf<String, String>()
        for (m in Regex("<string\\s+([^>]*)>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val name = Regex("name=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues[1] ?: continue
            out[name] = m.groupValues[2]
                .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
        return out
    }

    /** (argument position, conversion type) for each format specifier, sorted. */
    private fun specs(text: String): List<Pair<Int, Char>> {
        val out = mutableListOf<Pair<Int, Char>>()
        val conversions = "sdfeEgGaAxXn%"
        var implicit = 1
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') { i++; continue }
            if (i + 1 < text.length && text[i + 1] == '%') { i += 2; continue }
            var j = i + 1
            val digitsStart = j
            while (j < text.length && text[j].isDigit()) j++
            val explicit = if (j < text.length && text[j] == '$' && j > digitsStart) {
                text.substring(digitsStart, j).toIntOrNull().also { j++ }
            } else {
                j = i + 1
                null
            }
            while (j < text.length && text[j] !in conversions) j++
            if (j < text.length) {
                val type = text[j]
                if (type != '%') {
                    out += (explicit ?: implicit) to type
                    if (explicit == null) implicit++
                }
                j++
            }
            i = j
        }
        return out.sortedWith(compareBy<Pair<Int, Char>> { it.first }.thenBy { it.second })
    }
}
