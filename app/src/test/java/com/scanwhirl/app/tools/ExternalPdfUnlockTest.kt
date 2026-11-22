package com.scanwhirl.app.tools

import com.scanwhirl.app.util.PdfDecryptor
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException

class ExternalPdfUnlockTest {
    @get:Rule val temp = TemporaryFolder()

    private fun statement(bits: Int, aes: Boolean, password: String = "ravi-2026"): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            PDDocument().use { doc ->
                repeat(3) { index ->
                    val page = PDPage(if (index == 1) PDRectangle.LETTER else PDRectangle.A4)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use {
                        it.beginText()
                        it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        it.newLineAtOffset(40f, 700f)
                        it.showText("Fictional Ravi statement page ${index + 1}: INR 1234.50")
                        it.endText()
                    }
                }
                doc.protect(StandardProtectionPolicy("owner-fictional", password, AccessPermission()).apply {
                    encryptionKeyLength = bits
                    isPreferAES = aes
                })
                doc.save(bytes, CompressParameters.NO_COMPRESSION)
            }
        }.toByteArray()

    private fun unlock(raw: ByteArray, password: String = "ravi-2026", check: () -> Unit = {}) =
        ExternalPdfUnlock(temp.root, heapBytes = 256L shl 20).unlock(
            raw.inputStream(), raw.size.toLong(), password,
            verifyPages = { file -> Loader.loadPDF(file).use { it.numberOfPages } },
            checkCancelled = check
        )

    @Test fun `external three page ciphers preserve text boxes and source without library recognition`() {
        for ((bits, aes) in listOf(128 to true, 256 to true, 128 to false)) {
            val raw = statement(bits, aes)
            val original = raw.clone()
            val sourceFile = temp.newFile("source-$bits-$aes.pdf").apply { writeBytes(raw) }
            val sourceHash = com.scanwhirl.app.util.Sha256.of(sourceFile)
            val output = sourceFile.inputStream().use { input ->
                ExternalPdfUnlock(temp.root).unlock(input, sourceFile.length(), "ravi-2026",
                    { file -> Loader.loadPDF(file).use { it.numberOfPages } })
            }
            Loader.loadPDF(raw, "ravi-2026").use { source ->
                Loader.loadPDF(output.file).use { plain ->
                    assertFalse(plain.isEncrypted)
                    assertEquals(3, plain.numberOfPages)
                    assertEquals(PDFTextStripper().getText(source), PDFTextStripper().getText(plain))
                    repeat(3) { assertEquals(source.getPage(it).mediaBox, plain.getPage(it).mediaBox) }
                }
            }
            assertFalse(output.file.readText(Charsets.ISO_8859_1).contains("/Encrypt"))
            assertArrayEquals(original, raw)
            assertEquals(sourceHash, com.scanwhirl.app.util.Sha256.of(sourceFile))
            assertEquals(sourceHash, output.sourceHash)
            assertEquals(3, output.pages)
            output.file.delete()
            sourceFile.delete()
        }
    }

    @Test fun `wrong and empty passwords produce no plaintext output`() {
        for ((bits, aes) in listOf(128 to true, 256 to true, 128 to false)) {
            val raw = statement(bits, aes)
            for (password in listOf("", "wrong")) {
                assertThrows(PdfDecryptor.WrongPassword::class.java) { unlock(raw, password) }
                assertTrue(temp.root.listFiles()!!.isEmpty())
            }
        }
        unlock(statement(256, true, ""), "").file.delete() // cryptographically valid empty password
    }

    @Test fun `unsupported xref stream and incremental structure publish nothing`() {
        val raw = statement(128, true)
        val text = raw.toString(Charsets.ISO_8859_1)
        val incremental = text.replace("trailer\n<<", "trailer\n<< /Prev 1 ")
        assertNotEquals(text, incremental)
        assertThrows(PdfDecryptor.CannotRewrite::class.java) {
            unlock(incremental.toByteArray(Charsets.ISO_8859_1))
        }
        val stream = "%PDF-1.7\n1 0 obj\n<< /Type /XRef /Length 0 >>\nstream\n\nendstream\nendobj\nstartxref\n9\n%%EOF\n"
        assertThrows(PdfDecryptor.CannotRewrite::class.java) {
            unlock(stream.toByteArray())
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `40 MB on 256 MB heap is refused before reading`() {
        val input = object : InputStream() {
            override fun read(): Int = throw AssertionError("must refuse before reading")
        }
        assertThrows(ExternalPdfUnlock.TooLarge::class.java) {
            ExternalPdfUnlock(temp.root, 256L shl 20).unlock(input, 40L shl 20, "secret", { 3 })
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `unknown length cannot exceed budget`() {
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(0, off, off + len)
                return len
            }
        }
        assertThrows(ExternalPdfUnlock.TooLarge::class.java) {
            ExternalPdfUnlock(temp.root, 256L shl 20).unlock(input, -1, "secret", { 3 })
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `cancel after plaintext write removes all temporary files`() {
        val raw = statement(128, true)
        assertThrows(CancellationException::class.java) {
            ExternalPdfUnlock(temp.root).unlock(raw.inputStream(), raw.size.toLong(), "ravi-2026",
                verifyPages = { throw CancellationException() })
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `failed verification never publishes partial output`() {
        val raw = statement(128, true)
        assertThrows(IllegalStateException::class.java) {
            ExternalPdfUnlock(temp.root).unlock(raw.inputStream(), raw.size.toLong(), "ravi-2026", { 0 })
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun `cancel immediately after rename still removes published plaintext`() {
        val raw = statement(128, true)
        assertThrows(CancellationException::class.java) {
            unlock(raw, check = {
                if (temp.root.listFiles()!!.any { it.extension == "pdf" }) throw CancellationException()
            })
        }
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
