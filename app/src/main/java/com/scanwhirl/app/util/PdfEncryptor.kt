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
     * Finds every stream body together with the number of the object it
     * belongs to, by walking the PDF's xref table (not by scanning for
     * markers inside binary bodies).
     *
     * The generator writes xref entries as 20-byte records
     * (`%010d 00000 n \n`), one per object, ending with the xref offset in
     * `startxref`. For each in-use object we take the byte region from its
     * offset to the next larger offset (the xref offset is the final bound),
     * locate `\nstream\n` inside that region, and read `/Length <n>` from the
     * dictionary text before `stream`. The body is exactly `length` bytes
     * starting right after `\nstream\n`.
     */
    internal fun objectStreamBodies(raw: ByteArray): List<Pair<Int, IntRange>> {
        val latin = String(raw, Charsets.ISO_8859_1)
        val ranges = mutableListOf<Pair<Int, IntRange>>()

        // 1. Locate the xref offset via the last "startxref\n".
        val sxMarker = "startxref\n"
        val sxIdx = latin.lastIndexOf(sxMarker)
        if (sxIdx < 0) return ranges
        val numStart = sxIdx + sxMarker.length
        var numEnd = numStart
        while (numEnd < latin.length && latin[numEnd].isDigit()) numEnd++
        val xrefOffset = latin.substring(numStart, numEnd).toIntOrNull() ?: return ranges

        // 2. Read xref header: "xref\n0 <count>\n".
        var p = latin.indexOf('\n', xrefOffset) + 1 // skip past "xref\n"
        val countLineEnd = latin.indexOf('\n', p)
        if (countLineEnd < 0) return ranges
        val countLine = latin.substring(p, countLineEnd).trim()
        val countParts = countLine.split(' ')
        val objCount = countParts.last().toIntOrNull() ?: return ranges
        p = countLineEnd + 1

        // 3. Parse entries: 20 bytes each ("%010d 00000 n \n"). Entry i is
        // object i; object 0 is always free. We only keep in-use ('n') ones.
        val offsets = mutableListOf<Pair<Int, Long>>()
        for (i in 0 until objCount) {
            if (p + 20 > latin.length) break
            val entry = latin.substring(p, p + 20)
            val offStr = entry.substring(0, 10)
            val useFlag = entry[17] // offset(10) + sp(1) + gen(5) + sp(1) = 17
            if (i > 0 && useFlag == 'n') { // object 0 is always free
                val off = offStr.toLongOrNull()
                if (off != null) offsets += i to off
            }
            p += 20
        }

        // 4. For each object, region = [offset, nextOffset); last ends at xrefOffset.
        offsets.sortBy { it.second }
        for (i in offsets.indices) {
            val (objNum, startOff) = offsets[i]
            val endOff = if (i + 1 < offsets.size) offsets[i + 1].second else xrefOffset
            val regionStart = startOff.toInt().coerceAtLeast(0)
            val regionEnd = endOff.toInt().coerceAtMost(latin.length)
            if (regionStart >= regionEnd) continue

            val region = latin.substring(regionStart, regionEnd)
            val streamRel = region.indexOf("\nstream\n")
            if (streamRel < 0) continue
            val streamIdx = regionStart + streamRel

            // /Length <n> in the dictionary text before stream.
            val dictText = latin.substring(regionStart, streamIdx)
            val lengthMatch = Regex("/Length\\s*(\\d+)").find(dictText)
            val length = lengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue

            val dataStart = streamIdx + 8 // right after "\nstream\n"
            ranges += objNum to (dataStart until dataStart + length)
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
