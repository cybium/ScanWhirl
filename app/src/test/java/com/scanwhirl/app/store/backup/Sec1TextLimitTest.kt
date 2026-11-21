package com.scanwhirl.app.store.backup

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DraftStore
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.store.vault.VaultCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale

/**
 * SECURITY_JUDGE 1.3.0 finding 4: format-specific restore caps, an honest
 * skip count, and bounded reads in every text consumer. Large fixtures are
 * generated here, never committed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Sec1TextLimitTest {

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
        return File(app.cacheDir, "sec1-limit-fixture.swbackup").also { file ->
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
    fun `oversized ocr and draft entries are skipped with an honest count`() {
        File(app.filesDir, "documents").deleteRecursively()
        File(app.filesDir, "drafts").deleteRecursively()
        val huge = ByteArray(9 * 1024 * 1024) { 'x'.toByte() }
        val source = archive(
            "documents/new/meta.txt" to "New document\n".toByteArray(),
            "documents/new/ocr.txt" to huge,
            "documents/new/ocr_geo.txt" to huge,
            "drafts/new/draft.txt" to huge
        )
        val report = FullBackup(app).restore(source, password)

        assertEquals(3, report.textEntriesSkipped)
        assertTrue("the skip is visible to the user", report.warnings.any { it.contains("3") })
        assertFalse(File(app.filesDir, "documents/new/ocr.txt").exists())
        assertFalse(File(app.filesDir, "documents/new/ocr_geo.txt").exists())
        assertFalse(File(app.filesDir, "drafts/new/draft.txt").exists())
        assertEquals("ordinary entries still restore", 1, report.documents)
    }

    @Test(timeout = 30_000)
    fun `an in-limit text entry is still restored and read normally`() {
        File(app.filesDir, "documents").deleteRecursively()
        val encoded = (0 until 40).map {
            "page_000.jpg\t" + android.util.Base64.encodeToString(
                "text-$it".toByteArray(), android.util.Base64.NO_WRAP
            )
        }
        val ocr = (listOf("#scanwhirl-ocr-v2") + encoded).joinToString("\n").toByteArray()
        val source = archive(
            "documents/new/meta.txt" to "New document\n".toByteArray(),
            "documents/new/ocr.txt" to ocr
        )
        val report = FullBackup(app).restore(source, password)
        assertEquals(0, report.textEntriesSkipped)
        assertTrue(File(app.filesDir, "documents/new/ocr.txt").isFile)
        assertTrue(OcrStore(File(app.filesDir, "documents")).readText("new").isNotEmpty())
    }

    @Test(timeout = 30_000)
    fun `the skip warning is translated in Hindi`() {
        val english = app.resources.getQuantityString(R.plurals.backup_restore_text_skipped, 2, 2)
        assertTrue(english.contains("Skipped"))
        val hiConfig = Configuration(app.resources.configuration).apply { setLocale(Locale("hi")) }
        val hindi = app.createConfigurationContext(hiConfig).resources
            .getQuantityString(R.plurals.backup_restore_text_skipped, 2, 2)
        assertTrue("Hindi skip warning must be a translation", hindi != english)
        assertTrue("Hindi skip warning must be in Devanagari", hindi.any { it.code in 0x0900..0x097F })
    }
}
