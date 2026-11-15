package com.scanwhirl.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Minimal reproducers from the owner's hostile PDF sweep: malformed bytes must end as a friendly
 * [PdfDecryptor.Unsupported] / null / empty list, never as IndexOutOfBounds or NumberFormat.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MalformedPdfTest {

    private val cases = listOf(
        "empty" to ByteArray(0),
        "header only" to "%PDF-1.7\n".toByteArray(),
        "truncated xref" to ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n" +
            "xref\n0 2\n0000000000 65535 f \n0000000010 00000 n \ntrailer\n<< /Size 2 >>\n" +
            "startxref\n999999\n%%EOF\n").toByteArray(),
        "huge length" to ("%PDF-1.7\n1 0 obj\n<< /Length 999999999 >>\nstream\n" +
            "abc\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\nstartxref\n9\n%%EOF\n").toByteArray(),
        "bad encrypt dict" to ("%PDF-1.7\ntrailer\n<< /Root 1 0 R /Encrypt << /V 5 /R 6 " +
            "/U <zz> >> >>\nstartxref\n0\n%%EOF\n").toByteArray()
    )

    @Test
    fun `malformed PDFs fail as unsupported, never as a runtime crash`() {
        for ((name, bytes) in cases) {
            val file = File.createTempFile("malformed-", ".pdf")
            try {
                file.writeBytes(bytes)
                val encrypted = runCatching { PdfProtection.isEncrypted(file) }
                assertTrue("$name: isEncrypted must not throw", encrypted.isSuccess)

                val objects = runCatching { PdfEncryptor.objects(bytes) }
                assertTrue("$name: object parser must not throw", objects.isSuccess)
                assertNotNull("$name: object list", objects.getOrNull())

                val decrypt = runCatching { PdfDecryptor.decrypt(bytes, "anything") }
                val failure = decrypt.exceptionOrNull()
                assertTrue(
                    "$name: decrypt must fail as Unsupported (was $failure)",
                    failure is PdfDecryptor.Unsupported || failure is PdfDecryptor.WrongPassword
                )
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `an 18 byte broken file is not a PDF`() {
        val bytes = "this is not a pdf".toByteArray()
        assertFalse(PdfImport.looksLikePdf(bytes, bytes.size))
    }
}
