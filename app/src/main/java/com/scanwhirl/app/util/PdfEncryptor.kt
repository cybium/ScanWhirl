package com.scanwhirl.app.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Adds password protection to a ScanWhirl PDF using the modern standard handler:
 * **ISO 32000-2 V5 / R6, AESV3 (AES-256)** — see [PdfAes]. RC4 is no longer written; old
 * RC4-protected files still open in any PDF viewer and the old unlock path is unchanged.
 *
 * AES changes stream sizes (16-byte IV + PKCS#7 padding), so a protected file cannot be patched in
 * place: every object is copied, each stream body is re-encrypted and `/Length` is rewritten, and a
 * fresh xref table and trailer (`/Encrypt`, `/ID`) are produced. Page images stay byte-identical
 * once decrypted — only the PDF wrapper changes.
 *
 * @throws IOException when the source is not a PDF this writer understands, when the output fails
 *   its own structural verification, or when it cannot be published.
 */
object PdfEncryptor {

    /** One in-use object of a generator-written PDF: byte region `[start, end)`. */
    internal class Obj(val number: Int, val start: Int, val end: Int)

    /**
     * Writes [dst] as an AES-256 protected copy of [src]. Output goes to `<dst name>.tmp`, is
     * fsynced and structurally verified (with decryption) before the rename, so a failure leaves
     * no partial file behind.
     */
    fun encrypt(src: File, dst: File, userPassword: String) {
        require(src.exists() && src.length() > 0) { "PDF is empty" }
        require(userPassword.isNotEmpty()) { "Password must not be empty" }
        val raw = src.readBytes()
        val encryption = PdfAes.create(userPassword)
        // The same key the reader will derive: fail loudly instead of publishing a file nobody opens.
        val derived = PdfAes.userKey(userPassword, encryption.u, encryption.ue)
        if (derived == null || !derived.contentEquals(encryption.fileKey)) {
            throw IOException("The password could not be verified before writing")
        }

        val protectedBytes = rewrite(raw, encryption)

        val parent = dst.absoluteFile.parentFile ?: throw IOException("No folder for ${dst.name}")
        parent.mkdirs()
        val tmp = File(parent, dst.name + ".tmp")
        try {
            tmp.writeBytes(protectedBytes)
            Durable.syncFile(tmp)
            PdfVerifier.verify(tmp, decryption = encryption)
            if (!tmp.renameTo(dst)) throw IOException("Could not publish ${dst.name}")
            Durable.syncDir(parent)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Rewrites a plain generator PDF with [encryption] applied to every stream. Object boundaries
     * come from the source xref table (never from marker scanning inside binary data), so a JPEG
     * that happens to contain `endstream` cannot confuse it.
     */
    internal fun rewrite(raw: ByteArray, encryption: PdfAes.Encryption): ByteArray {
        val objects = objects(raw)
        if (objects.isEmpty()) throw IOException("Not a PDF ScanWhirl can protect")

        val latin = String(raw, Charsets.ISO_8859_1)
        val firstObject = objects.minOf { it.start }
        val out = ByteArrayOutputStream(raw.size + objects.size * 96 + 4096)
        out.write(raw, 0, firstObject)

        val offsets = HashMap<Int, Int>()
        for (obj in objects.sortedBy { it.start }) {
            offsets[obj.number] = out.size()
            val marker = latin.indexOf(">>\nstream\n", obj.start)
            if (marker < 0 || marker >= obj.end) {
                // A dictionary or the page tree: copied untouched.
                out.write(raw, obj.start, obj.end - obj.start)
                continue
            }
            val dictEnd = marker + ">>\nstream\n".length
            if (dictEnd > obj.end) throw IOException("Object ${obj.number} has a broken dictionary")
            val dictText = latin.substring(obj.start, dictEnd)
            val lengthMatch = Regex("/Length\\s+(\\d+)").find(dictText)
                ?: throw IOException("Stream ${obj.number} has no /Length")
            val oldLength = lengthMatch.groupValues[1].toIntOrNull()
                ?: throw IOException("Stream ${obj.number} has an invalid /Length")
            val bodyEnd = dictEnd + oldLength
            if (bodyEnd > obj.end) throw IOException("Stream ${obj.number} runs past its object")
            val plain = raw.copyOfRange(dictEnd, bodyEnd)
            val encrypted = encryption.encryptStream(plain)
            val newDict = dictText.substring(0, lengthMatch.range.first) +
                "/Length ${encrypted.size}" +
                dictText.substring(lengthMatch.range.last + 1)
            out.write(newDict.toByteArray(Charsets.ISO_8859_1))
            out.write(encrypted)
            out.write(raw, bodyEnd, obj.end - bodyEnd)
        }

        val xrefOffset = out.size()
        val size = objects.maxOf { it.number } + 1
        val xref = StringBuilder()
        xref.append("xref\n0 $size\n0000000000 65535 f \n")
        for (number in 1 until size) {
            val offset = offsets[number]
            if (offset == null) {
                xref.append("0000000000 00000 f \n")
            } else {
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
            }
        }
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))

        val root = Regex("/Root (\\d+) 0 R").find(trailerDictionary(latin))
            ?.groupValues?.get(1)
            ?: throw IOException("The PDF trailer has no /Root")
        val id = PdfAes.hex(encryption.fileId)
        val tail = "trailer\n<< /Size $size /Root $root 0 R /Encrypt ${encryption.encryptDict()} " +
            "/ID [<$id> <$id>] >>\nstartxref\n$xrefOffset\n%%EOF\n"
        out.write(tail.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Minimal parsing of generator-written PDFs (classic xref table)
    // ------------------------------------------------------------------

    /** In-use object regions, in file order, read through the xref table. */
    internal fun objects(raw: ByteArray): List<Obj> {
        val latin = String(raw, Charsets.ISO_8859_1)
        val xrefOffset = xrefOffset(latin) ?: return emptyList()
        val entries = xrefEntries(latin, xrefOffset) ?: return emptyList()
        val sorted = entries.sortedBy { it.second }
        return sorted.mapIndexed { i, (number, offset) ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].second else xrefOffset
            Obj(number, offset.toInt(), end.toInt())
        }.filter { it.start in 0 until it.end && it.end <= raw.size }
    }

    /**
     * Every stream body with its object number (data range inclusive), for verification and tests.
     * Same xref walk as [objects]; the region is then split on `>>\nstream\n` and `/Length`.
     */
    internal fun objectStreamBodies(raw: ByteArray): List<Pair<Int, IntRange>> {
        val latin = String(raw, Charsets.ISO_8859_1)
        val ranges = mutableListOf<Pair<Int, IntRange>>()
        for (obj in objects(raw)) {
            val marker = latin.indexOf(">>\nstream\n", obj.start)
            if (marker < 0 || marker >= obj.end) continue
            val dictEnd = marker + ">>\nstream\n".length
            val dictText = latin.substring(obj.start, dictEnd)
            val length = Regex("/Length\\s+(\\d+)").find(dictText)
                ?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (dictEnd + length > obj.end) continue
            ranges += obj.number to (dictEnd until dictEnd + length)
        }
        return ranges
    }

    private fun xrefOffset(latin: String): Int? {
        val marker = "startxref\n"
        val at = latin.lastIndexOf(marker)
        if (at < 0) return null
        var end = at + marker.length
        while (end < latin.length && latin[end].isDigit()) end++
        return latin.substring(at + marker.length, end).toIntOrNull()
    }

    /** `(object number, offset)` for every in-use entry; null when the table is not the writer's shape. */
    private fun xrefEntries(latin: String, xrefOffset: Int): List<Pair<Int, Long>>? {
        if (xrefOffset <= 0 || xrefOffset >= latin.length) return null
        var p = latin.indexOf('\n', xrefOffset) + 1 // past "xref\n"
        val countLineEnd = latin.indexOf('\n', p)
        if (p <= 0 || countLineEnd < 0) return null
        val count = latin.substring(p, countLineEnd).trim().split(' ').lastOrNull()?.toIntOrNull()
            ?: return null
        p = countLineEnd + 1
        val out = mutableListOf<Pair<Int, Long>>()
        for (i in 0 until count) {
            if (p + 20 > latin.length) return null
            val entry = latin.substring(p, p + 20)
            val inUse = entry[17] == 'n'
            if (i > 0 && inUse) {
                val offset = entry.substring(0, 10).toLongOrNull() ?: return null
                out += i to offset
            }
            p += 20
        }
        return out
    }

    /** The source trailer dictionary, between `trailer` and `startxref`. */
    private fun trailerDictionary(latin: String): String {
        val at = latin.lastIndexOf("\ntrailer\n")
        if (at < 0) return ""
        val from = at + "\ntrailer\n".length
        val to = latin.indexOf("\nstartxref\n", from)
        return if (to < 0) latin.substring(from) else latin.substring(from, to)
    }
}
