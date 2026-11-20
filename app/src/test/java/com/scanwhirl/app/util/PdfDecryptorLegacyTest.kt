package com.scanwhirl.app.util

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X1: real-world PDFs use the older standard-handler revisions as well as AES-256. AES-128 and
 * RC4 (40/128) invoices, bank statements and government forms are common in India, and some
 * writers reference the `/Encrypt` dictionary indirectly (`/Encrypt 6 0 R`) instead of inlining it.
 *
 * The fixtures are copies of the CC0 corpus folder `qa/corpus/pdf_encrypted` (SOURCES.csv, SHA-256
 * checked below): self-generated plus FynePDF's public AES-128 sample. PDFBox is a test-only
 * oracle (never in the APK); the decryption under test is the app's own offline decryptor.
 */
class PdfDecryptorLegacyTest {

    /** A corpus fixture, its user password and its page count (SOURCES.csv). */
    private data class Case(val file: String, val password: String, val pages: Int, val sha256: String)

    private val cases = listOf(
        Case(
            "aes128_invoice_legal.pdf", "legal-2026", 1,
            "d585ce1fdc28da5e7aa17d690552f8800faae02155b1f5ead0ec7d5880ffb641"
        ),
        Case(
            "rc4_legacy_oldpass.pdf", "oldpass", 1,
            "d0ea5aa111bfccc3165cd981dd53f0f2861e18ac4b0008817c24fe060a331676"
        ),
        Case(
            "fyne_password_protected.pdf", "fynepdf", 1,
            "fa47d702af788108698c08df0ff6f950c9e02fcebf2f55aade18b3e71c6453c8"
        )
    )

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/pdf_encrypted/$name")!!.use { it.readBytes() }

    private fun bytesEncrypted(bytes: ByteArray): Boolean {
        val tmp = File.createTempFile("decrypt-out-", ".pdf")
        return try {
            tmp.writeBytes(bytes)
            PdfProtection.isEncrypted(tmp) != false
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `the fixtures are the recorded CC0 corpus bytes`() {
        for (c in cases) {
            assertEquals(c.file, c.sha256, Sha256.of(fixture(c.file).inputStream()))
        }
        assertEquals(
            "aes256_owner_only.pdf",
            "d8a874ea9d28f826fffb383351d89892766e6e0106a5e93947db14ef5d8f85dd",
            Sha256.of(fixture("aes256_owner_only.pdf").inputStream())
        )
    }

    @Test
    fun `older RC4 and AES-128 files open with the right password and keep pages and text`() {
        for (c in cases) {
            val raw = fixture(c.file)
            val plain = try {
                PdfDecryptor.decrypt(raw, c.password)
            } catch (e: PdfDecryptor.Unsupported) {
                throw AssertionError("${c.file} is a valid PDF we refuse: ${e.message}", e)
            } catch (e: PdfDecryptor.WrongPassword) {
                throw AssertionError("${c.file} rejected its own user password", e)
            }
            assertFalse("${c.file}: output still carries /Encrypt", bytesEncrypted(plain))
            Loader.loadPDF(raw, c.password).use { source ->
                val expectedText = PDFTextStripper().getText(source)
                assertEquals(c.file, c.pages, source.numberOfPages)
                assertTrue("${c.file}: fixture has no text to compare", expectedText.isNotBlank())
                // No password: this only opens when the output really is plain.
                Loader.loadPDF(plain).use { out ->
                    assertFalse("${c.file}: output is still encrypted", out.isEncrypted)
                    assertEquals(c.file, source.numberOfPages, out.numberOfPages)
                    assertEquals(c.file, expectedText, PDFTextStripper().getText(out))
                }
            }
        }
    }

    @Test
    fun `a wrong password is refused cleanly on every older fixture`() {
        for (c in cases) {
            assertThrows("${c.file} accepted a wrong password", PdfDecryptor.WrongPassword::class.java) {
                PdfDecryptor.decrypt(fixture(c.file), "definitely-not-the-password")
            }
        }
    }

    @Test
    fun `an owner-password-only PDF opens with its empty user password`() {
        val raw = fixture("aes256_owner_only.pdf")
        // The document's user password is empty (only an owner password exists), so it opens
        // without a prompt; an empty password is verified, not assumed.
        val plain = PdfDecryptor.decrypt(raw, "")
        assertFalse("owner-only output still carries /Encrypt", bytesEncrypted(plain))
        Loader.loadPDF(raw, "").use { source ->
            assertEquals(1, source.numberOfPages)
            val expectedText = PDFTextStripper().getText(source)
            assertTrue("owner-only fixture has no text to compare", expectedText.isNotBlank())
            Loader.loadPDF(plain).use { out ->
                assertFalse("owner-only output is still encrypted", out.isEncrypted)
                assertEquals(source.numberOfPages, out.numberOfPages)
                assertEquals(expectedText, PDFTextStripper().getText(out))
            }
        }
        assertThrows("owner-only file accepted a wrong password", PdfDecryptor.WrongPassword::class.java) {
            PdfDecryptor.decrypt(raw, "definitely-not-the-password")
        }
    }
}
