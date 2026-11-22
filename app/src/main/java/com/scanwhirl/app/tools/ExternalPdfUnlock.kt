package com.scanwhirl.app.tools

import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Object-preserving external unlock, not a scan import. Caller runs this off main and owns the
 * returned temporary copy. All files use PrivacySweep's existing cold-start `decrypted-` prefix;
 * no new retention category, library entry, password record or raster fallback.
 */
internal class ExternalPdfUnlock(
    private val cacheDir: File,
    private val heapBytes: Long = Runtime.getRuntime().maxMemory()
) {
    class TooLarge : IOException("PDF exceeds unlock memory budget")
    data class Output(val file: File, val beforeBytes: Long, val sourceHash: String, val pages: Int)

    fun unlock(
        input: InputStream,
        declaredBytes: Long,
        password: String,
        verifyPages: (File) -> Int,
        checkCancelled: () -> Unit = {}
    ): Output {
        // Account for bounded stream accumulation and verification in addition to the decoder's
        // working copies. Never enlarge the existing decoder budget.
        val cap = minOf(PdfDecryptor.maxInMemoryBytes(heapBytes), heapBytes / 16)
        if (cap <= 0 || declaredBytes > cap) throw TooLarge()
        val id = UUID.randomUUID().toString()
        val staging = File(cacheDir, "decrypted-$id.part")
        val published = File(cacheDir, "decrypted-$id.pdf")
        var handedOff = false
        var raw: ByteArray? = null
        var plain: ByteArray? = null
        try {
            checkCancelled()
            raw = ByteArrayOutputStream().use { bytes ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) throw IOException("PDF provider stopped reading")
                    count += n
                    if (count > cap) throw TooLarge()
                    bytes.write(buffer, 0, n)
                }
                if (declaredBytes >= 0 && declaredBytes != count) throw IOException("PDF size changed")
                bytes.toByteArray()
            }
            val sourceHash = Sha256.of(raw)
            plain = PdfDecryptor.decrypt(raw, password)
            checkCancelled()
            FileOutputStream(staging).use {
                it.write(plain)
                it.fd.sync()
            }
            // Identity check is over saved bytes, not a successful write callback.
            if (staging.length() != plain.size.toLong() ||
                Sha256.of(staging) != Sha256.of(plain) ||
                PdfProtection.isEncrypted(staging) != false
            ) throw IOException("Unverified unlock output")
            val pages = verifyPages(staging)
            check(pages > 0) { "No verified PDF pages" }
            checkCancelled()
            check(staging.renameTo(published)) { "Could not publish unlock output" }
            checkCancelled()
            handedOff = true
            return Output(published, raw.size.toLong(), sourceHash, pages)
        } catch (_: OutOfMemoryError) {
            throw TooLarge()
        } finally {
            raw?.fill(0)
            plain?.fill(0)
            staging.delete()
            if (!handedOff) published.delete()
        }
    }
}
