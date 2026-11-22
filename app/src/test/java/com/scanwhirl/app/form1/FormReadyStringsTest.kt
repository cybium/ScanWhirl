package com.scanwhirl.app.form1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** FORM1: the new screen is fully bilingual and uses the judged Hindi vocabulary. */
class FormReadyStringsTest {

    private val en = File("src/main/res/values/strings_form1.xml")
    private val hi = File("src/main/res/values-hi/strings_form1.xml")

    private fun keys(file: File): Set<String> {
        assertTrue("missing ${file.path}", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it).attributes
            ?.getNamedItem("name")?.nodeValue }.toSet()
    }

    private fun texts(file: File): String {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = StringBuilder()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i)
            if (node.nodeName == "string" || node.nodeName == "plurals") out.append(node.textContent).append('\n')
        }
        return out.toString()
    }

    @Test
    fun `English and Hindi declare the same keys`() {
        assertEquals(keys(en), keys(hi))
        assertTrue("no keys found", keys(en).isNotEmpty())
    }

    @Test
    fun `Hindi uses judged words and no rejected loanwords`() {
        val text = texts(hi)
        for (banned in listOf("कंप्रेस", "क्वालिटी", "कस्टम", "साइज़", "टूल", "वॉल्ट", "क्रॉप",
            "पेज", "फ़िल्टर", "बैकअप", "ऑफ़लाइन", "सेव", "शेयर", "प्रो", "डिफ़ॉल्ट")) {
            assertFalse("rejected Hindi term \"$banned\"", text.contains(banned))
        }
        assertTrue("photo should use the judged term", text.contains("फ़ोटो"))
        assertTrue("signature should use the judged term", text.contains("हस्ताक्षर"))
    }

    @Test
    fun `fit crop and exam strings exist in both locales`() {
        val required = listOf(
            "form1_exam_label", "form1_exam_ug_photo", "form1_exam_ug_signature",
            "form1_exam_ug_thumb", "form1_exam_pg_photo", "form1_exam_pg_signature",
            "form1_exam_pg_thumb", "form1_example_exam", "form1_mode_fit",
            "form1_mode_crop", "form1_mode_helper"
        )
        for (file in listOf(en, hi)) {
            val present = keys(file)
            for (key in required) assertTrue("missing $key in ${file.name}", key in present)
        }
        val hiText = texts(hi)
        assertTrue("Hindi needs the whole-photo label", hiText.contains("पूरी फ़ोटो"))
        assertTrue("Hindi needs the Crop action", hiText.contains("छाँटें"))
        assertTrue("Hindi needs the suggested-pixels marker", hiText.contains("सुझावित"))
        assertFalse("Hindi must not use the rejected crop loanword", hiText.contains("क्रॉप"))
    }

    @Test
    fun `no long dashes in user visible text`() {
        for (file in listOf(en, hi)) {
            val text = texts(file)
            assertFalse("em dash in ${file.name}", text.contains('—'))
            assertFalse("en dash in ${file.name}", text.contains('–'))
        }
    }

    @Test
    fun `copy diet removed keys are gone and new keys exist in both locales`() {
        val removed = listOf(
            "form1_check_rules", "form1_kb_invalid", "form1_kb_max_nonpositive",
            "form1_result_pixels_ok", "form1_result_dpi_ok", "form1_note_measured",
            "form1_note_below_min", "form1_note_above_max"
        )
        val added = listOf(
            "form1_example_label", "form1_min_miss_constraint",
            "form1_note_measured_short", "form1_note_bytes"
        )
        for (file in listOf(en, hi)) {
            val present = keys(file)
            for (key in removed) assertFalse("stale COPY_DIET key $key in ${file.name}", key in present)
            for (key in added) assertTrue("missing COPY_DIET key $key in ${file.name}", key in present)
        }
    }
}
