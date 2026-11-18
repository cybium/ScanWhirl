package com.scanwhirl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JUDGE_HINDI.md final glossary — regression guard for the Hindi UI.
 *
 * The judged wording replaced a set of English loanwords that a reader who knows
 * no English cannot use (कंप्रेस, क्वालिटी, कस्टम, टूल, वॉल्ट, साइज़, पेज …). A later
 * edit must not quietly bring them back. The check runs over every file in
 * `values-hi`, including plurals items, and ignores XML comments.
 *
 * Allowed exceptions are listed in [ALLOWED_WORDS] and are ordinary Hindi words
 * that merely contain a rejected string (for example मर्ज़ी contains मर्ज).
 * Product and format names (ScanWhirl, PDF, Word, Excel, Pro, QR, ZIP, PIN …)
 * are not in the rejected list at all.
 *
 * The second test pins the judged navigation words: Library कागज़ात, Tools काम,
 * Vault तिजोरी, Me मैं, Create बनाएँ, Scan action स्कैन करें, Settings सेटिंग.
 */
class HindiGlossaryTermsTest {

    private val hiDir = File("src/main/res/values-hi")

    /** Rejected transliterations / loanwords from the judged glossary. */
    private val rejected = listOf(
        "कंप्रेस", "कम्प्रेस",
        "क्वालिटी",
        "कस्टम",
        "साइज़", "साइज",
        "टूल",
        "वॉल्ट",
        "इम्पोर्ट", "इंपोर्ट", "एक्सपोर्ट", "एक्सपोर्ट",
        "डिवाइस",
        "लाइब्रेरी",
        "फ़िल्टर", "फिल्टर",
        "ट्रैश",
        "मर्ज",
        "क्रॉप",
        "पेज",
        "पाठ", "टेक्स्ट", "टैक्स्ट",
        "अनडू", "रीडू",
        "डिफ़ॉल्ट", "डिफॉल्ट", "सिस्टम",
        "शेयर", "कॉपी", "सेव", "फ्री", "सर्च", "क्विक",
        "प्रीव्यू", "प्रिव्यू",
        "टैप", "पिन", "आईडी", "डुप्लिकेट", "बैच", "गैलरी",
        "ऑफ़लाइन", "ऑफलाइन",
        "हाइलाइट", "वॉटरमार्क", "रिडैक्ट",
        "बैकअप", "रिकवरी", "पासकोड", "फ़िंगरप्रिंट", "फिंगरप्रिंट",
        "एन्क्रिप्ट", "इन्क्रिप्ट",
        "डाउनलोड", "अपलोड",
        "स्क्रीन",
        "प्रो",
    )

    /**
     * Ordinary Hindi words that contain a rejected string but are not the
     * rejected term: मर्ज़ी ("as you wish", used for the custom crop preset).
     */
    private val allowedWords = listOf("मर्ज़ी")

    @Test
    fun `rejected transliterations do not appear in Hindi UI strings`() {
        val offenders = mutableListOf<String>()
        for ((name, quantity, text) in readAll()) {
            var bare = text
            for (allowed in allowedWords) bare = bare.replace(allowed, " ")
            for (term in rejected) {
                if (bare.contains(term)) {
                    offenders.add("${label(name, quantity)} has \"$term\" = \"$text\"")
                }
            }
        }
        assertTrue(
            "Rejected glossary terms came back into values-hi:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `judged navigation words are exact`() {
        val expected = mapOf(
            "shell_nav_library" to "कागज़ात",
            "shell_nav_tools" to "काम",
            "shell_nav_vault" to "तिजोरी",
            "shell_nav_me" to "मैं",
            "shell_nav_scan" to "स्कैन",
            "tools_group_create" to "बनाएँ",
            "scan" to "स्कैन करें",
            "tool_settings" to "सेटिंग",
        )
        val actual = readAll().filter { it.second == null }.associate { it.first to it.third }
        val wrong = expected.mapNotNull { (name, hi) ->
            val found = actual[name] ?: return@mapNotNull "$name is missing"
            if (found == hi) null else "$name = \"$found\" (judged: \"$hi\")"
        }
        assertTrue("Judged navigation words changed:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    private fun label(name: String, quantity: String?): String =
        if (quantity == null) name else "$name[$quantity]"

    /** (name, quantity or null for a plain string, text) for every entry in values-hi. */
    private fun readAll(): List<Triple<String, String?, String>> {
        val files = hiDir.listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
        assertTrue("No XML files found in ${hiDir.absolutePath}", !files.isNullOrEmpty())
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val out = mutableListOf<Triple<String, String?, String>>()
        for (file in files!!) {
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isEmpty()) continue
                when (el.tagName) {
                    "string" -> out.add(Triple(name, null, el.textContent))
                    "plurals" -> {
                        val items = el.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            out.add(Triple(name, item.getAttribute("quantity"), item.textContent))
                        }
                    }
                }
            }
        }
        return out
    }
}
