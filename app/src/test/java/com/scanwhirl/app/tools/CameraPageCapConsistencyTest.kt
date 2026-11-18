package com.scanwhirl.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T19: [ScanLauncher.CAMERA_PAGE_LIMIT] is the only page cap a *camera* session may ask for.
 *
 * T15 raised the camera cap to 60 for the Home scan, but the second-batch camera entries
 * (Review "Add page(s)", viewer "Insert pages -> camera", Book pages "Scan with camera")
 * kept their own hardcoded 30, so the same phone accepted 60 pages on one screen and 30 on
 * the next. This guard reads the shipped sources and fails with file:line whenever a
 * `setPageLimit(...)` / `pageLimit = ...` asks for a number the camera cap does not govern.
 *
 * Deliberately *not* covered: gallery entries ([launchGalleryCrop] / `galleryOptions`) keep
 * their own per-entry limits; a gallery import is a different session with a different job.
 *
 * Single-card tools (ID card, business card) capture one page per session by design; they are
 * listed in [notTheCameraCap] with the reason, so adding another exception is a visible,
 * reviewable edit rather than a silent 30.
 *
 * Complements [CameraPageCapTest] (behaviour: 60 shows the calm prompt) with the source
 * invariant: no camera path can drift back to its own number.
 */
class CameraPageCapConsistencyTest {

    /** One `setPageLimit(..)` call or `pageLimit = ..` assignment in the shipped sources. */
    private class Hit(val file: File, val line: Int, val call: String, val arg: String) {
        override fun toString() = "${file.name}:$line: $call($arg)"
    }

    /** An exception to the one-cap rule, with the reason a reviewer can check. */
    private class Allowed(val file: String, val call: String, val arg: String, val why: String)

    /**
     * Page limits that are intentionally not the camera document cap. Every entry is a
     * single-card session (one page per capture), never a multi-page document scan.
     */
    private val notTheCameraCap = listOf(
        Allowed(
            "IdCardActivity.kt", "setPageLimit", "1",
            "an ID card session captures one page (front or back), not a document"
        ),
        Allowed(
            "BusinessCardActivity.kt", "setPageLimit", "1",
            "a business card session captures one page, not a document"
        )
    )

    /**
     * Limits derived from a config instead of a literal. They stay legal only in
     * ScanLauncher.kt, where the config is built: the camera config must take
     * [ScanLauncher.CAMERA_PAGE_LIMIT] (checked below) and the gallery config keeps its
     * per-entry `maxPages`.
     */
    private val configDerived = listOf(
        Allowed("ScanLauncher.kt", "setPageLimit", "cfg.pageLimit", "the builder reads its config"),
        Allowed("ScanLauncher.kt", "pageLimit", "CAMERA_PAGE_LIMIT", "the camera config takes the cap"),
        Allowed("ScanLauncher.kt", "pageLimit", "maxPages.coerceIn(1, 100)", "the gallery config")
    )

    /** The camera entries that add pages to an existing document; all must take the cap. */
    private val cameraAddPagesFiles = listOf(
        "ScanReviewActivity.kt",
        "DocumentViewerActivity.kt",
        "BookPagesActivity.kt"
    )

    // ------------------------------------------------------------------ the guard

    @Test
    fun `the camera cap is still 60`() {
        assertEquals("one camera session stops at 60 pages", 60, ScanLauncher.CAMERA_PAGE_LIMIT)
    }

    @Test
    fun `every camera add pages session uses the shared cap`() {
        val hits = pageLimitHits()
        for (name in cameraAddPagesFiles) {
            val inFile = hits.filter { it.file.name == name && it.call == "setPageLimit" }
            assertTrue(
                "$name no longer opens a camera session with setPageLimit — this guard would " +
                    "be testing nothing; update the guard with the new entry point",
                inFile.isNotEmpty()
            )
            inFile.forEach { hit ->
                assertEquals(
                    "$hit hardcodes its own camera page limit; it must use " +
                        "ScanLauncher.CAMERA_PAGE_LIMIT so one constant governs every camera session",
                    "ScanLauncher.CAMERA_PAGE_LIMIT",
                    hit.arg
                )
            }
        }
    }

    @Test
    fun `no camera session asks for a page limit other than the cap`() {
        val offenders = pageLimitHits().filter { hit ->
            val name = hit.file.name
            val symbolic = hit.call == "setPageLimit" && hit.arg == "ScanLauncher.CAMERA_PAGE_LIMIT"
            val documented = (notTheCameraCap + configDerived).any {
                it.file == name && it.call == hit.call && it.arg == hit.arg
            }
            !symbolic && !documented
        }
        assertTrue(
            "a camera session asks for its own page limit instead of ScanLauncher.CAMERA_PAGE_LIMIT " +
                "(one constant governs every camera session; single-card or gallery entries must be " +
                "documented in notTheCameraCap):\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the guard reads the shipped sources`() {
        val hits = pageLimitHits()
        assertTrue(
            "no page limits found — the guard is not testing anything:\n${hits.joinToString("\n")}",
            hits.count { it.call == "setPageLimit" } >= 6
        )
        assertTrue(
            "the camera config must take the cap:\n${hits.joinToString("\n")}",
            hits.any { it.file.name == "ScanLauncher.kt" && it.call == "pageLimit" && it.arg == "CAMERA_PAGE_LIMIT" }
        )
        for (name in cameraAddPagesFiles) {
            assertTrue("$name is missing from the source set", hits.any { it.file.name == name })
        }
    }

    // ------------------------------------------------------------------ source scan

    private fun moduleDir(): File {
        val local = File("src/main")
        if (local.isDirectory) return local
        val rooted = File("app/src/main")
        check(rooted.isDirectory) { "src/main not found from ${File(".").absolutePath}" }
        return rooted
    }

    private fun kotlinSources(dir: File, into: MutableList<File> = mutableListOf()): List<File> {
        dir.listFiles()?.forEach { f ->
            when {
                f.isDirectory -> kotlinSources(f, into)
                f.extension == "kt" -> into += f
            }
        }
        return into
    }

    private val setPageLimitCall = Regex("""setPageLimit\s*\(([^)\n]*)\)""")
    private val pageLimitAssign = Regex("""(?<![\w.])pageLimit\s*=\s*([^\n]*)""")

    private fun pageLimitHits(): List<Hit> {
        val root = moduleDir()
        val files = kotlinSources(File(root, "java")) +
            File(root, "kotlin").takeIf { it.isDirectory }?.let { kotlinSources(it) }.orEmpty()
        assertTrue("no kotlin sources under $root", files.isNotEmpty())

        val hits = mutableListOf<Hit>()
        for (file in files) {
            for ((number, code) in codeLines(file)) {
                setPageLimitCall.findAll(code).forEach {
                    hits += Hit(file, number, "setPageLimit", it.groupValues[1].trim())
                }
                pageLimitAssign.findAll(code).forEach {
                    hits += Hit(file, number, "pageLimit", it.groupValues[1].trim().trimEnd(',').trim())
                }
            }
        }
        return hits
    }

    /**
     * The file's lines with comments removed, so an example in a KDoc never counts as shipped
     * code. String and character literals are skipped, so a MIME wildcard or a double slash
     * inside a string cannot be mistaken for a comment. Line numbers are kept for the message.
     */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val text = file.readText(Charsets.UTF_8)
        val out = StringBuilder(text.length)
        var i = 0
        var lineComment = false
        var blockComment = false
        var string: Char? = null // the closing delimiter while inside a literal, null otherwise
        var raw = false // inside a """...""" literal: a single quote does not close it
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                lineComment -> {
                    if (c == '\n') {
                        lineComment = false
                        out.append('\n')
                    }
                    i++
                }
                blockComment -> {
                    if (c == '*' && next == '/') {
                        blockComment = false
                        i += 2
                    } else {
                        if (c == '\n') out.append('\n')
                        i++
                    }
                }
                string != null -> when {
                    raw && text.startsWith("\"\"\"", i) -> {
                        out.append("\"\"\"")
                        i += 3
                        string = null
                        raw = false
                    }
                    !raw && c == '\\' && i + 1 < text.length -> {
                        out.append(c).append(text[i + 1])
                        i += 2
                    }
                    !raw && c == string -> {
                        out.append(c)
                        i++
                        string = null
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
                text.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    string = '"'
                    raw = true
                }
                c == '"' || c == '\'' -> {
                    string = c
                    out.append(c)
                    i++
                }
                c == '/' && next == '/' -> {
                    lineComment = true
                    i += 2
                }
                c == '/' && next == '*' -> {
                    blockComment = true
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString().split('\n').mapIndexed { index, line ->
            index + 1 to line.trimEnd('\r')
        }
    }
}
