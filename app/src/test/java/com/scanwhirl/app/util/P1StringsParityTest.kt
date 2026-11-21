package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lane P1: every new string must exist in both locales with matching formats. */
class P1StringsParityTest {

    @Test fun `English and Hindi lane strings stay in step`() {
        val enFile = File("src/main/res/values/strings_p1.xml")
        val hiFile = File("src/main/res/values-hi/strings_p1.xml")
        assertTrue("missing ${enFile.path}", enFile.isFile)
        assertTrue("missing ${hiFile.path}", hiFile.isFile)

        val en = parse(enFile)
        val hi = parse(hiFile)
        assertTrue("empty string file", en.isNotEmpty())
        assertEquals("missing Hindi entries", emptySet<String>(), en.keys - hi.keys)
        assertEquals("extra Hindi entries", emptySet<String>(), hi.keys - en.keys)

        val devanagari = Regex("[\\u0900-\\u097F]")
        en.forEach { (name, text) ->
            assertEquals("format specifiers for $name", specs(text), specs(hi[name]!!))
            assertTrue("Hindi entry for $name must actually be Hindi", devanagari.containsMatchIn(hi[name]!!))
        }
    }

    private fun parse(file: File): Map<String, String> =
        Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>")
            .findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun specs(text: String): List<String> =
        Regex("%(?:\\d+\\$)?([sdfeEgGaAxXn%])")
            .findAll(text.replace("%%", ""))
            .map { it.groupValues[1] }
            .toList()
            .sorted()
}
