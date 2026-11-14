package com.scanwhirl.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * The v2 protect path is AES-256 (V5/R6, AESV3). This test opens the protected file exactly the
 * way an independent reader would: it re-implements Algorithm 2.B and the AES-CBC stream layer in
 * the test (as this file used to do for RC4), derives the file key from /U + /UE and decrypts every
 * stream body byte-for-byte.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfEncryptorTest {

    private val zeroIv = ByteArray(16)

    private fun tempDir(name: String): File {
        val d = File(File(java.io.File.separator, "tmp"), name)
        d.mkdirs()
        return d
    }

    private fun makeJpeg(dir: File, name: String, w: Int, h: Int): File {
        val f = File(dir, name)
        f.writeBytes(ImportFixtures.image(width = w, height = h))
        return f
    }

    // ---------------- independent implementation (test side) ----------------

    private fun refPassword(pw: String): ByteArray {
        val bytes = Normalizer.normalize(pw, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return if (bytes.size <= 127) bytes else bytes.copyOf(127)
    }

    private fun digest(alg: String, data: ByteArray): ByteArray =
        MessageDigest.getInstance(alg).digest(data)

    private fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return c.doFinal(data)
    }

    /** Algorithm 2.B (R6), written from the spec, not shared with production. */
    private fun refHash(password: ByteArray, salt: ByteArray): ByteArray {
        var k = digest("SHA-256", password + salt)
        var e = ByteArray(0)
        var rounds = 0
        while (rounds < 64 || (e[e.size - 1].toInt() and 0xFF) > rounds - 32) {
            val unit = password + k
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

    private fun refFileKey(pw: String, u: ByteArray, ue: ByteArray): ByteArray? {
        val password = refPassword(pw)
        if (!MessageDigest.isEqual(refHash(password, u.copyOfRange(32, 40)), u.copyOfRange(0, 32))) return null
        return cbc(refHash(password, u.copyOfRange(40, 48)), zeroIv, ue, encrypt = false)
    }

    private fun fromHex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun entry(text: String, tag: String): ByteArray {
        val m = Regex("/$tag <([0-9A-Fa-f]+)>").find(text) ?: throw AssertionError("no /$tag")
        return fromHex(m.groupValues[1])
    }

    /** IV-prefixed AES-256-CBC/PKCS#7, decrypted independently of PdfAes. */
    private fun refDecryptStream(fileKey: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(fileKey, "AES"),
            IvParameterSpec(data.copyOfRange(0, 16))
        )
        return c.doFinal(data, 16, data.size - 16)
    }

    /** True when [needle] appears in [haystack] (no library needed). */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun streams(encrypted: File, password: String): Pair<ByteArray, Map<Int, ByteArray>> {
        val bytes = encrypted.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val key = refFileKey(password, entry(text, "U"), entry(text, "UE"))
            ?: throw AssertionError("reference Algorithm 2.B could not derive the key")
        val decrypted = PdfEncryptor.objectStreamBodies(bytes).associate { (num, range) ->
            val body = bytes.copyOfRange(range.first, range.last + 1)
            assertTrue(
                "object $num body must be IV + whole AES blocks (size ${body.size})",
                body.size > 16 && (body.size - 16) % 16 == 0
            )
            num to refDecryptStream(key, body)
        }
        return key to decrypted
    }

    // ---------------- tests ----------------

    @Test
    fun `encrypted pdf streams decrypt to original content`() {
        val dir = tempDir("pdfaes-enc")
        val jpg1 = makeJpeg(dir, "a.jpg", 620, 877)
        val jpg2 = makeJpeg(dir, "b.jpg", 600, 800)

        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg1, jpg2), src)

        val encrypted = File(dir, "enc.pdf")
        PdfEncryptor.encrypt(src, encrypted, "pw1234")

        // --- structural shape of an R6 file -------------------------------
        val encBytes = encrypted.readBytes()
        val encText = String(encBytes, Charsets.ISO_8859_1)
        assertTrue("starts with %PDF-", encText.startsWith("%PDF-"))
        assertTrue("ends with %%EOF\\n", encText.endsWith("%%EOF\n"))
        assertTrue("/V 5 /R 6", encText.contains("/V 5") && encText.contains("/R 6"))
        assertTrue("/CFM /AESV3", encText.contains("/CFM /AESV3"))
        assertTrue("streams grow: IV + padding", encrypted.length() > src.length())
        assertTrue("no plaintext JPEG in the wrapper", !contains(encBytes, jpg1.readBytes()))

        // --- decrypt every body independently -----------------------------
        val w1 = (620 * 72f / 150f).roundToInt()
        val h1 = (877 * 72f / 150f).roundToInt()
        val w2 = (600 * 72f / 150f).roundToInt()
        val h2 = (800 * 72f / 150f).roundToInt()
        val content1 = "q\n$w1 0 0 $h1 0 0 cm\n/Im0 Do\nQ\n"
        val content2 = "q\n$w2 0 0 $h2 0 0 cm\n/Im1 Do\nQ\n"

        val (_, decrypted) = streams(encrypted, "pw1234")
        assertEquals("content + image per page", setOf(4, 5, 7, 8), decrypted.keys)
        assertEquals("content 0", content1, String(decrypted.getValue(4), Charsets.ISO_8859_1))
        assertEquals("content 1", content2, String(decrypted.getValue(7), Charsets.ISO_8859_1))
        assertArrayEquals("image 0", jpg1.readBytes(), decrypted.getValue(5))
        assertArrayEquals("image 1", jpg2.readBytes(), decrypted.getValue(8))
    }

    @Test
    fun `the right password derives the file key and a wrong one does not`() {
        val dir = tempDir("pdfaes-pw")
        val jpg = makeJpeg(dir, "a.jpg", 620, 877)
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), src)

        val encrypted = File(dir, "enc.pdf")
        PdfEncryptor.encrypt(src, encrypted, "pw1234")
        val text = encrypted.readText(Charsets.ISO_8859_1)
        val u = entry(text, "U")
        val ue = entry(text, "UE")

        val right = refFileKey("pw1234", u, ue)
        assertTrue("the right password opens the file", right != null)
        // streams() decrypts every body; take the content stream as text, not another ciphertext.
        val content = streams(encrypted, "pw1234").second.getValue(4)
        assertTrue("content stream starts with q", String(content, Charsets.ISO_8859_1).startsWith("q\n"))

        // The wrong password fails the Algorithm 10 validation hash, so no key exists at all.
        assertNull("a wrong password must not authenticate", refFileKey("wrong", u, ue))
        assertNull(refFileKey("PW1234", u, ue))

        // The production reader agrees with the reference one.
        assertTrue(PdfAes.userKey("pw1234", u, ue)!!.contentEquals(right))
    }

    @Test
    fun `encrypting a PDF with a text layer and decrypting restores the exact text`() {
        val dir = tempDir("pdfaes-textlayer")
        val jpg = makeJpeg(dir, "a.jpg", 620, 877)
        val src = File(dir, "src.pdf")
        val layer = OcrPage(620, 877,
            listOf(
                OcrLine("Hello World", 10, 20, 50, 40),
                OcrLine("Second line", 10, 60, 60, 80)
            )
        )
        PdfGenerator.imagesToPdf(listOf(jpg), src, mapOf(0 to layer))

        val encrypted = File(dir, "enc.pdf")
        PdfEncryptor.encrypt(src, encrypted, "pw1234")

        val content = String(streams(encrypted, "pw1234").second.getValue(4), Charsets.ISO_8859_1)
        assertTrue("content stream contains 3 Tr", content.contains("3 Tr"))
        assertTrue("content stream contains Hello", content.contains("Hello"))
        assertTrue("content stream contains World", content.contains("World"))
        assertTrue("content stream contains Second", content.contains("Second"))
        assertTrue("encrypted file starts with %PDF-", encrypted.readText(Charsets.ISO_8859_1).startsWith("%PDF-"))
    }
}
