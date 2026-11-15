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
 * Devanagari words inside English words (e.g. "Dचालूe" instead of "Done"). These
 * checks are plain JUnit (no Android runtime needed) and run over every file in
 * `values-hi`, not only `strings.xml`.
 *
 *  - No string may mix Latin and Devanagari letters inside one word.
 *  - Every placeholder in a Hindi string must be the placeholder the English
 *    string of the same name uses, so `getString(...)` can never throw.
 */
class HindiStringsHygieneTest {

    private val valuesDir = File("src/main/res/values")
    private val hiDir = File("src/main/res/values-hi")

    /** Legitimate Latin+Devanagari mixes, by string name, if any turn up later. */
    private val gluedAllowList = emptySet<String>()

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

    /** Escapes such as \n, \t, \' or \" are not letters; hide them before the glued check. */
    private fun unmaskEscapes(text: String): String = ESCAPE.replace(text, " ")

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
    }
}
