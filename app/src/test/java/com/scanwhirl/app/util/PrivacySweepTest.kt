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

    @Test
    fun `share copies inside the cache documents dir are removed`() {
        val shared = file("documents/share/Annual Report.pdf")
        PrivacySweep.run(app)
        assertFalse("a shared copy must not survive the process", shared.exists())
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
