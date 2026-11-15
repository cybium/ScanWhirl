package com.scanwhirl.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Hygiene checks for every XML file in the `values-hi` resource folder.
 *
 * The Hindi translation used to be patched by an automated word replace that glued
 * Devanagari words inside English words (e.g. "Dचालूe" instead of "Done") and left
 * whole English sentences behind ("Could नहीं save"). These checks are plain JUnit
 * (no Android runtime needed) and cover all files, not only `strings.xml`:
 *
 *  - no Latin letter glued to a Devanagari letter inside one word;
 *  - no leftover English UI word from [ENGLISH_UI_WORDS] (a Hindi string must not
 *    read "Delete स्कैन?" or "Drag को reorder");
 *  - placeholders match the English string of the same name, so `getString(...)`
 *    can never throw.
 *
 * Product, format and product-UI names (ScanWhirl, Pro, PDF, ZIP, PIN, Downloads,
 * email/URL text, …) are stripped before the English-word check, plus the
 * [EXTRA_LATIN_ALLOWED] technical words this app legitimately shows in Latin.
 */
class HindiStringsHygieneTest {

    private val valuesDir = File("src/main/res/values")
    private val hiDir = File("src/main/res/values-hi")

    /** Legitimate Latin+Devanagari mixes, by string name, if any turn up later. */
    private val gluedAllowList = emptySet<String>()

    /** Hindi string names that may keep an English word, if one is ever needed. */
    private val englishWordAllowList = emptySet<String>()

    @Test
    fun `no Latin letter is glued to Devanagari`() {
        val offenders = mutableListOf<String>()
        for ((name, items) in readAll(hiDir)) {
            for ((quantity, text) in items) {
                if (name in gluedAllowList) continue
                if (GLUED.containsMatchIn(unmaskEscapes(text))) {
                    offenders.add(label(name, quantity) + " = \"" + text + "\"")
                }
            }
        }
        assertTrue("Latin/Devanagari glued inside a word:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `no English UI words are left in Hindi strings`() {
        val offenders = mutableListOf<String>()
        for ((name, items) in readAll(hiDir)) {
            if (name in englishWordAllowList) continue
            for ((quantity, text) in items) {
                val leftover = stripAllowedLatin(unmaskEscapes(text))
                val word = ENGLISH_WORD.find(leftover)?.value
                if (word != null) {
                    offenders.add(label(name, quantity) + " has \"" + word + "\" = \"" + text + "\"")
                }
            }
        }
        assertTrue("English words left in Hindi strings:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `placeholders match the English strings`() {
        val english = readAll(valuesDir)
        val offenders = mutableListOf<String>()
        for ((name, items) in readAll(hiDir)) {
            val enItems = english[name] ?: continue
            val enByQuantity = enItems.toMap()
            for ((quantity, text) in items) {
                // Plurals: a missing quantity in English falls back to "other", as Android does.
                val en = when {
                    quantity == null -> enByQuantity[null]
                    else -> enByQuantity[quantity] ?: enByQuantity["other"]
                } ?: continue
                val hiSpecs = placeholders(text)
                val enSpecs = placeholders(en)
                if (enSpecs.isEmpty()) {
                    // English spells out the number (plural "one"). Android still passes the
                    // count as argument 1, so only a bare %d / %1$d may appear in Hindi.
                    if (hiSpecs.any { it != "d" }) {
                        offenders.add(label(name, quantity) + " hi=" + hiSpecs + " en=[none]")
                    }
                } else if (hiSpecs != enSpecs) {
                    offenders.add(label(name, quantity) + " hi=" + hiSpecs + " en=" + enSpecs)
                }
            }
        }
        assertTrue("Placeholder mismatches:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun label(name: String, quantity: String?): String =
        if (quantity == null) name else "$name[$quantity]"

    private fun xmlFiles(dir: File): List<File> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name }
        assertTrue("No XML files found in ${dir.absolutePath}", !files.isNullOrEmpty())
        return files!!
    }

    /** name -> list of (quantity or null for <string>) -> text, comments excluded. */
    private fun readAll(dir: File): Map<String, List<Pair<String?, String>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String?, String>>>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        for (file in xmlFiles(dir)) {
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isEmpty()) continue
                when (el.tagName) {
                    "string" -> out.getOrPut(name) { mutableListOf() }.add(null to el.textContent)
                    "plurals" -> {
                        val items = el.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            out.getOrPut(name) { mutableListOf() }
                                .add(item.getAttribute("quantity") to item.textContent)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Escapes such as \n, \t, \' or \" are not letters; hide them before the checks. */
    private fun unmaskEscapes(text: String): String = ESCAPE.replace(text, " ")

    /**
     * Removes everything the app may legitimately keep in Latin script: format
     * specifiers, emails and web addresses, `{name}` template tokens, A-Z ranges
     * and the product/format words listed in [LATIN_ALLOWED].
     */
    private fun stripAllowedLatin(text: String): String {
        var s = text
        s = PLACEHOLDER_ANY.replace(s, " ")
        s = EMAIL.replace(s, " ")
        s = URL.replace(s, " ")
        s = TEMPLATE_TOKEN.replace(s, " ")
        s = A_TO_Z_RANGE.replace(s, " ")
        for (pattern in LATIN_ALLOWED_PATTERNS) {
            s = pattern.replace(s, " ")
        }
        return s
    }

    /**
     * Format specifiers of a text, normalized to non-positional form so that `%d`
     * and `%1$d` compare equal (Android treats a lone specifier as argument 1).
     * `%%` is a literal percent sign, not a placeholder.
     */
    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text.replace("%%", "\u0000"))
            .map { it.groupValues[1] }
            .sorted()
            .toList()

    private companion object {
        /** Devanagari matras, letters and digits; excludes danda (U+0964/U+0965). */
        val GLUED = Regex("[A-Za-z][\u0900-\u0963\u0966-\u097F]|[\u0900-\u0963\u0966-\u097F][A-Za-z]")
        val ESCAPE = Regex("\\\\[nt'\"\\\\]")
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?([sdfeEgGaAxX])")
        val PLACEHOLDER_ANY = Regex("%\\d+\\$[a-zA-Z]|%[a-zA-Z]")
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")
        val URL = Regex("[A-Za-z0-9.-]+\\.(com|io|org|net)(/\\S*)?")
        val TEMPLATE_TOKEN = Regex("\\{[a-z]+\\}")
        val A_TO_Z_RANGE = Regex("\\b[A-Z]-[A-Z]\\b")

        /**
         * Words that are Latin on purpose beyond the product list: folder names,
         * brands, formats, card schemes and library names.
         */
        val EXTRA_LATIN_ALLOWED = listOf(
            "Documents", "Files", "Android", "Cybium", "Drive", "Power", "Pack", "KB", "MB", "GB",
            "EXIF", "PAN", "VID", "IBAN", "SSN", "Wi-Fi", "vCard", "DOCX", "XLSX", "PPTX", "txt",
            "docx", "ML", "Kit", "Apache", "Material", "Components", "PdfBox", "kotlinx", "coroutines",
        )

        /** Product, format and product-UI names that stay Latin in Hindi. */
        val LATIN_ALLOWED = listOf(
            "ScanWhirl", "Pro", "PDF", "PDF/A", "Word", "Excel", "PowerPoint", "QR", "ZIP",
            "JPG", "PNG", "JPEG", "AES-256", "AES", "PIN", "ID", "Google", "Play", "Downloads",
            "OCR", "DPI", "px", "A4", "Letter", "PRO",
        ) + EXTRA_LATIN_ALLOWED

        val LATIN_ALLOWED_PATTERNS = LATIN_ALLOWED
            .sortedByDescending { it.length }
            .map { Regex("(?<![A-Za-z])" + Regex.escape(it) + "(?![A-Za-z])") }

        /** Common English UI words that must not survive inside a Hindi value. */
        val ENGLISH_UI_WORDS = listOf(
            "a", "the", "to", "not", "could", "delete", "save", "move", "moved", "lock", "unlock",
            "drag", "reorder", "scan", "page", "pages", "share", "open", "close", "cancel", "done",
            "error", "failed", "file", "files", "image", "images", "add", "apply", "pick", "choose",
            "select", "merge", "compress", "resize", "rotate", "crop", "copy", "text", "printed",
            "best", "works", "extracting", "copied", "clipboard", "unsaved", "changes", "this",
            "that", "these", "those", "you", "your", "we", "our", "is", "are", "was", "were", "has",
            "have", "had", "been", "nothing", "something", "first", "least", "more", "up", "down",
            "into", "from", "with", "for", "and", "or", "of", "on", "at", "in", "by", "as", "so",
            "then", "than", "it", "its", "can", "cannot", "will", "would", "should", "may", "might",
            "must", "do", "does", "did", "here", "there", "when", "what", "which", "who", "how",
            "why", "all", "any", "some", "new", "old", "use", "used", "using", "keep", "keeps",
            "just", "only", "also", "before", "after", "next", "last", "same", "other", "most",
            "much", "many", "few", "every", "each", "both", "either", "neither", "about", "over",
            "under", "again", "still", "yet", "ever", "never", "always", "often", "sometimes",
            "usually",
        )

        val ENGLISH_WORD = Regex(
            "(?<![A-Za-z])(?:" +
                ENGLISH_UI_WORDS.sortedByDescending { it.length }.joinToString("|") +
                ")(?![A-Za-z])",
            RegexOption.IGNORE_CASE
        )
    }
}
