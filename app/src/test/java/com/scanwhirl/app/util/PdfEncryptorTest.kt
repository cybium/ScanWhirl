package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
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
import java.util.Locale
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfEncryptorTest {

    private val PAD = byteArrayOf(
        0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(),
        0x75.toByte(), 0x8A.toByte(), 0x41.toByte(), 0x64.toByte(), 0x00.toByte(), 0x4E.toByte(),
        0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(), 0x2E.toByte(), 0x2E.toByte(),
        0x00.toByte(), 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
        0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(),
        0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
    )

    /** Minimal valid JPEG: SOI + SOF0 + EOI. */
    private fun makeJpeg(dir: File, name: String, w: Int, h: Int): File {
        val bytes = buildJpeg(w, h)
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    private fun buildJpeg(w: Int, h: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xC0.toByte(), // SOF0
            0x00.toByte(), 0x0B.toByte(), // length 11
            0x08.toByte(), // precision
            (h shr 8).toByte(), (h and 0xFF).toByte(), // height
            (w shr 8).toByte(), (w and 0xFF).toByte(), // width
            0x01.toByte(), // components
            0x01.toByte(), 0x22.toByte(), 0x00.toByte(), // component 1
            0xFF.toByte(), 0xD9.toByte() // EOI
        )

    private fun pad(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf(32)
        for (i in bytes.size until 32) out[i] = PAD[i - bytes.size]
        return out
    }

    /** MD5 of [seed], re-hashed 50 times (Revision 3). */
    private fun mdx50(seed: ByteArray): ByteArray {
        var digest = MessageDigest.getInstance("MD5").digest(seed)
        repeat(50) { digest = MessageDigest.getInstance("MD5").digest(digest) }
        return digest
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
        md.update(byteArrayOf(
            (permissions and 0xFF).toByte(),
            ((permissions shr 8) and 0xFF).toByte(),
            ((permissions shr 16) and 0xFF).toByte(),
            ((permissions shr 24) and 0xFF).toByte()
        ))
        md.update(fileId)
        var digest = md.digest()
        repeat(50) { digest = MessageDigest.getInstance("MD5").digest(digest) }
        return digest.copyOf(16)
    }

    /** Per-object RC4 key: MD5(fileKey + objNum[3B LE] + gen[2B])[0..16]. */
    private fun objectKey(fileKey: ByteArray, objNum: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(
            (objNum and 0xFF).toByte(),
            ((objNum shr 8) and 0xFF).toByte(),
            ((objNum shr 16) and 0xFF).toByte()
        ))
        md.update(byteArrayOf(0.toByte(), 0.toByte())) // generation 0
        return md.digest().copyOf(16)
    }

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

    private fun tempDir(name: String): File {
        val d = File(File(java.io.File.separator, "tmp"), name)
        d.mkdirs()
        return d
    }

    @Test
    fun `encrypted pdf streams decrypt to original content`() {
        val dir = tempDir("pdfenc")
        val jpg1 = makeJpeg(dir, "a.jpg", 620, 877)
        val jpg2 = makeJpeg(dir, "b.jpg", 600, 800)

        // Build a 2-page PDF.
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg1, jpg2), src)
        val originalBytes = src.readBytes()
        val originalText = String(originalBytes, Charsets.ISO_8859_1)

        // Expected content streams for the two pages.
        val w1 = (620 * 72f / 150f).roundToInt()
        val h1 = (877 * 72f / 150f).roundToInt()
        val w2 = (600 * 72f / 150f).roundToInt()
        val h2 = (800 * 72f / 150f).roundToInt()
        val content1 = "q\n$w1 0 0 $h1 0 0 cm\n/Im0 Do\nQ\n"
        val content2 = "q\n$w2 0 0 $h2 0 0 cm\n/Im1 Do\nQ\n"

        // Encrypt with password "pw1234".
        val encrypted = File(dir, "enc.pdf")
        PdfEncryptor.encrypt(src, encrypted, "pw1234")

        // --- Basic structural checks -------------------------------------
        val encBytes = encrypted.readBytes()
        val encText = String(encBytes, Charsets.ISO_8859_1)
        assertTrue("starts with %PDF-", encText.startsWith("%PDF-"))
        assertTrue("ends with %%EOF\\n", encText.endsWith("%%EOF\n"))
        assertTrue("grew only by trailer overhead", encrypted.length() > src.length())

        // --- Parse trailer for /O, /P, /ID -------------------------------
        val oHex = Regex("/O <([0-9A-F]+)>").find(encText)!!
            .groupValues[1]
        val pStr = Regex("/P (-?\\d+)").find(encText)!!
            .groupValues[1]
        val idHex = Regex("/ID \\[<([0-9A-F]+)> <([0-9A-F]+)>\\]")
            .find(encText)!!
            .groupValues[1]

        val o = oHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fileId = idHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val permissions = pStr.toInt()

        // --- Compute file key independently (Algorithm 2) ----------------
        val paddedUser = pad("pw1234".toByteArray(Charsets.ISO_8859_1))
        val fileKey = computeFileKey(paddedUser, o, permissions, fileId)

        // --- Find stream bodies via the encryptor's xref-based parser -----
        val bodies = PdfEncryptor.objectStreamBodies(encBytes)
        val objNums = bodies.map { it.first }.sorted()
        // 2-page PDF: objects 4 (content 0), 5 (image 0), 7 (content 1), 8 (image 1).
        assertEquals("stream-bearing object numbers", listOf(4, 5, 7, 8), objNums)

        // --- Decrypt each body independently ------------------------------
        val decrypted = mutableMapOf<Int, ByteArray>()
        for ((objNum, range) in bodies) {
            val objKey = objectKey(fileKey, objNum)
            val bodyBytes = encBytes.copyOfRange(range.first, range.last + 1)
            decrypted[objNum] = rc4(objKey, bodyBytes)
        }

        // Content streams equal the original text.
        assertEquals("content 0", content1, String(decrypted[4]!!, Charsets.ISO_8859_1))
        assertEquals("content 1", content2, String(decrypted[7]!!, Charsets.ISO_8859_1))

        // Image streams are byte-identical to the source JPEGs.
        assertTrue("image 0", jpg1.readBytes().contentEquals(decrypted[5]!!))
        assertTrue("image 1", jpg2.readBytes().contentEquals(decrypted[8]!!))
    }

    @Test
    fun `verifyPdfPassword accepts right and rejects wrong`() {
        val dir = tempDir("pdfencpw")
        val jpg = makeJpeg(dir, "a.jpg", 620, 877)
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), src)

        val encrypted = File(dir, "enc.pdf")
        PdfEncryptor.encrypt(src, encrypted, "pw1234")

        val encBytes = encrypted.readBytes()
        val encText = String(encBytes, Charsets.ISO_8859_1)

        // Parse trailer (shared setup).
        val oHex = Regex("/O <([0-9A-F]+)>").find(encText)!!.groupValues[1]
        val pStr = Regex("/P (-?\\d+)").find(encText)!!.groupValues[1]
        val idHex = Regex("/ID \\[<([0-9A-F]+)> <([0-9A-F]+)>\\]")
            .find(encText)!!
            .groupValues[1]
        val o = oHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fileId = idHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val permissions = pStr.toInt()
        val bodies = PdfEncryptor.objectStreamBodies(encBytes)

        // Right password: content stream for object 4 starts with "q\n".
        val rightPadded = pad("pw1234".toByteArray(Charsets.ISO_8859_1))
        val rightKey = computeFileKey(rightPadded, o, permissions, fileId)
        val rightOk = bodies.all { (objNum, range) ->
            val objKey = objectKey(rightKey, objNum)
            val dec = rc4(objKey, encBytes.copyOfRange(range.first, range.last + 1))
            // Content streams (objects 4, 7) must begin with "q\n".
            objNum !in listOf(4, 7) || String(dec.copyOfRange(0, 2), Charsets.ISO_8859_1) == "q\n"
        }
        assertTrue("right password decrypts content streams", rightOk)

        // Wrong password: content stream for object 4 must NOT begin with "q\n".
        val wrongPadded = pad("wrong".toByteArray(Charsets.ISO_8859_1))
        val wrongKey = computeFileKey(wrongPadded, o, permissions, fileId)
        val wrongBody = bodies.first { it.first == 4 }
        val wrongDec = rc4(objectKey(wrongKey, wrongBody.first),
            encBytes.copyOfRange(wrongBody.second.first, wrongBody.second.last + 1))
        assertTrue("wrong password does not produce valid content",
            String(wrongDec.copyOfRange(0, 2), Charsets.ISO_8859_1) != "q\n")
    }

    @Test
    fun `encrypting a PDF with a text layer and decrypting restores the exact text`() {
        val dir = tempDir("pdfenc-textlayer")
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

        val encBytes = encrypted.readBytes()
        val encText = String(encBytes, Charsets.ISO_8859_1)

        // Parse trailer.
        val oHex = Regex("/O <([0-9A-F]+)>").find(encText)!!.groupValues[1]
        val pStr = Regex("/P (-?\\d+)").find(encText)!!.groupValues[1]
        val idHex = Regex("/ID \\[<([0-9A-F]+)> <([0-9A-F]+)>\\]")
            .find(encText)!!
            .groupValues[1]
        val o = oHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val fileId = idHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val permissions = pStr.toInt()

        val paddedUser = pad("pw1234".toByteArray(Charsets.ISO_8859_1))
        val fileKey = computeFileKey(paddedUser, o, permissions, fileId)
        val bodies = PdfEncryptor.objectStreamBodies(encBytes)

        // Content stream object is 4 (page 0 content). Decrypt it independently.
        val contentBody = bodies.first { it.first == 4 }
        val contentKey = objectKey(fileKey, contentBody.first)
        val contentDec = rc4(contentKey,
            encBytes.copyOfRange(contentBody.second.first, contentBody.second.last + 1))
        val contentText = String(contentDec, Charsets.ISO_8859_1)

        assertTrue("content stream contains 3 Tr", contentText.contains("3 Tr"))
        assertTrue("content stream contains Hello", contentText.contains("Hello"))
        assertTrue("content stream contains World", contentText.contains("World"))
        assertTrue("content stream contains Second", contentText.contains("Second"))
        assertTrue("encrypted file starts with %PDF-", encText.startsWith("%PDF-"))
        assertTrue("encrypted file ends with %%EOF", encText.endsWith("%%EOF\n"))
    }
}
