package com.scanwhirl.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISO 32000-2 Standard Security Handler V5/R6 (AESV3, AES-256).
 *
 * Every expectation here is recomputed by an INDEPENDENT implementation of Algorithm 2.B, 8 and 9 in
 * this file (the same way PdfEncryptorTest re-implemented RC4 for the old handler), so a bug in the
 * production code cannot make the test pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfAesTest {

    @get:Rule val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Independent reference implementation (test side, from ISO 32000-2)
    // ------------------------------------------------------------------

    private val zeroIv = ByteArray(16)

    private fun refPassword(pw: String): ByteArray {
        val bytes = Normalizer.normalize(pw, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return if (bytes.size <= 127) bytes else bytes.copyOf(127)
    }

    private fun digest(alg: String, data: ByteArray): ByteArray =
        MessageDigest.getInstance(alg).digest(data)

    private fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    /** Algorithm 2.B, R6: iterated AES-128-CBC + SHA-2 (64 rounds, then until E_last <= round - 32). */
    private fun refHash(password: ByteArray, salt: ByteArray, userData: ByteArray = ByteArray(0)): ByteArray {
        var k = digest("SHA-256", password + salt + userData)
        var e = ByteArray(0)
        var rounds = 0
        while (rounds < 64 || (e[e.size - 1].toInt() and 0xFF) > rounds - 32) {
            val unit = password + k + userData
            val k1 = ByteArray(unit.size * 64)
            for (r in 0 until 64) unit.copyInto(k1, r * unit.size)
            e = cbc(k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1, encrypt = true)
            val selector = (0 until 16).sumOf { e[it].toInt() and 0xFF } % 3
            k = when (selector) {
                0 -> digest("SHA-256", e)
                1 -> digest("SHA-384", e)
                else -> digest("SHA-512", e)
            }
            rounds++
        }
        return k.copyOf(32)
    }

    /** Algorithm 10: the file key of a user password, or null. */
    private fun refUserKey(pw: String, u: ByteArray, ue: ByteArray): ByteArray? {
        val password = refPassword(pw)
        val validation = u.copyOfRange(32, 40)
        if (!MessageDigest.isEqual(refHash(password, validation), u.copyOfRange(0, 32))) return null
        val intermediate = refHash(password, u.copyOfRange(40, 48))
        return cbc(intermediate, zeroIv, ue, encrypt = false)
    }

    private fun refOwnerKey(pw: String, o: ByteArray, oe: ByteArray, u: ByteArray): ByteArray? {
        val password = refPassword(pw)
        if (!MessageDigest.isEqual(refHash(password, o.copyOfRange(32, 40), u), o.copyOfRange(0, 32))) return null
        return cbc(refHash(password, o.copyOfRange(40, 48), u), zeroIv, oe, encrypt = false)
    }

    private fun fromHex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun entry(pdf: File, tag: String): ByteArray {
        val text = pdf.readText(Charsets.ISO_8859_1)
        val m = Regex("/$tag <([0-9A-Fa-f]+)>").find(text) ?: throw AssertionError("no /$tag")
        return fromHex(m.groupValues[1])
    }

    /** Decrypts a stream body independently: 16-byte IV prefix, then AES-256-CBC/PKCS#7. */
    private fun refDecryptStream(fileKey: ByteArray, ivAndData: ByteArray): ByteArray {
        require(ivAndData.size > 16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(fileKey, "AES"),
            IvParameterSpec(ivAndData.copyOfRange(0, 16))
        )
        return c.doFinal(ivAndData, 16, ivAndData.size - 16)
    }

    private fun image(name: String, w: Int = 120, h: Int = 80): File =
        File(tmp.root, name).apply { writeBytes(ImportFixtures.image(width = w, height = h)) }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `the right user password opens the document and a wrong one does not`() {
        val enc = PdfAes.create("correct horse battery")
        assertArrayEquals("user key is the file key", enc.fileKey, PdfAes.userKey("correct horse battery", enc.u, enc.ue))
        assertNull("a wrong password must not authenticate", PdfAes.userKey("correct horse batterY", enc.u, enc.ue))
        assertNull("an empty password must not authenticate", PdfAes.userKey("", enc.u, enc.ue))
        assertArrayEquals(
            "the owner password opens too",
            enc.fileKey,
            PdfAes.ownerKey("correct horse battery", enc.o, enc.oe, enc.u)
        )
    }

    @Test
    fun `u ue o oe and perms follow algorithms 2b 8 9 and 10`() {
        val enc = PdfAes.create("s3cret-pw")
        val pw = "s3cret-pw"

        // /U = hash(pw, validation salt) || validation salt || key salt
        assertEquals(48, enc.u.size)
        assertArrayEquals(
            "U[0:32] is Algorithm 2.B with the validation salt",
            refHash(refPassword(pw), enc.u.copyOfRange(32, 40)),
            enc.u.copyOfRange(0, 32)
        )
        assertArrayEquals(
            "UE decrypts to the file key",
            enc.fileKey,
            cbc(refHash(refPassword(pw), enc.u.copyOfRange(40, 48)), zeroIv, enc.ue, encrypt = false)
        )
        assertArrayEquals(
            "UE decrypts to the file key (independent user-key path)",
            enc.fileKey,
            refUserKey(pw, enc.u, enc.ue)
        )

        // Owner entry (Algorithm 9) with the same password, independent path; /U is the user data.
        assertEquals(48, enc.o.size)
        assertArrayEquals(enc.fileKey, refOwnerKey(pw, enc.o, enc.oe, enc.u))
        assertNull(refOwnerKey("nope", enc.o, enc.oe, enc.u))

        // /Perms (16 bytes): AES-256-ECB of P || FF FF FF FF || 'T' || 'a' 'd' 'b' || random.
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc.fileKey, "AES"))
        val block = ecb.doFinal(enc.perms)
        assertEquals(16, block.size)
        val p = (block[0].toInt() and 0xFF) or ((block[1].toInt() and 0xFF) shl 8) or
            ((block[2].toInt() and 0xFF) shl 16) or ((block[3].toInt() and 0xFF) shl 24)
        assertEquals("P inside /Perms", enc.permissions, p)
        assertEquals(0xFF, block[4].toInt() and 0xFF)
        assertEquals(0xFF, block[7].toInt() and 0xFF)
        assertEquals('T'.code, block[8].toInt())
        assertEquals("adb", String(block, 9, 3, Charsets.ISO_8859_1))
    }

    /**
     * Algorithm 13 also fixes bytes 4-7 of the plaintext permission block at FF FF FF FF. A block
     * that decrypts with the right key, the right /P and "adb" but a different constant has been
     * rewritten by something else (a permission editor, a corrupted /Encrypt dictionary) and must
     * be refused instead of trusted.
     */
    @Test
    fun `a Perms block whose fixed FF vector was changed is refused`() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val permissions = PdfAes.P_DEFAULT
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        fun sealed(block: ByteArray): ByteArray = ecb.doFinal(block)
        val fixed = ByteArray(16)
        for (i in 0..3) fixed[i] = ((permissions ushr (8 * i)) and 0xFF).toByte()
        for (i in 4..7) fixed[i] = 0xFF.toByte()
        fixed[8] = 'T'.code.toByte()
        fixed[9] = 'a'.code.toByte()
        fixed[10] = 'd'.code.toByte()
        fixed[11] = 'b'.code.toByte()
        fixed[12] = 0x11
        fixed[13] = 0x22
        fixed[14] = 0x33
        fixed[15] = 0x44

        assertTrue("the untouched vector must verify", PdfAes.permsValid(key, sealed(fixed), permissions, true))
        for (index in 4..7) {
            val changed = fixed.copyOf()
            changed[index] = 0x00
            assertFalse(
                "byte $index of the fixed vector was altered",
                PdfAes.permsValid(key, sealed(changed), permissions, true)
            )
        }
        // The trailing four bytes are defined as random: changing them is not a failure.
        val randomPart = fixed.copyOf()
        randomPart[15] = 0x55
        assertTrue("the random tail is not part of the fixed vector", PdfAes.permsValid(key, sealed(randomPart), permissions, true))
    }

    @Test
    fun `unicode passwords are NFKC normalised as UTF-8 and cut at 127 bytes`() {        // NFKC maps the ligature ﬁ (U+FB01) to "fi": both spellings are the same password.
        val enc = PdfAes.create("ﬁle密码123")
        assertNotNull("NFKC equivalent spelling opens", PdfAes.userKey("file密码123", enc.u, enc.ue))
        assertNull("a different password stays wrong", PdfAes.userKey("file密码124", enc.u, enc.ue))

        // Decomposed "é" (e + U+0301) normalises to the composed form.
        val composed = PdfAes.create("café")
        assertNotNull(PdfAes.userKey("cafe\u0301", composed.u, composed.ue))

        // The spec caps a password at 127 bytes of UTF-8.
        val prefix = "p".repeat(127)
        val long = PdfAes.create(prefix + "TAIL")
        assertNotNull("bytes after the 127th are ignored", PdfAes.userKey(prefix + "OTHER", long.u, long.ue))
        assertEquals(127, PdfAes.normalizePassword(prefix + "TAIL").size)
    }

    @Test
    fun `generator encrypts every stream with aes 256 and the verifier decrypts it`() {
        val images = listOf(image("a.jpg", 620, 877), image("b.jpg", 600, 800))
        val out = File(tmp.root, "aes.pdf")
        val enc = PdfAes.create("pw1234")

        PdfGenerator.imagesToPdf(images, out, encryption = enc)

        assertEquals("the trailer carries /Encrypt", true, PdfProtection.isEncrypted(out))
        val text = out.readText(Charsets.ISO_8859_1)
        assertTrue("the /Encrypt value is a dictionary", text.contains("/Encrypt << /Filter /Standard"))
        assertTrue("/V 5", text.contains("/V 5"))
        assertTrue("/R 6", text.contains("/R 6"))
        assertTrue("AESV3 crypt filter", text.contains("/CFM /AESV3"))
        assertTrue("/ID present", text.contains("/ID [<"))

        // The verifier decrypts content and image streams with the same key.
        PdfVerifier.verify(out, expectedImages = images, decryption = enc)

        // Independent decryption of every stream.
        val bytes = out.readBytes()
        val u = entry(out, "U")
        val ue = entry(out, "UE")
        val fileKey = refUserKey("pw1234", u, ue) ?: throw AssertionError("reference 2.B could not open the file")
        assertArrayEquals("reference file key", enc.fileKey, fileKey)

        val bodies = PdfEncryptor.objectStreamBodies(bytes)
        assertEquals("content + image per page", 4, bodies.size)
        val byObject = bodies.associate { it.first to refDecryptStream(fileKey, bytes.copyOfRange(it.second.first, it.second.last + 1)) }
        // Objects 4/7 are the content streams, 5/8 the images (PdfGenerator layout).
        assertTrue(String(byObject.getValue(4), Charsets.ISO_8859_1).contains("/Im0 Do"))
        assertTrue(String(byObject.getValue(7), Charsets.ISO_8859_1).contains("/Im1 Do"))
        assertArrayEquals(images[0].readBytes(), byObject.getValue(5))
        assertArrayEquals(images[1].readBytes(), byObject.getValue(8))
    }

    @Test
    fun `post hoc encryption of a generated pdf keeps every page image`() {
        val images = listOf(image("c.jpg", 300, 400))
        val src = File(tmp.root, "plain.pdf")
        PdfGenerator.imagesToPdf(images, src)
        assertFalse(PdfProtection.isEncrypted(src) == true)

        val dst = File(tmp.root, "locked.pdf")
        PdfEncryptor.encrypt(src, dst, "pw1234")

        assertEquals(true, PdfProtection.isEncrypted(dst))
        assertTrue(
            "the /Encrypt value points to an indirect standard security dictionary",
            dst.readText(Charsets.ISO_8859_1).let { pdf ->
                val number = Regex("/Encrypt (\\d+) 0 R").find(pdf)?.groupValues?.get(1)
                number != null && pdf.contains("\n$number 0 obj\n<< /Filter /Standard")
            }
        )

        val bytes = dst.readBytes()
        val fileKey = refUserKey("pw1234", entry(dst, "U"), entry(dst, "UE"))
            ?: throw AssertionError("reference 2.B could not open the rewritten file")
        // The structure is only verifiable with the key: the streams are ciphertext now.
        PdfVerifier.verify(dst, decryption = PdfAes.decryptor(fileKey), eolBeforeContentEndstream = true)
        val bodies = PdfEncryptor.objectStreamBodies(bytes)
        val imageBody = bodies.first { it.first == 5 }
        assertArrayEquals(
            "the page JPEG survives the rewrite byte for byte",
            images[0].readBytes(),
            refDecryptStream(fileKey, bytes.copyOfRange(imageBody.second.first, imageBody.second.last + 1))
        )
        val contentBody = bodies.first { it.first == 4 }
        val content = String(
            refDecryptStream(fileKey, bytes.copyOfRange(contentBody.second.first, contentBody.second.last + 1)),
            Charsets.ISO_8859_1
        )
        assertTrue("the content stream still draws the page image", content.contains("/Im0 Do"))
        assertNull(
            "a wrong password cannot derive the key",
            refUserKey("pw12345", entry(dst, "U"), entry(dst, "UE"))
        )
    }
}
