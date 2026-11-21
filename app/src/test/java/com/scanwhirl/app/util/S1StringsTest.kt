package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S1 defects 5-8: new localized copy must be paired in English/Hindi, the cap message must
 * describe the real 60-page camera rule in both languages, and no raw exception text may be
 * interpolated into a user-visible string.
 */
class S1StringsTest {

    private fun names(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun value(file: File, name: String): String =
        Regex("<string\\s+name=\"$name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)?.trim() ?: ""

    @Test
    fun `every new S1 string exists in both languages with matching format placeholders`() {
        val en = File("src/main/res/values/strings_s1.xml")
        val hi = File("src/main/res/values-hi/strings_s1.xml")
        assertTrue("English S1 strings file exists", en.isFile)
        assertTrue("Hindi S1 strings file exists", hi.isFile)
        val enNames = names(en)
        val hiNames = names(hi)
        assertEquals("S1 strings are translated", enNames, hiNames)
        assertTrue("S1 adds localized copy", enNames.isNotEmpty())
        for (name in enNames) {
            val a = value(en, name)
            val b = value(hi, name)
            assertTrue("$name is not empty in English", a.isNotBlank())
            assertTrue("$name is not empty in Hindi", b.isNotBlank())
            assertEquals("$name format placeholders match", specs(a), specs(b))
        }
    }

    @Test
    fun `the camera page cap says the same thing in both languages`() {
        val en = value(File("src/main/res/values/strings.xml"), "cap_scan_message")
        val hi = value(File("src/main/res/values-hi/strings.xml"), "cap_scan_message")
        assertTrue("English names the one-scan rule", en.contains("in one scan", ignoreCase = true))
        assertTrue("Hindi names the one-scan rule", hi.contains("एक स्कैन में"))
        assertEquals("the placeholder matches", specs(en), specs(hi))
        assertFalse("no hardcoded cap in English", en.contains("60"))
        assertFalse("no hardcoded cap in Hindi", hi.contains("60"))
    }

    @Test
    fun `no owned screen interpolates a raw exception message`() {
        val scannerFailed = Regex("getString\\(R\\.string\\.scanner_failed, e\\.message\\)")
        val rawQr = Regex("qr_scanner_error,[\\s\\S]{0,120}?(error|e)\\.message")
        val launcherOnly = listOf(
            Regex("getString\\(R\\.string\\.scan_save_failed_review, e\\.message\\)"),
            Regex("getString\\(R\\.string\\.stage_failed, e\\.message\\)")
        )
        val offenders = mutableListOf<String>()
        for (rel in listOf(
            "src/main/java/com/scanwhirl/app/tools/ScanLauncher.kt",
            "src/main/java/com/scanwhirl/app/ui/DocumentViewerActivity.kt",
            "src/main/java/com/scanwhirl/app/ui/ScanReviewActivity.kt",
            "src/main/java/com/scanwhirl/app/ui/qr/QrScanner.kt"
        )) {
            val text = File(rel).readText()
            for (pattern in listOf(scannerFailed, rawQr)) {
                pattern.findAll(text).forEach { offenders += "$rel: ${it.value.trim()}" }
            }
            if (rel.endsWith("ScanLauncher.kt")) {
                launcherOnly.forEach { pattern ->
                    pattern.findAll(text).forEach { offenders += "$rel: ${it.value.trim()}" }
                }
            }
        }
        assertTrue("raw exception text in localized copy:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    private fun specs(text: String): List<String> =
        Regex("%(?:\\d+\\$)?[sdfeEgGaAxXn]").findAll(text).map { it.value.replace(Regex("\\d+\\$"), "") }.sorted().toList()
}
