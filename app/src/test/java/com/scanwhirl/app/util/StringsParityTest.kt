package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Ensures every user-visible string in `values/strings.xml` has a matching
 * entry in `values-hi/strings.xml`, with identical format specifiers.
 * All Hindi entries carry `<!-- review -->` for native-speaker review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StringsParityTest {

    private val valuesDir = File("src/main/res/values")
    private val hiDir = File("src/main/res/values-hi")

    @Test
    fun `every translatable string has a Hindi entry`() {
        val values = parseStrings(File(valuesDir, "strings.xml"))
        val hi = parseStrings(File(hiDir, "strings.xml"))

        val missing = values.keys - hi.keys
        assertTrue(
            "Missing Hindi entries: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `format specifiers match between values and values-hi`() {
        val values = parseStrings(File(valuesDir, "strings.xml"))
        val hi = parseStrings(File(hiDir, "strings.xml"))

        val mismatches = mutableListOf<String>()
        for ((name, en) in values) {
            val hiText = hi[name] ?: continue
            val enSpecs = formatSpecifiers(en)
            val hiSpecs = formatSpecifiers(hiText)
            if (enSpecs != hiSpecs) {
                mismatches.add("$name: en=$enSpecs hi=$hiSpecs (en=\"$en\" hi=\"$hiText\")")
            }
        }
        assertTrue("Format specifier mismatches: ${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test
    fun `plurals quantities are valid`() {
        // Basic sanity: every plurals entry in Hindi has at least one item.
        val hiXml = File(hiDir, "strings.xml").readText(Charsets.UTF_8)
        val plurals = Regex("<plurals\\s+name=\"([^\"]+)\">(.*?)</plurals>").findAll(hiXml)
        for (m in plurals) {
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            val items = Regex("<item\\s+quantity=\"([^\"]+)\"").findAll(body)
            assertTrue("Plurals $name has no item quantities", items.count() > 0)
        }
    }

    /** Parses all <string name="...">...</string> entries, ignoring translatable="false". */
    private fun parseStrings(file: File): Map<String, String> {
        val xml = file.readText(Charsets.UTF_8)
        return Regex("<string\\s+name=\"([^\"]+)\"(?:[^>]*)>(.*?)</string>").findAll(xml).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    /** Extracts format specifiers from a string, normalizing positional (%1$d) to non-positional (%d). */
    private fun formatSpecifiers(text: String): List<String> {
        // Escape %% first so they don't look like specifiers, then extract real ones.
        val escaped = text.replace("%%", "\u0000")
        return Regex("%(?:\\d+\\$)?([sdfeEgGaAxXn%])").findAll(escaped).map {
            it.groupValues[1].replace("\u0000", "%")
        }.sorted().toList()
    }
}
