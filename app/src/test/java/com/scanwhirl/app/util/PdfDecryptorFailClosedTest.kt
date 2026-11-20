package com.scanwhirl.app.util

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.SplittableRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * S2 fail-closed decryption (2026-11-20).
 *
 * Every fixture is built here from the ISO 32000 standard security handler written independently of
 * the production code: RC4, the R2-R4 key algorithms 2/3/4/5/6, AES-128 object keys, and the V5 R5
 * (single SHA-256) / R6 (Algorithm 2.B) entries. PDFBox is only the oracle that proves a fixture is
 * a real encrypted PDF and that the decrypted copy is a plain PDF with the same text.
 *
 * The point of the suite is honesty: a shape the decoder cannot rewrite faithfully must throw
 * [PdfDecryptor.Unsupported] and publish nothing, never a half-decrypted file.
 */
class PdfDecryptorFailClosedTest {

    // ------------------------------------------------------------------
    // Independent crypto (test side, from ISO 32000-1 7.6.3 / 32000-2 2.A-2.B)
    // ------------------------------------------------------------------

    private val padding = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
    )

    private val random = SplittableRandom(0x5EA1_5EEDL)

    private fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    private fun sha(algorithm: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(algorithm)
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun md5(vararg parts: ByteArray): ByteArray = sha("MD5", *parts)

    private fun pad(pw: ByteArray): ByteArray {
        val out = ByteArray(32)
        val n = minOf(pw.size, 32)
        pw.copyInto(out, 0, 0, n)
        if (n < 32) padding.copyInto(out, n, 0, 32 - n)
        return out
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (k in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]; s[a] = s[b]; s[b] = t
            out[k] = (data[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    private fun xorKey(key: ByteArray, value: Int): ByteArray =
        ByteArray(key.size) { (key[it].toInt() xor value).toByte() }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    /** Algorithm 2: the file key for an already padded user password. */
    private fun legacyFileKey(
        paddedUserPassword: ByteArray,
        o: ByteArray,
        permissions: Int,
        id: ByteArray,
        revision: Int,
        keyLen: Int,
        encryptMetadata: Boolean
    ): ByteArray {
        var hash = md5(
            paddedUserPassword,
            o.copyOf(32),
            le32(permissions),
            id,
            if (revision >= 4 && !encryptMetadata) byteArrayOf(-1, -1, -1, -1) else ByteArray(0)
        )
        // Algorithm 2 step (h): 50 rounds, each over the FIRST keyLen bytes of the digest.
        if (revision >= 3) repeat(50) { hash = md5(hash.copyOf(keyLen)) }
        return hash.copyOf(keyLen)
    }

    /** Algorithm 3/5: the /O entry (the owner password protects the user password). */
    private fun ownerEntry(ownerPassword: ByteArray, userPassword: ByteArray, revision: Int, keyLen: Int): ByteArray {
        var hash = md5(pad(ownerPassword))
        // Algorithm 3 step (b): 50 rounds, each over the FULL 16-byte digest (R3/R4).
        if (revision >= 3) repeat(50) { hash = md5(hash) }
        val key = hash.copyOf(keyLen)
        val data = pad(userPassword)
        if (revision == 2) return rc4(key, data)
        var d = data
        for (i in 19 downTo 0) d = rc4(xorKey(key, i), d)
        return d
    }

    /** Algorithm 4/6: the /U entry. */
    private fun userEntry(fileKey: ByteArray, id: ByteArray, revision: Int): ByteArray {
        if (revision == 2) return rc4(fileKey, padding)
        var value = rc4(fileKey, md5(padding, id))
        for (i in 1..19) value = rc4(xorKey(fileKey, i), value)
        return value + bytes(16)
    }

    private fun perObjectKey(fileKey: ByteArray, number: Int, generation: Int, salted: Boolean): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(
            byteArrayOf(
                number.toByte(), (number ushr 8).toByte(), (number ushr 16).toByte(),
                generation.toByte(), (generation ushr 8).toByte()
            )
        )
        if (salted) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    private fun cbc(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return c.doFinal(data)
    }

    /** AES-CBC without padding: /UE and /OE are exactly one block. */
    private fun cbcRaw(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun ecb(key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /** Algorithm 2.B, R6. */
    private fun hash6(password: ByteArray, salt: ByteArray, userData: ByteArray = ByteArray(0)): ByteArray {
        var k = sha("SHA-256", password, salt, userData)
        var e = ByteArray(0)
        var rounds = 0
        while (rounds < 64 || (e[e.size - 1].toInt() and 0xFF) > rounds - 32) {
            val unit = password + k + userData
            val k1 = ByteArray(unit.size * 64)
            for (r in 0 until 64) unit.copyInto(k1, r * unit.size)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(k.copyOfRange(0, 16), "AES"),
                IvParameterSpec(k.copyOfRange(16, 32))
            )
            e = c.doFinal(k1)
            k = when ((0 until 16).sumOf { e[it].toInt() and 0xFF } % 3) {
                0 -> sha("SHA-256", e)
                1 -> sha("SHA-384", e)
                else -> sha("SHA-512", e)
            }
            rounds++
        }
        return k.copyOf(32)
    }

    /** R5: one SHA-256 of password + salt + user data (no R6 loop). */
    private fun hash5(password: ByteArray, salt: ByteArray, userData: ByteArray = ByteArray(0)): ByteArray =
        sha("SHA-256", password, salt, userData)

    private fun preparedPassword(pw: String): ByteArray {
        val b = Normalizer.normalize(pw, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return if (b.size <= 127) b else b.copyOf(127)
    }

    // ------------------------------------------------------------------
    // Fixture writer
    // ------------------------------------------------------------------

    private enum class Cfm { V2, AESV2, AESV3, IDENTITY, UNKNOWN }

    private data class Spec(
        val version: Int,
        val revision: Int,
        val lengthBits: Int? = 128,
        val streamCfm: Cfm = Cfm.AESV2,
        val stringCfm: Cfm = Cfm.AESV2,
        val ownerPassword: String = "owner-pass",
        val userPassword: String = "user-pass",
        val permissions: Int = -3904,
        val encryptMetadata: Boolean = true,
        val inlineEncrypt: Boolean = false,
        val dictPatch: ((String) -> String)? = null,
        val extraTrailer: String = "",
        val extraObjects: List<Pair<Int, Int>> = emptyList(),
        val generations: Map<Int, Int> = emptyMap(),
        val omitPerms: Boolean = false
    )

    private class Built(
        val bytes: ByteArray,
        val content: IntRange,
        val metadata: IntRange,
        val offsets: Map<Int, Int>
    )

    private val title = "Hello ScanWhirl"
    private val producer = "ScanWhirl test producer"
    private val bodyText = "Hello ScanWhirl"
    private val xmp = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/></x:xmpmeta>\n" +
        "<?xpacket end=\"w\"?>"

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun id1(): ByteArray = ByteArray(16) { (0x10 + it).toByte() }
    private fun id2(): ByteArray = ByteArray(16) { (0xA0 + it).toByte() }

    private fun filterName(cfm: Cfm): String = when (cfm) {
        Cfm.V2 -> "RC4CF"
        Cfm.AESV2, Cfm.AESV3 -> "StdCF"
        Cfm.IDENTITY -> "Identity"
        Cfm.UNKNOWN -> "BrokenCF"
    }

    private fun encryptData(
        cfm: Cfm,
        fileKey: ByteArray,
        number: Int,
        generation: Int,
        plain: ByteArray
    ): ByteArray = when (cfm) {
        Cfm.IDENTITY, Cfm.UNKNOWN -> plain
        Cfm.V2 -> rc4(perObjectKey(fileKey, number, generation, salted = false), plain)
        Cfm.AESV2 -> {
            val iv = bytes(16)
            iv + cbc(true, perObjectKey(fileKey, number, generation, salted = true), iv, plain)
        }
        Cfm.AESV3 -> {
            val iv = bytes(16)
            iv + cbc(true, fileKey, iv, plain)
        }
    }

    private fun build(spec: Spec): Built {
        val keyLen = (spec.lengthBits ?: 40) / 8
        val id = id1()

        val fileKey: ByteArray
        val o: ByteArray
        val u: ByteArray
        var oe: ByteArray? = null
        var ue: ByteArray? = null
        var perms: ByteArray? = null
        if (spec.version >= 5) {
            fileKey = bytes(32)
            val uValidation = bytes(8)
            val uKeySalt = bytes(8)
            val oValidation = bytes(8)
            val oKeySalt = bytes(8)
            val user = preparedPassword(spec.userPassword)
            val owner = preparedPassword(spec.ownerPassword)
            val h: (ByteArray, ByteArray, ByteArray) -> ByteArray =
                if (spec.revision == 5) ::hash5 else ::hash6
            u = h(user, uValidation, ByteArray(0)) + uValidation + uKeySalt
            ue = cbcRaw(h(user, uKeySalt, ByteArray(0)), ByteArray(16), fileKey)
            o = h(owner, oValidation, u) + oValidation + oKeySalt
            oe = cbcRaw(h(owner, oKeySalt, u), ByteArray(16), fileKey)
            val block = ByteArray(16)
            le32(spec.permissions).copyInto(block, 0)
            block[4] = 0xFF.toByte(); block[5] = 0xFF.toByte()
            block[6] = 0xFF.toByte(); block[7] = 0xFF.toByte()
            block[8] = if (spec.encryptMetadata) 'T'.code.toByte() else 'F'.code.toByte()
            block[9] = 'a'.code.toByte(); block[10] = 'd'.code.toByte(); block[11] = 'b'.code.toByte()
            bytes(4).copyInto(block, 12)
            perms = ecb(fileKey, block)
        } else {
            val ownerBytes = ownerEntry(
                spec.ownerPassword.toByteArray(Charsets.ISO_8859_1),
                spec.userPassword.toByteArray(Charsets.ISO_8859_1),
                spec.revision, keyLen
            )
            fileKey = legacyFileKey(
                pad(spec.userPassword.toByteArray(Charsets.ISO_8859_1)),
                ownerBytes, spec.permissions, id, spec.revision, keyLen, spec.encryptMetadata
            )
            o = ownerBytes
            u = userEntry(fileKey, id, spec.revision)
        }

        // ---- /Encrypt dictionary ----
        val cf = StringBuilder("/CF <<")
        val used = mutableSetOf<Cfm>()
        if (spec.version >= 4) {
            used += spec.streamCfm
            used += spec.stringCfm
        }
        for (cfm in used) {
            when (cfm) {
                Cfm.V2 -> cf.append(" /${filterName(cfm)} << /AuthEvent /DocOpen /CFM /V2 /Length ${spec.lengthBits ?: 40} >>")
                Cfm.AESV2 -> cf.append(" /${filterName(cfm)} << /AuthEvent /DocOpen /CFM /AESV2 /Length 16 >>")
                Cfm.AESV3 -> cf.append(" /${filterName(cfm)} << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >>")
                Cfm.UNKNOWN -> cf.append(" /${filterName(cfm)} << /AuthEvent /DocOpen /CFM /AESV4 /Length 32 >>")
                Cfm.IDENTITY -> Unit
            }
        }
        cf.append(" >>")
        val dict = StringBuilder("<< /Filter /Standard /V ${spec.version} /R ${spec.revision}")
        if (spec.lengthBits != null) dict.append(" /Length ${spec.lengthBits}")
        if (spec.version >= 4) dict.append(" $cf")
        if (spec.version >= 4) dict.append(" /StmF /${filterName(spec.streamCfm)} /StrF /${filterName(spec.stringCfm)}")
        dict.append(" /O <${hex(o)}> /U <${hex(u)}>")
        if (oe != null && ue != null) {
            dict.append(" /OE <${hex(oe)}> /UE <${hex(ue)}>")
            if (!spec.omitPerms && perms != null) dict.append(" /Perms <${hex(perms)}>")
        }
        dict.append(" /P ${spec.permissions}")
        if (!spec.encryptMetadata) dict.append(" /EncryptMetadata false")
        dict.append(" >>")
        val encryptDict = spec.dictPatch?.invoke(dict.toString()) ?: dict.toString()

        // ---- objects ----
        val entries = mutableListOf<Triple<Int, Int, ByteArray>>()
        val streamBytes = HashMap<Int, Int>()
        fun gen(n: Int) = spec.generations[n] ?: 0
        fun ref(n: Int) = "$n ${gen(n)} R"
        fun string(number: Int, text: String): String {
            val cfm = if (spec.version >= 4) spec.stringCfm else Cfm.V2
            return "<${hex(encryptData(cfm, fileKey, number, gen(number), text.toByteArray(Charsets.ISO_8859_1)))}>"
        }
        fun addStream(number: Int, dictExtra: String, body: ByteArray, cfm: Cfm) {
            val g = gen(number)
            val encrypted = encryptData(cfm, fileKey, number, g, body)
            entries += Triple(
                number, g,
                "<< /Length ${encrypted.size}$dictExtra >>\nstream\n".toByteArray(Charsets.ISO_8859_1) +
                    encrypted + "\nendstream".toByteArray(Charsets.ISO_8859_1)
            )
            streamBytes[number] = encrypted.size
        }

        entries += Triple(1, gen(1), (
            "<< /Type /Catalog /Pages ${ref(2)} /Title ${string(1, title)} >>"
            ).toByteArray(Charsets.ISO_8859_1))
        entries += Triple(2, gen(2), (
            "<< /Type /Pages /Kids [${ref(3)}] /Count 1 >>"
            ).toByteArray(Charsets.ISO_8859_1))
        entries += Triple(3, gen(3), (
            "<< /Type /Page /Parent ${ref(2)} /MediaBox [0 0 200 200] " +
                "/Contents ${ref(4)} /Resources << /Font << /F1 ${ref(6)} >> >> >>"
            ).toByteArray(Charsets.ISO_8859_1))
        addStream(4, "", "BT /F1 12 Tf 20 100 Td ($bodyText) Tj ET".toByteArray(Charsets.ISO_8859_1),
            if (spec.version >= 4) spec.streamCfm else Cfm.V2)
        addStream(
            5, " /Type /Metadata /Subtype /XML", xmp.toByteArray(Charsets.ISO_8859_1),
            if (!spec.encryptMetadata) Cfm.IDENTITY
            else if (spec.version >= 4) spec.streamCfm else Cfm.V2
        )
        entries += Triple(6, gen(6),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray(Charsets.ISO_8859_1))
        entries += Triple(7, gen(7), (
            "<< /Producer ${string(7, producer)} /Title ${string(7, title)} >>"
            ).toByteArray(Charsets.ISO_8859_1))
        for ((number, g) in spec.extraObjects) {
            entries += Triple(number, g, "<< /N $number >>".toByteArray(Charsets.ISO_8859_1))
        }
        if (!spec.inlineEncrypt) {
            entries += Triple(8, gen(8), encryptDict.toByteArray(Charsets.ISO_8859_1))
        }

        // ---- assemble ----
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = HashMap<Int, Int>()
        val bodies = HashMap<Int, IntRange>()
        for ((number, g, text) in entries.sortedBy { it.first }) {
            offsets[number] = out.size()
            out.write("$number $g obj\n".toByteArray(Charsets.ISO_8859_1))
            val textStart = out.size()
            out.write(text)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            if (streamBytes.containsKey(number)) {
                val marker = "\nstream\n".toByteArray(Charsets.ISO_8859_1)
                val at = indexOf(text, marker, 0)
                val start = textStart + at + marker.size
                bodies[number] = start until (start + streamBytes.getValue(number))
            }
        }
        val xrefOffset = out.size()
        val size = entries.maxOf { it.first } + 1
        val xref = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            val e = entries.firstOrNull { it.first == n }
            if (e == null) {
                xref.append("0000000000 00000 f \n")
            } else {
                xref.append(String.format(Locale.ROOT, "%010d %05d n \n", offsets.getValue(n), e.second))
            }
        }
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        val encryptInTrailer = if (spec.inlineEncrypt) " /Encrypt $encryptDict" else " /Encrypt ${ref(8)}"
        val tail = "trailer\n<< /Size $size /Root ${ref(1)} /Info ${ref(7)} " +
            "/ID [<${hex(id1())}> <${hex(id2())}>]$encryptInTrailer${spec.extraTrailer} >>\n" +
            "startxref\n$xrefOffset\n%%EOF\n"
        out.write(tail.toByteArray(Charsets.ISO_8859_1))
        return Built(out.toByteArray(), bodies.getValue(4), bodies.getValue(5), offsets)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun replaceOnce(bytes: ByteArray, from: String, to: String): ByteArray {
        val a = from.toByteArray(Charsets.ISO_8859_1)
        val b = to.toByteArray(Charsets.ISO_8859_1)
        val at = indexOf(bytes, a, 0)
        require(at >= 0) { "fixture does not contain $from" }
        return bytes.copyOfRange(0, at) + b + bytes.copyOfRange(at + a.size, bytes.size)
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    /** The output must be a genuinely plain PDF whose pages and text survived. */
    private fun assertPlainPdf(plain: ByteArray, expectTitle: String? = title) {
        val file = File.createTempFile("s2-plain-", ".pdf")
        try {
            file.writeBytes(plain)
            Loader.loadPDF(file).use { doc ->
                assertFalse("output is still encrypted", doc.isEncrypted)
                assertEquals(1, doc.numberOfPages)
                assertTrue("page text survived", PDFTextStripper().getText(doc).contains(bodyText))
                if (expectTitle != null) {
                    assertEquals("strings were decrypted", expectTitle, doc.documentInformation.title)
                }
            }
        } finally {
            file.delete()
        }
    }

    /**
     * PDFBox proves the fixture really is an encrypted PDF before we judge the decryptor.
     *
     * [expectText] is false for the V4 `/CFM /V2` fixture: PDFBox 3 does not rebuild that stream
     * cipher the way the standard specifies (its own key derivation disagrees), so it is not a
     * usable oracle there. The decrypted output is still verified through PDFBox as a plain PDF.
     */
    private fun assertFixtureIsReal(built: Built, password: String, expectText: Boolean = true) {
        Loader.loadPDF(built.bytes, password).use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(1, doc.numberOfPages)
            if (expectText) {
                assertTrue(PDFTextStripper().getText(doc).contains(bodyText))
            }
        }
    }

    // ------------------------------------------------------------------
    // Crypt filters by name
    // ------------------------------------------------------------------

    @Test
    fun `AES-128 streams with RC4 strings open and both are decrypted`() {
        val built = build(Spec(version = 4, revision = 4, streamCfm = Cfm.AESV2, stringCfm = Cfm.V2))
        assertFixtureIsReal(built, "user-pass")
        val plain = PdfDecryptor.decrypt(built.bytes, "user-pass")
        assertPlainPdf(plain)
        assertTrue("the metadata stream survived", String(plain, Charsets.ISO_8859_1).contains("xmpmeta"))
    }

    @Test
    fun `RC4 streams with AES-128 strings open and both are decrypted`() {
        val built = build(Spec(version = 4, revision = 4, streamCfm = Cfm.V2, stringCfm = Cfm.AESV2))
        assertFixtureIsReal(built, "user-pass", expectText = false)
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
    }

    @Test
    fun `an Identity string filter leaves plaintext strings alone`() {
        val built = build(Spec(version = 4, revision = 4, streamCfm = Cfm.AESV2, stringCfm = Cfm.IDENTITY))
        assertFixtureIsReal(built, "user-pass")
        // /Title and /Producer were stored in the clear: decrypting them again would corrupt them.
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
    }

    @Test
    fun `an unknown crypt filter is refused instead of silently copied`() {
        val built = build(Spec(version = 4, revision = 4, streamCfm = Cfm.UNKNOWN))
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    @Test
    fun `a stream filter name that is not in the CF dictionary is refused`() {
        val built = build(
            Spec(version = 4, revision = 4, dictPatch = { it.replace("/StmF /StdCF", "/StmF /MissingCF") })
        )
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    @Test
    fun `a non-standard security handler is refused`() {
        val built = build(
            Spec(version = 4, revision = 4, dictPatch = { it.replace("/Filter /Standard", "/Filter /Adobe.PubSec") })
        )
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    // ------------------------------------------------------------------
    // AES-256: R5 vs R6, permissions block
    // ------------------------------------------------------------------

    private fun aes256(revision: Int, patch: ((String) -> String)? = null): Built = build(
        Spec(
            version = 5, revision = revision, lengthBits = 256,
            streamCfm = Cfm.AESV3, stringCfm = Cfm.AESV3,
            ownerPassword = "owner-pass", userPassword = "user-pass",
            dictPatch = patch
        )
    )

    @Test
    fun `R6 opens with the user password and with the owner password`() {
        val built = aes256(6)
        assertFixtureIsReal(built, "user-pass")
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "owner-pass"))
        assertThrows(PdfDecryptor.WrongPassword::class.java) {
            PdfDecryptor.decrypt(built.bytes, "not-the-password")
        }
    }

    @Test
    fun `R5 uses the single SHA-256 derivation, not the R6 loop`() {
        val built = aes256(5)
        assertFixtureIsReal(built, "user-pass")
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "owner-pass"))
        assertThrows(PdfDecryptor.WrongPassword::class.java) {
            PdfDecryptor.decrypt(built.bytes, "not-the-password")
        }
    }

    @Test
    fun `a missing Perms block is refused, not ignored`() {
        val built = aes256(6) { it.replace(Regex(" ?/Perms <[0-9A-F]+>"), "") }
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    @Test
    fun `an altered Perms block is refused, not ignored`() {
        val built = aes256(6) { dict ->
            val m = Regex("/Perms <([0-9A-F]{32})").find(dict)!!
            dict.replace(m.value, "/Perms <0" + m.groupValues[1].substring(1))
        }
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    // ------------------------------------------------------------------
    // Legacy revisions
    // ------------------------------------------------------------------

    @Test
    fun `a 40-bit R3 file opens with its owner password`() {
        val built = build(
            Spec(
                version = 2, revision = 3, lengthBits = 40,
                streamCfm = Cfm.V2, stringCfm = Cfm.V2,
                ownerPassword = "short-owner", userPassword = "short-user"
            )
        )
        assertFixtureIsReal(built, "short-user")
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "short-user"))
        // The owner path is Algorithm 3 then 5: a truncated 50-round loop cannot recover it.
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "short-owner"))
    }

    @Test
    fun `an omitted Length defaults to 40 bits`() {
        val built = build(
            Spec(version = 2, revision = 3, lengthBits = null, streamCfm = Cfm.V2, stringCfm = Cfm.V2)
        )
        assertFixtureIsReal(built, "user-pass")
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
    }

    @Test
    fun `a signed P value and two different ID elements key the file correctly`() {
        // The fixture always writes the first /ID element as A and the second as B, and /P -3904.
        val built = build(Spec(version = 4, revision = 4, permissions = -3904))
        assertFixtureIsReal(built, "user-pass")
        assertPlainPdf(PdfDecryptor.decrypt(built.bytes, "user-pass"))
    }

    @Test
    fun `object generations are preserved in the output`() {
        val built = build(Spec(version = 4, revision = 4, generations = mapOf(3 to 2, 6 to 1)))
        assertFixtureIsReal(built, "user-pass")
        val plain = PdfDecryptor.decrypt(built.bytes, "user-pass")
        val text = String(plain, Charsets.ISO_8859_1)
        assertTrue("page generation 2 kept", Regex("3\\s+2\\s+obj").containsMatchIn(text))
        assertTrue("font generation 1 kept", Regex("6\\s+1\\s+obj").containsMatchIn(text))
        assertPlainPdf(plain)
    }

    // ------------------------------------------------------------------
    // EncryptMetadata
    // ------------------------------------------------------------------

    @Test
    fun `EncryptMetadata false leaves the clear XMP stream untouched`() {
        val built = build(Spec(version = 4, revision = 4, encryptMetadata = false))
        assertFixtureIsReal(built, "user-pass")
        val metadata = built.bytes.copyOfRange(built.metadata.first, built.metadata.last + 1)
        assertTrue("fixture stores XMP in the clear", String(metadata, Charsets.ISO_8859_1).contains("xmpmeta"))
        val plain = PdfDecryptor.decrypt(built.bytes, "user-pass")
        assertPlainPdf(plain)
        val text = String(plain, Charsets.ISO_8859_1)
        assertTrue(
            "clear metadata bytes were copied, not decrypted",
            text.contains(String(metadata, Charsets.ISO_8859_1))
        )
    }

    // ------------------------------------------------------------------
    // Fail closed on structures we cannot rewrite faithfully
    // ------------------------------------------------------------------

    @Test
    fun `an incremental update with Prev is refused`() {
        val built = build(Spec(version = 4, revision = 4, extraTrailer = " /Prev 1234"))
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    @Test
    fun `a hybrid XRefStm trailer is refused`() {
        val built = build(Spec(version = 4, revision = 4, extraTrailer = " /XRefStm 1234"))
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(built.bytes, "user-pass")
        }
    }

    @Test
    fun `an xref stream instead of a classic table is refused`() {
        val xrefStream =
            "5 0 obj\n<< /Type /XRef /Size 6 /W [1 2 1] /Root 1 0 R /Length 0 >>\nstream\n\nendstream\nendobj\n"
        val bytes = ("%PDF-1.7\n" + xrefStream + "startxref\n9\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(bytes, "")
        }
    }

    @Test
    fun `an object stream is refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val patched = replaceOnce(built.bytes, "/Type /Metadata", "/Type /ObjStm")
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `an indirect stream Length is refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val patched = replaceOnce(built.bytes, "/Length ${built.content.count()}", "/Length 9 0 R")
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `a negative stream Length is refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val patched = replaceOnce(built.bytes, "/Length ${built.content.count()}", "/Length -5")
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `an overflowing stream Length is refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val patched = replaceOnce(
            built.bytes, "/Length ${built.content.count()}", "/Length 99999999999999999999"
        )
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `a stream that runs past its object is refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val patched = replaceOnce(
            built.bytes, "/Length ${built.content.count()}", "/Length ${built.content.count() + 64}"
        )
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `mismatched object headers are refused`() {
        val built = build(Spec(version = 4, revision = 4))
        val entryOffset = built.offsets.getValue(3)
        val patched = replaceOnce(
            built.bytes,
            String.format(Locale.ROOT, "%010d 00000 n", entryOffset),
            String.format(Locale.ROOT, "%010d 00000 n", entryOffset + 1)
        )
        assertThrows(PdfDecryptor.Unsupported::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
    }

    @Test
    fun `a truncated AES stream with the right password is not a wrong password`() {
        val built = build(Spec(version = 4, revision = 4, streamCfm = Cfm.AESV2))
        val patched = built.bytes.copyOf()
        // The last ciphertext byte no longer forms valid PKCS#7 padding (0xFF > 16).
        patched[built.content.last] = 0xFF.toByte()
        val failure = assertThrows(Throwable::class.java) {
            PdfDecryptor.decrypt(patched, "user-pass")
        }
        assertTrue(
            "padding failure must not be reported as a wrong password",
            failure is PdfDecryptor.Unsupported
        )
    }

    // ------------------------------------------------------------------
    // Work bounds
    // ------------------------------------------------------------------

    @Test
    fun `many tiny objects finish within a fixed budget`() {
        val extras = (100..3099).map { it to 0 }
        val built = build(Spec(version = 2, revision = 3, extraObjects = extras))
        val started = System.nanoTime()
        val plain = PdfDecryptor.decrypt(built.bytes, "user-pass")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("decrypting 3000 objects took ${elapsedMs}ms", elapsedMs < 20_000)
        assertPlainPdf(plain)
    }
}
