package com.scanwhirl.app.store.vault

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * SECURITY_JUDGE 1.3.0 finding 2 (HIGH): the Vault original must never enter the
 * ordinary Trash, a checked purge decides the outcome, the durable marker keeps
 * retry authority, and a partial recursive delete is never reported as success.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1VaultPurgeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @After
    fun tearDown() {
        VaultSession.close()
        ScanRepository.resetTestSeams()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        repo.trashDir.deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun VaultStore.setupConfirmed(passcode: String) =
        setup(passcode).also { markConfirmationPassed() }

    private fun store(): VaultStore = VaultStore(
        context = app,
        repo = repo,
        keys = FakeVaultKeys(),
        gate = VaultPasscodeGate(clock = { 0L }),
        iterations = 2_000
    )

    private fun marker(id: String) = File(app.filesDir, "vault2/.adding-$id")

    @Test(timeout = 30_000)
    fun `a successful add publishes the entry and never uses ordinary Trash`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional clean add")
        val vault = store()
        vault.setupConfirmed("246810")

        val result = vault.addDocumentWithResult(id)

        assertTrue("a clean add fully purged the original", result.purgeComplete)
        assertEquals(id, result.entry.id)
        assertTrue(vault.hasEntry(id))
        assertNull("the library copy is gone", repo.getDocument(id))
        assertTrue("no plaintext original is left in Trash", repo.listTrash().isEmpty())
        assertFalse("no plaintext original waits in the private area", repo.vaultPendingPurgeExists(id))
        assertFalse(marker(id).exists())
    }

    @Test(timeout = 30_000)
    fun `a failed move reports incomplete, leaves no Trash copy and is retried next open`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional failed move")
        val vault = store()
        vault.setupConfirmed("246810")
        ScanRepository.vaultPendingMove = { _, _ -> false }
        try {
            val result = vault.addDocumentWithResult(id)
            assertFalse("a failed move must not be reported as complete", result.purgeComplete)
            assertTrue("the encrypted entry is still published", vault.hasEntry(id))
            assertTrue(repo.listTrash().none { it.originalId == id })
            assertTrue("the original stays in the Library, never in Trash", repo.getDocument(id) != null)
            assertTrue("the durable marker keeps retry authority", marker(id).isFile)

            // Next Vault open: a new store recovers from the durable marker.
            ScanRepository.vaultPendingMove = { from, to -> from.renameTo(to) }
            vault.lock()
            vault.recover()
            assertNull(repo.getDocument(id))
            assertTrue(repo.listTrash().isEmpty())
            assertFalse(repo.vaultPendingPurgeExists(id))
            assertFalse(marker(id).exists())
            assertTrue(vault.unlockWithPasscode("246810").ok)
            assertTrue(vault.openDocument(id).pdfFile.isFile)
        } finally {
            ScanRepository.resetTestSeams()
        }
    }

    @Test(timeout = 30_000)
    fun `a partial recursive delete reports incomplete and is retried next open`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional partial purge")
        val vault = store()
        vault.setupConfirmed("246810")
        var simulated = false
        ScanRepository.vaultPendingDelete = { dir ->
            if (!simulated) {
                simulated = true
                dir.listFiles()?.firstOrNull()?.delete()
                false // partial: some files are gone, the folder is still there
            } else {
                dir.deleteRecursively()
            }
        }
        try {
            val result = vault.addDocumentWithResult(id)
            assertFalse("a partial delete must not be reported as complete", result.purgeComplete)
            assertTrue("the private original is still present", repo.vaultPendingPurgeExists(id))
            assertTrue(repo.listTrash().isEmpty())
            assertTrue("the marker survives for retry", marker(id).isFile)

            vault.lock()
            vault.recover()
            assertFalse("the retry finishes the purge", repo.vaultPendingPurgeExists(id))
            assertFalse(marker(id).exists())
        } finally {
            ScanRepository.resetTestSeams()
        }
    }

    @Test(timeout = 30_000)
    fun `deleteFromTrash never claims success on a partial recursive delete`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional partial trash purge")
        val key = repo.moveToTrash(id)!!
        ScanRepository.trashPurgeDelete = { false }
        try {
            assertThrows(IOException::class.java) { repo.deleteFromTrash(key) }
            assertTrue(
                "the hidden purge entry stays on disk for startup recovery",
                repo.trashDir.listFiles()?.any { it.name.startsWith(ScanRepository.PURGE_PREFIX) } == true
            )
            assertTrue("it is no longer listed as restorable", repo.listTrash().none { it.key == key })
        } finally {
            ScanRepository.resetTestSeams()
            repo.recoverTrash()
        }
        assertFalse("recovery finishes the delete", repo.trashDir.exists() && repo.trashDir.listFiles()?.isNotEmpty() == true)
    }

    @Test(timeout = 30_000)
    fun `the honest purge message is translated in both languages`() {
        val english = app.getString(R.string.vault_added_purge_pending)
        assertTrue(
            "English must say the original is not fully removed yet",
            english.contains("could not be fully removed", ignoreCase = true) &&
                english.contains("next start", ignoreCase = true)
        )
        val hiConfig = Configuration(app.resources.configuration).apply { setLocale(Locale("hi")) }
        val hindi = app.createConfigurationContext(hiConfig).resources
            .getString(R.string.vault_added_purge_pending)
        assertNotNull(hindi)
        assertTrue("Hindi must be a real translation, not the English text", hindi != english)
        assertTrue("Hindi text must be in Devanagari", hindi.any { it.code in 0x0900..0x097F })
    }
}
