package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DraftStore
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.store.vault.VaultCrypto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * SECURITY_JUDGE 1.3.0 finding 4, behavioral RED anchor using only base APIs:
 * a hostile backup's huge text files must never be published or read whole.
 * Fixtures are generated here; no large file is committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Sec1TextLimitRegressionTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val password = "fictional-text-limit-password"

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            entries.forEach { (path, bytes) ->
                out.writeByte(1); out.writeUTF(path); out.writeLong(bytes.size.toLong()); out.write(bytes)
            }
            out.writeByte(0)
        }
        val salt = VaultCrypto.newSalt()
        val key = VaultCrypto.deriveKey(password, salt, 10_000)
        return File(app.cacheDir, "sec1-text-fixture.swbackup").also { file ->
            file.outputStream().use { raw ->
                val out = DataOutputStream(raw)
                out.write("SWFULLB1".toByteArray()); out.writeByte(2); out.writeByte(1)
                out.writeInt(10_000); out.writeByte(salt.size); out.write(salt); out.writeByte(1)
                VaultCrypto.encryptStream(plain.toByteArray().inputStream(), plain.size().toLong(), raw, key)
            }
            key.fill(0)
        }
    }

    @Test(timeout = 30_000)
    fun `an oversized ocr text file is treated as damaged instead of read whole`() {
        val docs = File(app.filesDir, "documents-sec1")
        val dir = File(docs, "d1").apply { mkdirs() }
        val line = ("page_000.jpg\t" + "a".repeat(1000) + "\n").toByteArray()
        val repeats = (9L * 1024 * 1024 / line.size + 1).toInt()
        ByteArrayOutputStream().use { out -> repeat(repeats) { out.write(line) }; File(dir, "ocr.txt").writeBytes(out.toByteArray()) }
        assertTrue("fixture is genuinely oversized", File(dir, "ocr.txt").length() > 8L * 1024 * 1024)

        val read = OcrStore(docs).readText("d1")
        assertTrue("a 9 MiB OCR file must be treated as damaged, not parsed", read.isEmpty())
    }

    @Test(timeout = 30_000)
    fun `an oversized draft manifest is treated as damaged`() {
        val filesDir = File(app.filesDir, "sec1-draft-home").apply { mkdirs() }
        val id = "d1"
        val dir = File(File(filesDir, DraftStore.DIR), id).apply { mkdirs() }
        val sha = "0".repeat(64)
        val head = listOf(
            "scanwhirl-draft-v1", "base=$id", "basePages=$sha",
            "created=100", "updated=100", "changes=1", "next=1"
        ).joinToString("\n") + "\n"
        val row = "p=page_000.jpg\t$sha\t-\t-\n"
        val repeats = (5L * 1024 * 1024 / row.toByteArray().size + 1).toInt()
        StringBuilder(head).apply { repeat(repeats) { append(row) } }.let { File(dir, "draft.txt").writeText(it.toString()) }

        val damaged = runCatching { DraftStore(filesDir).load(id) }.exceptionOrNull()
        assertTrue("a 5 MiB draft manifest must be rejected as damaged: $damaged", damaged is DraftStore.DraftDamaged)
    }

    @Test(timeout = 30_000)
    fun `a hostile backup cannot publish a huge ocr text file`() {
        File(app.filesDir, "documents").deleteRecursively()
        val huge = ByteArray(9 * 1024 * 1024) { 'x'.toByte() }
        val source = archive(
            "documents/new/meta.txt" to "New document\n".toByteArray(),
            "documents/new/ocr.txt" to huge
        )
        val report = FullBackup(app).restore(source, password)
        assertFalse(
            "an oversized OCR text entry must never be published for consumers to read",
            File(app.filesDir, "documents/new/ocr.txt").exists()
        )
        assertTrue("the skip must be reported honestly", report.warnings.isNotEmpty())
    }
}
