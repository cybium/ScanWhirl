package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Sha256
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SECURITY_JUDGE R2 N2: deterministic OCR-commit versus Vault-move interleaving.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec2VaultOcrLockTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
    }

    @After
    fun tearDown() {
        OcrStore.recognizedCommitProbe = null
        ScanRepository.resetTestSeams()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
    }

    private fun prepared(): Pair<String, List<OcrStore.Recognized>> {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional OCR commit")
        val page = File(repo.docsDir, id).listFiles { it.extension.equals("jpg", true) }!!.first()
        return id to listOf(OcrStore.Recognized(page.name, Sha256.of(page), "fictional text", null))
    }

    private fun pauseCommit(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        OcrStore.recognizedCommitProbe = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        return entered to release
    }

    @Test(timeout = 30_000)
    fun `a vault move waits for the OCR commit then purges all plaintext`() {
        val (id, pages) = prepared()
        val (entered, release) = pauseCommit()
        val commit = Thread { repo.ocrStore.putRecognizedPages(id, pages) }
        commit.start()
        assertTrue("the OCR commit never reached the probe", entered.await(5, TimeUnit.SECONDS))

        val moved = AtomicBoolean(false)
        val move = Thread {
            repo.moveOriginalToVaultPendingPurge(id)
            moved.set(true)
        }
        move.start()
        Thread.sleep(200)
        assertFalse("the Vault move must wait for the OCR lock", moved.get())

        release.countDown()
        commit.join(5_000)
        move.join(5_000)
        assertTrue("the move completes after the commit", moved.get())
        assertFalse("no document directory may be recreated", File(repo.docsDir, id).exists())
        assertTrue(repo.vaultPendingPurgeExists(id))
        assertTrue(repo.purgeVaultPendingOriginal(id))
        assertFalse("no plaintext OCR survives the purge", repo.vaultPendingPurgeExists(id))
        assertFalse(File(app.filesDir, "documents/$id/ocr.txt").exists())
    }

    @Test(timeout = 30_000)
    fun `a late OCR commit refuses to recreate a moved document directory`() {
        val (id, pages) = prepared()
        val (entered, release) = pauseCommit()
        val commit = Thread { repo.ocrStore.putRecognizedPages(id, pages) }
        commit.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        // Simulate the removed directory that a completed Vault move leaves.
        val removed = File(app.cacheDir, "sec2-moved-$id")
        assertTrue(File(repo.docsDir, id).renameTo(removed))
        release.countDown()
        commit.join(5_000)

        assertFalse("a late publisher must not mkdirs a removed document", File(repo.docsDir, id).exists())
        assertFalse(File(File(repo.docsDir, id), "ocr.txt").exists())
        removed.deleteRecursively()
    }
}
