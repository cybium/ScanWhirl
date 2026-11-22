package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SECURITY_JUDGE R3: every OCR publisher must refuse a live document directory
 * that was moved/purged after recognition started. Plus the not-set-up recover
 * and wipeAll pending-purge leftovers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec2RecognitionPublisherTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "sec2-recog").deleteRecursively()
        VaultSession.close()
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        VaultSession.close()
        ScanRepository.resetTestSeams()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "sec2-recog").deleteRecursively()
    }

    private fun doc(): Pair<String, File> {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional late OCR")
        val page = File(repo.docsDir, id).listFiles { it.extension.equals("jpg", true) }!!.first()
        return id to page
    }

    private fun pausedRecognizer(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        OcrEngine.rawReader = { _, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            OcrRawPage(
                100, 120,
                listOf(OcrRawLine("fictional recognised text", OcrBox(1, 1, 90, 20)))
            )
        }
        return entered to release
    }

    @Test(timeout = 30_000)
    fun `putPage refuses a document directory that was already moved`() {
        val (id, page) = doc()
        assertTrue(repo.moveOriginalToVaultPendingPurge(id))

        repo.ocrStore.putPage(id, page.name, "late plain text", null)

        assertFalse("a late publisher must not recreate a moved document", File(repo.docsDir, id).exists())
        assertFalse(File(File(repo.docsDir, id), "ocr.txt").exists())
        assertFalse(File(File(repo.docsDir, id), "ocr_geo.txt").exists())
    }

    @Test(timeout = 30_000)
    fun `Word recognition publishing after a move writes no plaintext OCR`() {
        val (id, _) = doc()
        val (entered, release) = pausedRecognizer()
        val out = File(app.cacheDir, "sec2-recog/export.docx")
        val worker = Thread {
            runBlocking { runCatching { repo.exportWord(id, out) } }
        }
        try {
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(repo.moveOriginalToVaultPendingPurge(id))
        } finally {
            release.countDown()
            worker.join(10_000)
        }
        assertFalse("Word export must not recreate a moved document", File(repo.docsDir, id).exists())
        assertFalse(File(File(repo.docsDir, id), "ocr.txt").exists())
    }

    @Test(timeout = 30_000)
    fun `one-shot re-recognition publishing after a move writes no plaintext OCR`() {
        val (id, _) = doc()
        val (entered, release) = pausedRecognizer()
        val worker = Thread {
            runBlocking { runCatching { OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH_HINDI) } }
        }
        try {
            worker.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(repo.moveOriginalToVaultPendingPurge(id))
        } finally {
            release.countDown()
            worker.join(10_000)
        }
        assertFalse("a one-shot re-read must not recreate a moved document", File(repo.docsDir, id).exists())
        assertFalse(File(File(repo.docsDir, id), "ocr.txt").exists())
    }

    @Test(timeout = 30_000)
    fun `recover sweeps pending purge even when the vault is not set up`() {
        val pending = File(repo.vaultPendingPurgeDir, "pending-id").apply {
            mkdirs()
            File(this, "ocr.txt").writeText("plaintext")
        }
        val vault = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000)

        vault.recover()

        assertFalse("a not-set-up Vault must still sweep its pending purge", pending.exists())
    }

    @Test(timeout = 30_000)
    fun `wipeAll cleans a pending purge folder`() {
        val vault = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000)
        vault.setup("246810").also { vault.markConfirmationPassed() }
        val pending = File(repo.vaultPendingPurgeDir, "pending-id").apply {
            mkdirs()
            File(this, "ocr.txt").writeText("plaintext")
        }

        vault.wipeAll()

        assertFalse("wipeAll must remove pending plaintext too", pending.exists())
    }
}
