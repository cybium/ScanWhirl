package com.scanwhirl.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Removes the standard-security-handler password from a PDF that ScanWhirl can parse (classic xref,
 * `/Encrypt` inline or as an indirect reference).
 *
 * Every cipher the standard security handler defines for opening a file is supported, because real
 * Indian bank statements and government PDFs still ship the older revisions:
 *  - **RC4 40-bit / 128-bit** (V1/V2, R2/R3), and **V4 with `/CFM /V2`**;
 *  - **AES-128** (V4, R4, `/CFM /AESV2`);
 *  - **AES-256** (V5, R5/R6, `/CFM /AESV3` — the format [PdfEncryptor] writes);
 *  - **owner-only restrictions**: the user password is empty, so an empty password verifies and the
 *    document opens without a prompt (permissions stay a viewer concern).
 *
 * The original file is never touched: the caller gets a new plaintext PDF in app storage and imports
 * its pages; only that temporary copy is deleted afterwards.
 *
 * The parser deliberately reuses [PdfEncryptor.objects], the writer's own xref walk, so a JPEG that
 * contains `endstream` cannot confuse object boundaries. Passwords are only ever compared, never
 * stored or logged. Unsupported shapes (xref streams, object streams) throw [Unsupported] with an
 * honest message instead of guessing.
 */
object PdfDecryptor {

    /** The password did not derive the file key (user or owner password). */
    class WrongPassword : IOException("Incorrect password")

    /** Not a PDF shape this offline decoder can open (e.g. xref streams). */
    class Unsupported(message: String) : IOException(message)

    /**
     * Largest protected PDF this phone can decrypt in memory, in bytes.
     *
     * [decrypt] needs the whole file at once and holds roughly five copies of it (the raw
     * bytes, an ISO-8859-1 `String` at 2 bytes per character, the output stream, and one
     * stream-sized copy per object), so a file may claim at most an eighth of the app's
     * maximum heap. Without this gate a 40-60 MB bank statement on a 3 GB phone throws an
     * [OutOfMemoryError], which kills the process instead of showing a message.
     */
    fun maxInMemoryBytes(heapBytes: Long = maxHeap()): Long =
        (heapBytes / HEAP_COPIES).coerceIn(MIN_IN_MEMORY_BYTES, PdfImport.MAX_BYTES)

    /** The heap the gate reads; replaceable in tests. */
    @Volatile
    internal var maxHeap: () -> Long = { Runtime.getRuntime().maxMemory() }

    internal fun resetForTests() {
        maxHeap = { Runtime.getRuntime().maxMemory() }
    }

    /**
     * Returns a decrypted copy of [raw]. Throws [WrongPassword] when neither the user nor the
     * owner password opens it, and [Unsupported] when the encryption or PDF shape is not handled.
     *
     * An empty [password] is a real user password, not a bypass: owner-only documents have one, and
     * it is verified exactly like any other, so it cannot open a file that needs a typed password.
     */
    fun decrypt(raw: ByteArray, password: String): ByteArray = try {
        decryptChecked(raw, password)
    } catch (e: WrongPassword) {
        throw e
    } catch (e: Unsupported) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw Unsupported(e.message ?: "This PDF cannot be opened offline")
    } catch (e: Exception) {
        // A malformed xref/dictionary must never surface as an IndexOutOfBounds to the UI.
        throw Unsupported("This PDF's password protection could not be read")
    }

    private fun decryptChecked(raw: ByteArray, password: String): ByteArray {
        val latin = String(raw, Charsets.ISO_8859_1)
        val trailer = trailerDictionary(latin)
            ?: throw Unsupported("This PDF has no trailer ScanWhirl can read")
        val dict = encryptionDictionary(raw, latin, trailer)
            ?: throw Unsupported("This PDF does not use the standard password format")
        val version = number(dict, "V") ?: 1
        val revision = number(dict, "R")
            ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        if (version !in 1..5 || revision !in 2..6) {
            throw Unsupported("This PDF uses a password format ScanWhirl cannot open")
        }
        if (revision >= 5 && version < 5) {
            throw Unsupported("The PDF encryption dictionary is inconsistent")
        }
        val aes = version >= 5 || Regex("/CFM\\s*/AESV[23]").containsMatchIn(dict)
        val streamsPlain = Regex("/StmF\\s*/Identity").containsMatchIn(dict)

        val key = if (version >= 5) {
            val u = hex(dict, "U") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val ue = hex(dict, "UE") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val o = hex(dict, "O")
            val oe = hex(dict, "OE")
            PdfAes.userKey(password, u, ue)
                ?: o?.let { owner -> oe?.let { ownerEnc -> PdfAes.ownerKey(password, owner, ownerEnc, u) } }
                ?: throw WrongPassword()
        } else {
            val o = hex(dict, "O") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val u = hex(dict, "U") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val permissions = signedNumber(dict, "P")
                ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val bits = number(dict, "Length") ?: 40
            val keyBytes = if (revision <= 2) 5 else (bits / 8).coerceIn(5, 16)
            val encryptMetadata = !Regex("/EncryptMetadata\\s+false").containsMatchIn(dict)
            legacyKey(password, o, u, permissions, fileId(trailer), revision, keyBytes, encryptMetadata)
                ?: throw WrongPassword()
        }

        val objects = PdfEncryptor.objects(raw)
        if (objects.isEmpty()) throw Unsupported("Not a PDF ScanWhirl can open")
        val out = ByteArrayOutputStream(raw.size)
        out.write(raw, 0, objects.minOf { it.start })
        val offsets = HashMap<Int, Int>()
        for (obj in objects.sortedBy { it.start }) {
            offsets[obj.number] = out.size()
            val marker = latin.indexOf(">>\nstream\n", obj.start)
            if (marker < 0 || marker >= obj.end) {
                out.write(raw, obj.start, obj.end - obj.start)
                continue
            }
            val dictEnd = marker + ">>\nstream\n".length
            if (dictEnd > obj.end) throw Unsupported("Object ${obj.number} has a broken dictionary")
            val dictText = latin.substring(obj.start, dictEnd)
            val lengthMatch = Regex("/Length\\s+(\\d+)").find(dictText)
                ?: throw Unsupported("Stream ${obj.number} has no /Length")
            val oldLength = lengthMatch.groupValues[1].toIntOrNull()
                ?: throw Unsupported("Stream ${obj.number} has an invalid /Length")
            val bodyEnd = dictEnd + oldLength
            if (bodyEnd > obj.end) throw Unsupported("Stream ${obj.number} runs past its object")
            val body = raw.copyOfRange(dictEnd, bodyEnd)
            val plain = when {
                streamsPlain -> body
                // V5/R6 streams use the file key directly; older revisions derive one key per object.
                version >= 5 -> aesCbc(key, body)
                aes -> aesCbc(objectKey(key, obj.number, obj.gen, aes = true), body)
                else -> rc4(objectKey(key, obj.number, obj.gen, aes = false), body)
            }
            val newDict = dictText.substring(0, lengthMatch.range.first) +
                "/Length ${plain.size}" +
                dictText.substring(lengthMatch.range.last + 1)
            out.write(newDict.toByteArray(Charsets.ISO_8859_1))
            out.write(plain)
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
        val root = Regex("/Root\\s+(\\d+)\\s+0\\s+R").find(trailer)
            ?.groupValues?.get(1)
            ?: throw Unsupported("The PDF trailer has no /Root")
        val tail = "trailer\n<< /Size $size /Root $root 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n"
        out.write(tail.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Standard security handler, revisions 2-6
    // ------------------------------------------------------------------

    /** The 32-byte padding string of Algorithm 2 (ISO 32000-1 7.6.3.3). */
    private val PADDING = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
    )

    private val IV_BYTES = 16

    /**
     * Algorithm 2 for R2-R4: MD5 over the padded password, `/O`, `/P`, the first `/ID` element and
     * (R4 without metadata encryption) `FF FF FF FF`, iterated 50 times for R3/R4. Null when neither
     * the user nor the owner password opens the document.
     */
    private fun legacyKey(
        password: String,
        o: ByteArray,
        u: ByteArray,
        permissions: Int,
        fileId: ByteArray,
        revision: Int,
        keyBytes: Int,
        encryptMetadata: Boolean
    ): ByteArray? {
        // R2-R4 passwords are PDFDocEncoding; the bytes that matter are Latin-1 for every real file.
        val typed = password.toByteArray(Charsets.ISO_8859_1)
        val userKey = legacyFileKey(padded(typed), o, permissions, fileId, revision, keyBytes, encryptMetadata)
        if (legacyUserMatches(userKey, u, revision, fileId)) return userKey
        if (o.size < 32) return null
        // Algorithm 3/5: /O holds the (padded) user password, encrypted with the owner key.
        var ownerKey = md5(padded(typed))
        if (revision >= 3) repeat(50) { ownerKey = md5(ownerKey.copyOf(keyBytes)) }
        ownerKey = ownerKey.copyOf(keyBytes)
        var recovered = o.copyOf(32)
        recovered = if (revision == 2) {
            rc4(ownerKey, recovered)
        } else {
            for (i in 19 downTo 0) recovered = rc4(xorWith(ownerKey, i), recovered)
            recovered
        }
        val recoveredKey = legacyFileKey(recovered, o, permissions, fileId, revision, keyBytes, encryptMetadata)
        return if (legacyUserMatches(recoveredKey, u, revision, fileId)) recoveredKey else null
    }

    private fun legacyFileKey(
        paddedPassword: ByteArray,
        o: ByteArray,
        permissions: Int,
        fileId: ByteArray,
        revision: Int,
        keyBytes: Int,
        encryptMetadata: Boolean
    ): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(paddedPassword)
        md.update(o.copyOf(32))
        md.update(
            byteArrayOf(
                (permissions and 0xFF).toByte(),
                ((permissions ushr 8) and 0xFF).toByte(),
                ((permissions ushr 16) and 0xFF).toByte(),
                ((permissions ushr 24) and 0xFF).toByte()
            )
        )
        md.update(fileId)
        if (revision >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
        var hash = md.digest()
        if (revision >= 3) repeat(50) { hash = md5(hash.copyOf(keyBytes)) }
        return hash.copyOf(keyBytes)
    }

    /** Algorithm 4 (R2) / 6 (R3-R4): does [key] reproduce /U from the padding string? */
    private fun legacyUserMatches(key: ByteArray, u: ByteArray, revision: Int, fileId: ByteArray): Boolean {
        if (revision <= 2) {
            return u.size >= 32 && MessageDigest.isEqual(rc4(key, PADDING), u.copyOf(32))
        }
        if (u.size < 16) return false
        val md = MessageDigest.getInstance("MD5")
        md.update(PADDING)
        md.update(fileId)
        var value = rc4(key, md.digest())
        for (i in 1..19) value = rc4(xorWith(key, i), value)
        return MessageDigest.isEqual(value.copyOf(16), u.copyOf(16))
    }

    /**
     * Algorithm 1 (R2-R4): MD5 of the file key with the object and generation numbers — plus the
     * literal `sAlT` for AES streams — shortened to `key + 5` bytes (never more than 16).
     * AES-256 (V5) uses the file key directly and never reaches this function.
     */
    private fun objectKey(fileKey: ByteArray, number: Int, generation: Int, aes: Boolean): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(
            byteArrayOf(
                number.toByte(),
                (number ushr 8).toByte(),
                (number ushr 16).toByte(),
                generation.toByte(),
                (generation ushr 8).toByte()
            )
        )
        // "For AES streams the key is extended with the 4 bytes 0x73 0x41 0x6C 0x54 (sAlT)."
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    /** IV (16 bytes) + AES-CBC/PKCS#7 body, as every AES revision writes streams. */
    private fun aesCbc(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size <= IV_BYTES || (data.size - IV_BYTES) % 16 != 0) {
            throw Unsupported("An AES stream is not IV plus whole blocks")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(data.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        } catch (e: Exception) {
            throw Unsupported("An AES stream could not be decrypted")
        }
    }

    /** RC4 (Algorithm 1's cipher) — the whole stream body, no IV, no padding. */
    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        if (key.isEmpty()) throw Unsupported("The PDF encryption key is empty")
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]
            s[i] = s[j]
            s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (k in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]
            s[a] = s[b]
            s[b] = t
            out[k] = (data[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    private fun xorWith(data: ByteArray, value: Int): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor value).toByte() }

    private fun padded(password: ByteArray): ByteArray {
        val out = ByteArray(32)
        val copy = minOf(password.size, 32)
        password.copyInto(out, 0, 0, copy)
        if (copy < 32) PADDING.copyInto(out, copy, 0, 32 - copy)
        return out
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    // ------------------------------------------------------------------
    // Encryption dictionary
    // ------------------------------------------------------------------

    /**
     * The `/Encrypt` dictionary, inline (`/Encrypt << … >>`) or referenced (`/Encrypt 6 0 R`).
     * Both shapes are legal and real writers use both.
     */
    private fun encryptionDictionary(raw: ByteArray, latin: String, trailer: String): String? {
        val inline = Regex("/Encrypt\\s*<<").find(trailer)
        if (inline != null) return balancedDict(trailer, trailer.indexOf("<<", inline.range.first))
        val reference = Regex("/Encrypt\\s+(\\d+)\\s+\\d+\\s+R").find(trailer) ?: return null
        val number = reference.groupValues[1].toIntOrNull() ?: return null
        val obj = PdfEncryptor.objects(raw).firstOrNull { it.number == number } ?: return null
        val text = latin.substring(obj.start, obj.end)
        val open = text.indexOf("<<")
        if (open < 0) return null
        return balancedDict(text, open)
    }

    private fun trailerDictionary(latin: String): String? {
        val at = latin.lastIndexOf("\ntrailer\n")
        if (at < 0) return null
        val from = at + "\ntrailer\n".length
        val to = latin.indexOf("\nstartxref\n", from)
        return if (to < 0) latin.substring(from) else latin.substring(from, to)
    }

    /** The first `/ID` element: a key-derivation input for R2-R4, absent in some files. */
    private fun fileId(trailer: String): ByteArray {
        val hex = Regex("/ID\\s*\\[\\s*<([0-9A-Fa-f]+)>").find(trailer)?.groupValues?.get(1)
            ?: return ByteArray(0)
        return hexOf(hex) ?: ByteArray(0)
    }

    /** The `<< … >>` starting at [start] (nested dictionaries included). */
    private fun balancedDict(text: String, start: Int): String? {
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            when {
                text.startsWith("<<", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith(">>", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return text.substring(start, i)
                }
                else -> i++
            }
        }
        return null
    }

    private fun number(dict: String, name: String): Int? =
        Regex("/$name\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun signedNumber(dict: String, name: String): Int? =
        Regex("/$name\\s+(-?\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun hex(dict: String, name: String): ByteArray? {
        val hex = Regex("/$name\\s*<([0-9A-Fa-f]+)>").find(dict)?.groupValues?.get(1) ?: return null
        return hexOf(hex)
    }

    private fun hexOf(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Copies of the file [decrypt] holds at its peak (see [maxInMemoryBytes]). */
    private const val HEAP_COPIES = 8L

    /** Never refuse a file this small, whatever the heap reports. */
    private const val MIN_IN_MEMORY_BYTES = 1L shl 20
}
