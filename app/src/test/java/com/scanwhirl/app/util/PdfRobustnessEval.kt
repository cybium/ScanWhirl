package com.scanwhirl.app.util

import java.io.File
import java.io.RandomAccessFile

/**
 * Host sweep over the owner's hostile PDF corpus (Isartor PDF/A tests, broken files, huge
 * textbooks). Every path we own is wrapped, so a malformed file ends in a classification and a
 * friendly message, never in an escaped exception. Not a JUnit class: the test calls [run] when
 * the owner folder is enabled.
 */
object PdfRobustnessEval {

    enum class Kind { OK, ENCRYPTED, NOT_A_PDF, UNSUPPORTED }

    data class Result(
        val path: String,
        val kind: Kind,
        val message: String,
        val crash: String? = null
    )

    fun run(root: File, out: File): List<Result> {
        out.mkdirs()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("pdf", true) }
            .sortedBy { it.absolutePath }
            .toList()
        val lines = mutableListOf<String>()
        val results = files.map { file ->
            val result = classify(file)
            lines += "${result.kind}\t${result.message}\t${result.path}" +
                (result.crash?.let { "\tCRASH $it" } ?: "")
            result
        }
        val counts = Kind.entries.joinToString { "$it=${results.count { r -> r.kind == it }}" }
        val crashes = results.count { it.crash != null }
        val header = "PDFs=${results.size} crashes=$crashes $counts"
        println("--- PDF robustness: $header ---")
        println(lines.joinToString("\n"))
        File(out, "PDF_RESULTS.txt").writeText(header + "\n" + lines.joinToString("\n") + "\n")
        return results
    }

    private fun classify(file: File): Result {
        val head = readHead(file)
        if (!looksLikePdf(head)) {
            return Result(file.path, Kind.NOT_A_PDF, "This file is not a PDF")
        }
        if (file.length() > MAX_INSPECT_BYTES) {
            return Result(
                file.path, Kind.UNSUPPORTED,
                "This PDF is too large to inspect offline (${file.length() / (1024 * 1024)} MB)"
            )
        }
        val raw = try {
            file.readBytes()
        } catch (e: Exception) {
            return Result(file.path, Kind.UNSUPPORTED, "This PDF could not be read: ${e.message}")
        }

        val encrypted = runCatching { PdfProtection.isEncrypted(file) }
            .getOrElse { return crash(file, it) }
        if (encrypted == true) {
            // The same parse path the import uses, with a deliberately wrong password.
            val outcome = runCatching { PdfDecryptor.decrypt(raw, "not-the-password") }
            val failure = outcome.exceptionOrNull()
            return if (failure is PdfDecryptor.WrongPassword || failure is PdfDecryptor.Unsupported) {
                Result(file.path, Kind.ENCRYPTED, "Encrypted PDF; ScanWhirl asks for the password")
            } else if (failure != null) {
                crash(file, failure)
            } else {
                Result(file.path, Kind.ENCRYPTED, "Encrypted PDF; ScanWhirl asks for the password")
            }
        }

        val objectsCrash = runCatching { PdfEncryptor.objects(raw) }
            .exceptionOrNull()
        if (objectsCrash != null) return crash(file, objectsCrash)

        val verified = runCatching { PdfVerifier.verify(file) }
        val verifyFailure = verified.exceptionOrNull()
        if (verifyFailure != null && verifyFailure !is PdfVerifier.InvalidPdfException &&
            verifyFailure !is java.io.IOException
        ) {
            return crash(file, verifyFailure)
        }

        val decryptCrash = runCatching { PdfDecryptor.decrypt(raw, "anything") }
            .exceptionOrNull()
        if (decryptCrash != null &&
            decryptCrash !is PdfDecryptor.WrongPassword &&
            decryptCrash !is PdfDecryptor.Unsupported
        ) {
            return crash(file, decryptCrash)
        }

        return if (verified.isSuccess) {
            Result(file.path, Kind.OK, "PDF opens and verifies")
        } else {
            Result(
                file.path, Kind.UNSUPPORTED,
                "Valid PDF; this structure is not one ScanWhirl rewrites"
            )
        }
    }

    private fun crash(file: File, error: Throwable): Result =
        Result(
            file.path, Kind.UNSUPPORTED,
            "This PDF could not be inspected: ${error.message ?: error.javaClass.simpleName}",
            crash = error.javaClass.name
        )

    private fun readHead(file: File): ByteArray = try {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(1024L, raf.length()).toInt()
            ByteArray(n).also { raf.readFully(it) }
        }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun looksLikePdf(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1)
        return text.contains("%PDF-")
    }

    private const val MAX_INSPECT_BYTES = 32L * 1024 * 1024
}
