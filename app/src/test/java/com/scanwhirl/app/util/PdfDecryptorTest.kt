package com.scanwhirl.app.util

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Importing a password-protected ScanWhirl PDF: the copy is decrypted offline and its pages are
 * then imported. The original file is never modified, and the page JPEGs must stay byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfDecryptorTest {

    private fun page(width: Int, height: Int): File =
        File.createTempFile("decrypt-page-", ".jpg").apply {
            writeBytes(ImportFixtures.image(width = width, height = height))
        }

    private fun protectedPdf(vararg pages: File, password: String = "open-sesame"): File {
        val out = File.createTempFile("protected-", ".pdf")
        val encryption = PdfAes.create(password)
        PdfGenerator.imagesToPdf(pages.toList(), out, encryption = encryption)
        return out
    }

    @Test
    fun `the right password gives a plain PDF with the same page bytes`() {
        val first = page(120, 80)
        val second = page(200, 140)
        val pdf = protectedPdf(first, second)
        try {
            assertTrue("fixture is encrypted", PdfProtection.isEncrypted(pdf) == true)
            val plain = PdfDecryptor.decrypt(pdf.readBytes(), "open-sesame")
            val out = File.createTempFile("decrypted-", ".pdf").apply { writeBytes(plain) }
            try {
                assertTrue(
                    "the decrypted copy is a plain PDF",
                    PdfProtection.isEncrypted(out) == false
                )
                // The verifier compares each image stream with its page file byte for byte.
                PdfVerifier.verify(out, expectedImages = listOf(first, second))
            } finally {
                out.delete()
            }
        } finally {
            pdf.delete()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `a wrong password is rejected`() {
        val page = page(120, 80)
        val pdf = protectedPdf(page)
        try {
            assertThrows(PdfDecryptor.WrongPassword::class.java) {
                PdfDecryptor.decrypt(pdf.readBytes(), "not-the-password")
            }
        } finally {
            pdf.delete()
            page.delete()
        }
    }

    @Test
    fun `a PDF without standard encryption is reported, not guessed`() {
        val page = page(120, 80)
        val plain = File.createTempFile("plain-", ".pdf")
        try {
            PdfGenerator.imagesToPdf(listOf(page), plain)
            assertThrows(PdfDecryptor.Unsupported::class.java) {
                PdfDecryptor.decrypt(plain.readBytes(), "anything")
            }
        } finally {
            plain.delete()
            page.delete()
        }
    }
}
