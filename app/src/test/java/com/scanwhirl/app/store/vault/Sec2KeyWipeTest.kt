package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SECURITY_JUDGE R2 LOW residual: the PDF file key and the unwrapped Vault
 * backup-import key must be wiped in finally after their final use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec2KeyWipeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, "vault2").deleteRecursively()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        PdfEncryptor.fileKeyWipeProbe = null
        VaultStore.importKeyWipeProbe = null
        VaultPasscodeGate.resetSharedForTests(app)
        File(app.filesDir, "vault2").deleteRecursively()
    }

    private fun store() = VaultStore(
        app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000
    )

    @Test(timeout = 30_000)
    fun `the PDF file key is wiped after a successful protect`() {
        val dir = File(app.cacheDir, "sec2-file-key").apply { mkdirs() }
        val jpg = File(dir, "a.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 300)) }
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), src)
        var wiped = false
        PdfEncryptor.fileKeyWipeProbe = { wiped = true }
        val out = File(dir, "protected.pdf")

        PdfEncryptor.encrypt(src, out, "pw1234")

        assertTrue("the file key must be wiped after the final rewrite", wiped)
        assertTrue(out.isFile && out.length() > 0)
    }

    @Test(timeout = 30_000)
    fun `the PDF file key is wiped when publication fails`() {
        val dir = File(app.cacheDir, "sec2-file-key-fail").apply { mkdirs() }
        val jpg = File(dir, "a.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 300)) }
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), src)
        val blocker = File(dir, "not-a-folder").apply { writeText("x") }
        var wiped = false
        PdfEncryptor.fileKeyWipeProbe = { wiped = true }

        runCatching { PdfEncryptor.encrypt(src, File(blocker, "out.pdf"), "pw1234") }

        assertTrue("a failed protect must still wipe the file key", wiped)
    }

    @Test(timeout = 30_000)
    fun `the unwrapped vault backup import key is wiped`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional backup key")
        val vault = store()
        val setup = vault.setup("246810").also { vault.markConfirmationPassed() }
        vault.addDocument(id)
        val backup = File(app.cacheDir, "sec2-import.swvault")
        vault.exportBackup(backup)
        vault.wipeAll()
        var wiped = false
        VaultStore.importKeyWipeProbe = { wiped = true }
        val restored = store()

        val entries = restored.importBackup(backup, setup.recoveryCode)

        assertTrue("the imported entry is still usable", entries.any { it.id == id })
        assertTrue("the unwrapped import key must be wiped", wiped)
    }
}
