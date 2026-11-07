package com.scanwhirl.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * test26 T1.5 — the emoji guard (N7 / R8: "no emoji anywhere; icons are
 * vectors").
 *
 * test25 put Unicode emoji in the greeting and the Pro panel copy; they render
 * with the phone's emoji font and clash with the cyan line-art style (and are
 * English-only by nature). This test scans every shipped string resource and
 * every `src/main` Kotlin file for the emoji code-point ranges the plan
 * defines and fails with file:line for each hit.
 *
 * Code points are walked with [String.codePointAt] — naive char iteration
 * would see only surrogates for the U+1Fxxx ranges and never fire.
 */
class EmojiGuardTest {

    private fun moduleDir(): File {
        val local = File("src/main")
        if (local.isDirectory) return local
        val rooted = File("app/src/main")
        check(rooted.isDirectory) { "src/main not found from ${File(".").absolutePath}" }
        return rooted
    }

    private fun isEmoji(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0x2B50

    @Test
    fun `no emoji in string resources or kotlin sources`() {
        val root = moduleDir()
        val files = buildList {
            File(root, "res").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("values") }
                ?.forEach { dir ->
                    dir.listFiles()?.filter { it.extension == "xml" }?.forEach { add(it) }
                }
            fun kotlinFiles(dir: File) {
                dir.listFiles()?.forEach { f ->
                    when {
                        f.isDirectory -> kotlinFiles(f)
                        f.extension == "kt" -> add(f)
                    }
                }
            }
            File(root, "java").takeIf { it.isDirectory }?.let { kotlinFiles(it) }
            File(root, "kotlin").takeIf { it.isDirectory }?.let { kotlinFiles(it) }
        }
        assertTrue("no sources found under $root — guard is not testing anything", files.isNotEmpty())

        val offenders = mutableListOf<String>()
        for (file in files) {
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                var i = 0
                while (i < line.length) {
                    val cp = line.codePointAt(i)
                    if (isEmoji(cp)) {
                        offenders += "${file.relativeTo(root)}:${index + 1}: U+${cp.toString(16)}"
                        break // one report per line is enough
                    }
                    i += Character.charCount(cp)
                }
            }
        }
        assertTrue(
            "emoji found in shipped text (N7 — use vector icons, not emoji):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
