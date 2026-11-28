package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ensures every translatable string AND plural across ALL `values/strings*.xml` files has a
 * matching entry in `values-hi/strings*.xml`, with identical format specifiers.
 *
 * C2 item 4: format specifiers are compared by POSITION AND TYPE. `%1$s` and `%1$d` differ;
 * `%d` and `%1$d` are the same single argument. Reordering placeholders between languages is
 * fine (the result is sorted by position), but changing a type or an argument index is not.
 * Entries marked `translatable="false"` are excluded on both sides.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StringsParityTest {

    private val valuesDir = File("src/main/res/values")
    private val hiDir = File("src/main/res/values-hi")

    private fun entries(dir: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
            .orEmpty().sortedBy { it.name }
        for (file in files) {
            val xml = file.readText(Charsets.UTF_8).replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            for (m in Regex("<string\\s+([^>]*)>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val attrs = m.groupValues[1]
                val name = Regex("name=\"([^\"]+)\"").find(attrs)?.groupValues[1] ?: continue
                if (attrs.contains("translatable=\"false\"")) continue
                out["string:$name"] = m.groupValues[2]
            }
            for (m in Regex("<plurals\\s+([^>]*)>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val attrs = m.groupValues[1]
                val name = Regex("name=\"([^\"]+)\"").find(attrs)?.groupValues[1] ?: continue
                if (attrs.contains("translatable=\"false\"")) continue
                for (item in Regex(
                    "<item\\s+quantity=\"([^\"]+)\"\\s*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL
                ).findAll(m.groupValues[2])) {
                    out["plurals:$name[${item.groupValues[1]}]"] = item.groupValues[2]
                }
            }
        }
        return out
    }

    @Test
    fun `every translatable string and plural has a Hindi entry`() {
        val values = entries(valuesDir)
        val hi = entries(hiDir)

        val missing = (values.keys - hi.keys).sorted()
        val extra = (hi.keys - values.keys).sorted()
        assertTrue(
            "Missing Hindi entries: ${missing.joinToString(", ")}\n" +
                "Hindi entries with no base: ${extra.joinToString(", ")}",
            missing.isEmpty() && extra.isEmpty()
        )
    }

    /**
     * B3/B4 deliberately changed the Tools copy: three named student jobs were added and
     * "Redact" became "Hide info". The generic parity loops above cannot tell a renamed
     * string from a missing one, so pin both languages' slots and the natural Hindi wording.
     */
    @Test
    fun `student job words and the renamed Hide info tile exist in both languages`() {
        val en = File(valuesDir, "strings_l1.xml").readText(Charsets.UTF_8)
        val hi = File(hiDir, "strings_l1.xml").readText(Charsets.UTF_8)
        val keys = listOf(
            "student_job_form_ready", "student_job_sign", "student_job_hide_info", "tool_redact"
        )
        for (key in keys) {
            assertTrue("English $key is missing", Regex("name=\"$key\"").containsMatchIn(en))
            assertTrue("Hindi $key is missing", Regex("name=\"$key\"").containsMatchIn(hi))
        }
        assertTrue("the tile reads Hide info", Regex("name=\"tool_redact\">Hide info<").containsMatchIn(en))
        assertTrue(
            "Hindi already says the plain thing naturally",
            Regex("name=\"tool_redact\">[^<]*छिपाएँ<").containsMatchIn(hi)
        )
        assertTrue(
            "the old word is gone from the tile",
            !Regex("name=\"tool_redact\">Redact<").containsMatchIn(en)
        )
    }

    @Test
    fun `format specifiers match by position and type across all string files`() {
        val values = entries(valuesDir)
        val hi = entries(hiDir)

        val mismatches = mutableListOf<String>()
        for (name in (values.keys intersect hi.keys).sorted()) {
            val enSpecs = formatSpecifiers(values.getValue(name))
            val hiSpecs = formatSpecifiers(hi.getValue(name))
            if (enSpecs != hiSpecs) {
                mismatches.add(
                    "$name: en=$enSpecs hi=$hiSpecs " +
                        "(en=\"${values.getValue(name)}\" hi=\"${hi.getValue(name)}\")"
                )
            }
        }
        assertTrue(
            "Format specifier mismatches:\n${mismatches.joinToString("\n")}",
            mismatches.isEmpty()
        )
    }

    @Test
    fun `plurals quantities are valid`() {
        for (dir in listOf(valuesDir, hiDir)) {
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
                .orEmpty()
            for (file in files) {
                val xml = file.readText(Charsets.UTF_8).replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                for (m in Regex("<plurals\\s+[^>]*>(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                    val items = Regex("<item\\s+quantity=\"([^\"]+)\"").findAll(m.groupValues[1])
                    assertTrue("Plurals in ${file.name} has no item quantities", items.count() > 0)
                }
            }
        }
    }

    // ---- the comparison rule itself ---------------------------------------------------

    @Test
    fun `a type change at the same position is a mismatch`() {
        assertFalse(same("%1\$s", "%1\$d"))
        assertFalse(same("%1\$d", "%1\$s"))
    }

    @Test
    fun `an implicit single argument equals its positional form`() {
        assertTrue(same("%d", "%1\$d"))
        assertTrue(same("%s", "%1\$s"))
        assertTrue(same("Read %d pages", "Read %1\$d pages"))
    }

    @Test
    fun `a different argument index is a mismatch but reordering is not`() {
        assertFalse(same("%1\$s", "%2\$s"))
        assertFalse(same("%1\$d %2\$s", "%1\$d"))
        // Hindi may reorder placeholders; same positions and types still match.
        assertTrue(same("%1\$s and %2\$s", "%2\$s और %1\$s"))
    }

    @Test
    fun `a literal percent is not a format specifier`() {
        assertTrue(formatSpecifiers("100%% done").isEmpty())
        assertTrue(formatSpecifiers("%%1\$d").isEmpty())
        assertEquals(listOf(1 to 'd'), formatSpecifiers("50%% done %1\$d"))
    }

    private fun same(en: String, hi: String) = formatSpecifiers(en) == formatSpecifiers(hi)

    /** A specifier is (argument position, conversion type); implicit positions count up from 1. */
    private data class Spec(val index: Int, val type: Char)

    private fun formatSpecifiers(text: String): List<Pair<Int, Char>> {
        val specs = mutableListOf<Spec>()
        val conversions = "sdfeEgGaAxXn%"
        var implicit = 1
        var i = 0
        while (i < text.length) {
            if (text[i] != '%') {
                i++
                continue
            }
            if (i + 1 < text.length && text[i + 1] == '%') {
                i += 2 // %% is a literal percent, not a specifier
                continue
            }
            var j = i + 1
            val digitsStart = j
            while (j < text.length && text[j].isDigit()) j++
            val explicit: Int? =
                if (j < text.length && text[j] == '$' && j > digitsStart) {
                    text.substring(digitsStart, j).toIntOrNull().also { j++ }
                } else {
                    j = i + 1
                    null
                }
            while (j < text.length && text[j] !in conversions) j++
            if (j < text.length) {
                val type = text[j]
                if (type != '%') {
                    specs += Spec(explicit ?: implicit, type)
                    if (explicit == null) implicit++
                }
                j++
            }
            i = j
        }
        return specs.sortedWith(compareBy<Spec> { it.index }.thenBy { it.type })
            .map { it.index to it.type }
    }
}
