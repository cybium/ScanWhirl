package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Security audit (plaintext leaks): the cold-start sweep removes every cache copy of private
 * content, and the library itself is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacySweepTest {

    private lateinit var app: Application
    private lateinit var cache: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        cache = app.cacheDir
        cache.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        PrivacySweep.resetForTests()
        cache.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun file(relative: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(cache, relative).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    @Test
    fun `decrypted vault files and temp pdfs are removed on a cold start`() {
        val opened = file("vault_open/doc/page_001.jpg")
        val verify = file("vault_verify/x/check.bin")
        val viewer = file("documents/preview.pdf")
        val pdfa = file("pdfa/report-pdfa.pdf")
        val ocrTxt = file("ocr_1234.txt")
        val decrypted = file("decrypted-abc.pdf")
        val preview = file("preview_doc.pdf")

        val removed = PrivacySweep.run(app)

        assertTrue("entries were removed", removed >= 6)
        listOf(opened, verify, viewer, pdfa, ocrTxt, decrypted, preview).forEach {
            assertFalse("still there: ${it.absolutePath}", it.exists())
        }
    }

    /**
     * X3-SHARECOPY: a share copy is the one cache entry a recipient app may still be reading
     * after ScanWhirl's process went away. Young copies are kept for the retention window;
     * only stale ones are reclaimed at cold start.
     */
    @Test
    fun `a share a recipient may still read survives the cold start`() {
        val shared = file(
            "documents/share/x3-${System.currentTimeMillis()}-abcd/Aadhaar front - FAKE.pdf"
        )
        val viewer = file("documents/preview.pdf")
        PrivacySweep.run(app)
        assertTrue("a young share copy must stay readable", shared.exists())
        assertFalse("the rest of the cache is still swept", viewer.exists())
    }

    @Test
    fun `share copies older than the keep window are swept at cold start`() {
        val old = System.currentTimeMillis() - ShareUtils.SHARE_KEEP_MS - 60_000L
        val shared = file("documents/share/x3-$old-abcd/Annual Report.pdf")
        shared.parentFile!!.setLastModified(old)
        shared.setLastModified(old)

        PrivacySweep.run(app)

        assertFalse("a stale share copy is reclaimed", shared.exists())
    }

    /**
     * B9: inserting pages from the camera or the gallery copies normalised JPEGs into
     * `cacheDir/insert_<id>/`; cancelling the insert used to leave the whole folder behind.
     */
    @Test
    fun `insert page copies are removed on a cold start`() {
        val page = file("insert_abcd1234/page_000.jpg", ByteArray(2048) { 5 })
        PrivacySweep.run(app)
        assertFalse("the copied page must not survive the process", page.exists())
        assertFalse(File(cache, "insert_abcd1234").exists())
    }

    /**
     * B2: a backup or a restore killed halfway leaves the plaintext archive (and, for a restore,
     * the decrypted document tree) in the cache. Both use `fullbackup-<id>.tmp`, `restore-<id>.tmp`
     * and `restore-<id>/`, none of which the sweep knew about.
     */
    @Test
    fun `interrupted backup and restore temp files are removed on a cold start`() {
        val archive = file("fullbackup-abcd1234.tmp", ByteArray(4096) { 1 })
        val decrypted = file("restore-abcd1234.tmp", ByteArray(4096) { 2 })
        val tree = file("restore-abcd1234/documents/doc1/scan.pdf", ByteArray(4096) { 3 })

        PrivacySweep.run(app)

        listOf(archive, decrypted, tree).forEach {
            assertFalse("still there: ${it.absolutePath}", it.exists())
        }
        assertFalse("the whole restore tree is gone", File(cache, "restore-abcd1234").exists())
    }

    @Test
    fun `a backup or restore running in this process keeps its own cache files`() {
        // The real guard reads the job marker FullBackup holds while write()/restore() run.
        assertFalse("nothing is running outside a backup", PrivacySweep.backupRunning())
        PrivacySweep.backupRunning = { true }

        val archive = file("fullbackup-abcd1234.tmp", ByteArray(64) { 1 })
        val decrypted = file("restore-abcd1234.tmp", ByteArray(64) { 2 })
        val tree = file("restore-abcd1234/documents/doc1/scan.pdf", ByteArray(64) { 3 })
        val finishedArchive = file("fullbackup/ScanWhirl-2026-11-16.0010.swbackup", ByteArray(64) { 4 })
        val otherTemp = file("decrypted-abc.pdf", ByteArray(64) { 5 })

        PrivacySweep.run(app)

        listOf(archive, decrypted, tree, finishedArchive).forEach {
            assertTrue("a running job must keep ${it.name}", it.exists())
        }
        assertFalse("unrelated plaintext is still swept", otherTemp.exists())
    }

    @Test
    fun `the library, the vault and user exports are never touched`() {
        val library = File(app.filesDir, "documents/doc1/scan.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val vault = File(app.filesDir, "vault2/doc1/manifest.enc").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(8))
        }
        val exported = File(app.filesDir, "exports/mine.zip").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(7))
        }

        PrivacySweep.run(app)

        assertTrue(library.exists())
        assertTrue(vault.exists())
        assertTrue(exported.exists())
    }

    @Test
    fun `the sweep is safe to run twice and on an empty cache`() {
        PrivacySweep.run(app)
        PrivacySweep.run(app)
        assertTrue("a second run removes nothing new", PrivacySweep.run(app) == 0)
    }
}
