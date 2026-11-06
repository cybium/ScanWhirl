package com.scanwhirl.app.util

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Password-protects ScanWhirl's own PDFs using the PDF 1.4 standard security
 * handler, Revision 3 (RC4-128). RC4 is a stream cipher, so ciphertext length
 * equals plaintext length: stream lengths and the xref offsets of the
 * generated PDF stay valid — only stream *bodies* are rewritten and the
 * trailer gains /Encrypt and /ID.
 *
 * Honest caveat (also shown in the UI): RC4-128 is the widely-compatible
 * legacy handler — it stops casual readers, it is not strong cryptography.
 */
object PdfEncryptor {

    /** Fixed padding string from the PDF spec (§7.6.3.1, Algorithm 1 step 1). */
    private val PAD = byteArrayOf(
        0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(),
        0x75.toByte(), 0x8A.toByte(), 0x41.toByte(), 0x64.toByte(), 0x00, 0x4E.toByte(),
        0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08, 0x2E.toByte(), 0x2E.toByte(),
        0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
        0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(),
        0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
    )

    private const val KEY_LEN = 16 // 128-bit file key
    private const val P_DEFAULT = 0xFFFFF2C0.toInt() // print allowed; copy/edit not

    fun encrypt(src: File, dst: File, userPassword: String) {
        require(src.exists() && src.length() > 0) { "PDF is empty" }
        val raw = src.readBytes()

        val paddedUser = pad(userPassword.toByteArray(Charsets.ISO_8859_1))
        val paddedOwner = paddedUser // owner key derives from the user password here
        val fileId = newFileId()

        val o = computeOwnerEntry(paddedOwner, paddedUser)
        val fileKey = computeFileKey(paddedUser, o, P_DEFAULT, fileId)
        val u = computeUserEntry(fileKey, fileId)

        // Encrypt every stream body with its own object key (length-preserving).
        objectStreamBodies(raw).forEach { (objNum, range) ->
            arc4Into(objectKey(fileKey, objNum), raw, range.first, range.last)
        }

        // Swap the trailer for one carrying /Encrypt and /ID.
        val patched = replaceTrailer(raw, o, u, fileId)
        dst.parentFile?.mkdirs()
        dst.writeBytes(patched)
        require(dst.length() > 0) { "Encrypted PDF write produced an empty file" }
    }

    // ------------------------------------------------------------------
    // RC4 (pure Kotlin — Android does not reliably expose an RC4 cipher)
    // ------------------------------------------------------------------

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = data.copyOf()
        var i = 0
        j = 0
        for (k in out.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            out[k] = (out[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }

    /** RC4-encrypts [data.size] bytes of [raw] in place starting at [offset]. */
    private fun arc4Into(key: ByteArray, raw: ByteArray, offset: Int, endInclusive: Int) {
        val slice = rc4(key, raw.copyOfRange(offset, endInclusive + 1))
        slice.copyInto(raw, offset)
    }

    private fun objectKey(fileKey: ByteArray, objNum: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(
            (objNum and 0xFF).toByte(),
            ((objNum shr 8) and 0xFF).toByte(),
            ((objNum shr 16) and 0xFF).toByte()
        ))
        md.update(byteArrayOf(0, 0)) // generation 0
        return md.digest().copyOf(KEY_LEN)
    }

    /** MD5 of [seed], re-hashed 50 times (Revision 3 loves this). */
    private fun mdx50(seed: ByteArray): ByteArray {
        var digest = MessageDigest.getInstance("MD5").digest(seed)
        repeat(50) { digest = MessageDigest.getInstance("MD5").digest(digest) }
        return digest
    }

    /** Algorithm 3 (Revision 3): the /O entry. */
    private fun computeOwnerEntry(paddedOwner: ByteArray, paddedUser: ByteArray): ByteArray {
        val digest = mdx50(paddedOwner)
        val key = digest.copyOf(KEY_LEN)
        var out = paddedUser.copyOf()
        for (i in 19 downTo 0) {
            val round = key.map { (it.toInt() xor i).toByte() }.toByteArray()
            out = rc4(round, out)
        }
        return out.copyOf(32)
    }

    /** Algorithm 2 (Revision 3): the 16-byte file encryption key. */
    private fun computeFileKey(
        paddedUser: ByteArray,
        o: ByteArray,
        permissions: Int,
        fileId: ByteArray
    ): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(paddedUser)
        md.update(o)
        // Permissions as 4-byte little-endian, plus 0xFFFFFFFF bytes (R3+).
        md.update(byteArrayOf(
            (permissions and 0xFF).toByte(),
            ((permissions shr 8) and 0xFF).toByte(),
            ((permissions shr 16) and 0xFF).toByte(),
            ((permissions shr 24) and 0xFF).toByte()
        ))
        md.update(fileId)
        var digest = md.digest()
        repeat(50) { digest = MessageDigest.getInstance("MD5").digest(digest) }
        return digest.copyOf(KEY_LEN)
    }

    /** Algorithm 4/5 (Revision 3): the /U entry. */
    private fun computeUserEntry(fileKey: ByteArray, fileId: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(PAD)
        md.update(fileId)
        var out = md.digest()
        out = rc4(fileKey, out)
        for (i in 1..19) {
            val round = fileKey.map { (it.toInt() xor i).toByte() }.toByteArray()
            out = rc4(round, out)
        }
        return out + ByteArray(16)
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf(32)
        for (i in bytes.size until 32) out[i] = PAD[i - bytes.size]
        return out
    }

    private fun newFileId(): ByteArray {
        val id = ByteArray(16)
        SecureRandom().nextBytes(id)
        return id
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    // ------------------------------------------------------------------
    // Minimal parsing of ScanWhirl-generated PDFs
    // ------------------------------------------------------------------

    /**
     * Finds every `stream\n … \nendstream` body together with the number of
     * the object it belongs to. Bodies are encrypted in place, so offsets
     * before them remain valid.
     */
    private fun objectStreamBodies(raw: ByteArray): List<Pair<Int, IntRange>> {
        val latin = String(raw, Charsets.ISO_8859_1)
        val ranges = mutableListOf<Pair<Int, IntRange>>()
        var cursor = 0
        while (true) {
            val objStart = latin.indexOf(" 0 obj", cursor)
            val streamStart = latin.indexOf("\nstream\n", objStart)
            if (objStart < 0 || streamStart < 0) break
            // Object number walks backwards from the marker to the start of a line.
            var numStart = objStart - 1
            while (numStart >= 0 && latin[numStart].isDigit()) numStart--
            val objNum = latin.substring(numStart + 1, objStart).toIntOrNull()
            val dataStart = streamStart + 8
            val dataEnd = latin.indexOf("\nendstream", dataStart)
            if (dataEnd < 0) break
            if (objNum != null && dataEnd > dataStart) {
                ranges += objNum to (dataStart until dataEnd)
            }
            cursor = dataEnd + 4
        }
        return ranges
    }

    /**
     * Replaces the trailer's `<< … >>` dictionary with one that carries the
     * encryption dictionary and the file ID. The trailer sits after the xref
     * table, so replacing it does not shift any offset.
     */
    private fun replaceTrailer(raw: ByteArray, o: ByteArray, u: ByteArray, fileId: ByteArray): ByteArray {
        val latin = String(raw, Charsets.ISO_8859_1)
        val tIdx = latin.indexOf("\ntrailer\n")
        val open = latin.indexOf("<<", tIdx)
        val close = latin.indexOf(">>", open)
        val kept = if (open > 0 && close > open) {
            latin.substring(open + 2, close)
        } else {
            "/Size 1 /Root 1 0 R"
        }
        val encrypt =
            " /Encrypt << /Filter /Standard /V 2 /R 3 /Length 128 /O <${hex(o)}> /U <${hex(u)}> /P $P_DEFAULT >>" +
                " /ID [<${hex(fileId)}> <${hex(fileId)}>] "
        val patched = latin.substring(0, open + 2) + kept + encrypt + latin.substring(close)
        return patched.toByteArray(Charsets.ISO_8859_1)
    }

    /** Reads the trailer object count of a (possibly encrypted) PDF. */
    fun trailerOpen(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            val head = ByteArray(64)
            raf.read(head)
            return String(head, Charsets.ISO_8859_1).take(16)
        }
    }
}
