package com.scanwhirl.app

import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * T46: every visible string/plural must take the same arguments in English and Hindi.
 *
 * Android selects the Hindi item but the code still passes the argument list the English
 * item declared. A Hindi item that reads `%1$d` where English reads `%1$s` therefore throws
 * IllegalFormatConversionException at runtime (T46: two recorded copies ScanWhirl cannot
 * prove were formatted as `%1$d ... %1$s` with only the names string).
 *
 * For every English entry the test infers one dummy argument per placeholder index (String
 * for %s, Int for %d/%x, Double for %f), formats the English text (so one item that uses two
 * types for one index fails), then formats the matching Hindi entry with the same arguments
 * and compares the placeholder signatures. Each locale is also formatted with its own
 * inferred arguments, so a Hindi-only conflict fails too.
 *
 * A Hindi entry with no English counterpart is skipped: Android falls back to English and
 * parity is a separate concern. A Hindi entry whose English counterpart was not found is
 * never reached with a wrong argument list.
 */
class StringFormatContractTest {

    private val resDir = File("src/main/res")

    private val positioned = Regex("""^%(\d+)\$([a-zA-Z])""")
    private val bare = Regex("""^%([a-zA-Z])""")

    private data class Arg(val index: Int, val type: Char)

    private class Inferred(val args: Array<Any>, val conflict: String?)

    @Test
    fun `every English and Hindi string formats with the English argument list`() {
        val en = read("values")
        val hi = read("values-hi")
        val problems = mutableListOf<String>()

        for ((key, enText) in en) {
            val inferred = infer(enText)
            if (inferred.conflict != null) {
                problems += "${label(key)}: English ${inferred.conflict}\n    EN: $enText"
                continue
            }
            try {
                String.format(Locale.ENGLISH, enText, *inferred.args)
            } catch (e: Exception) {
                problems += "${label(key)}: English does not format: ${e.javaClass.simpleName}\n    EN: $enText"
                continue
            }

            val hiText = hi[key] ?: continue
            val hiSignature = signature(hiText)
            val enSignature = signature(enText)
            if (hiSignature != enSignature) {
                problems += "${label(key)}: English placeholders [$enSignature], Hindi [$hiSignature]" +
                    "\n    EN: $enText\n    HI: $hiText"
                continue
            }
            try {
                String.format(Locale("hi", "IN"), hiText, *inferred.args)
            } catch (e: Exception) {
                problems += "${label(key)}: Hindi does not format with the English arguments: " +
                    "${e.javaClass.simpleName}\n    HI: $hiText"
            }
        }

        for ((key, hiText) in hi) {
            val inferred = infer(hiText)
            if (inferred.conflict != null) {
                problems += "${label(key)}: Hindi ${inferred.conflict}\n    HI: $hiText"
                continue
            }
            try {
                String.format(Locale("hi", "IN"), hiText, *inferred.args)
            } catch (e: Exception) {
                problems += "${label(key)}: Hindi does not format: ${e.javaClass.simpleName}\n    HI: $hiText"
            }
        }

        if (problems.isNotEmpty()) {
            fail("String argument contracts differ between English and Hindi:\n" + problems.joinToString("\n"))
        }
    }

    // ---- placeholders ----------------------------------------------------

    private fun placeholders(text: String): List<Arg> {
        val out = mutableListOf<Arg>()
        var implicit = 1
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') {
                i++
                continue
            }
            if (text.startsWith("%%", i)) {
                i += 2
                continue
            }
            val rest = text.substring(i)
            val p = positioned.find(rest)
            val b = bare.find(rest)
            when {
                p != null -> {
                    out += Arg(p.groupValues[1].toInt(), p.groupValues[2].lowercase()[0])
                    i += p.value.length
                }
                b != null -> {
                    out += Arg(implicit++, b.groupValues[1].lowercase()[0])
                    i += b.value.length
                }
                else -> i++
            }
        }
        return out
    }

    /** Sorted, de-duplicated "index+type" list, so %1$s and %2$s order does not matter. */
    private fun signature(text: String): String =
        placeholders(text).map { "${it.index}${it.type}" }.distinct().sorted().joinToString(",")

    private fun infer(text: String): Inferred {
        val phs = placeholders(text)
        val conflict = phs.groupBy { it.index }.entries
            .firstOrNull { (_, v) -> v.map { it.type }.distinct().size > 1 }
        if (conflict != null) {
            val used = conflict.value.map { "%${it.index}\$${it.type}" }.distinct().joinToString(" and ")
            return Inferred(emptyArray(), "uses $used for one argument")
        }
        val max = phs.maxOfOrNull { it.index } ?: 0
        val args = Array<Any>(max) { "text" }
        for (p in phs) args[p.index - 1] = dummy(p.type)
        return Inferred(args, null)
    }

    private fun dummy(type: Char): Any = when (type) {
        'd', 'x', 'o' -> 1
        'f', 'e', 'g' -> 1.5
        else -> "text"
    }

    private fun label(key: Pair<String, String?>): String =
        if (key.second == null) key.first else "${key.first}[${key.second}]"

    // ---- reading ---------------------------------------------------------

    private fun read(locale: String): Map<Pair<String, String?>, String> {
        val dir = File(resDir, locale)
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) fail("No XML files found in ${dir.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        val out = LinkedHashMap<Pair<String, String?>, String>()
        for (file in files!!) {
            val root = factory.newDocumentBuilder().parse(file).documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isEmpty()) continue
                when (el.tagName) {
                    "string" -> out[name to null] = el.textContent
                    "plurals" -> {
                        val items = el.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            out[name to item.getAttribute("quantity")] = item.textContent
                        }
                    }
                }
            }
        }
        return out
    }
}
