package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SimulatedProcessDeath
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * L2 Phase A: the encrypted Vault engine.
 *
 * The tests use the [VaultKeys] seam (Robolectric has no hardware Keystore) and a small
 * PBKDF2 iteration count for speed; the production default is asserted in [VaultCryptoTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultStoreTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var keys: FakeVaultKeys
    private var now = 0L

    private val fastIterations = 2_000

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        keys = FakeVaultKeys()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        Faults.resetAll()
        // Keep Robolectric's app data clean for the next test class run.
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        File(app.cacheDir, "vault_verify").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun store(): VaultStore = VaultStore(
        context = app,
        repo = repo,
        keys = keys,
        gate = VaultPasscodeGate(clock = { now }),
        iterations = fastIterations
    )

    @Test
    fun `add to vault then open gives byte-identical files and lock wipes the plaintext copy`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Aadhaar card")
        val original = snapshot(File(app.filesDir, "documents/$id"))
        val store = store()
        val setup = store.setup("246810")

        assertEquals(24, VaultCrypto.normalizeRecoveryCode(setup.recoveryCode).length)
        assertTrue(store.isUnlocked())

        val entry = store.addDocument(id)
        assertEquals(id, entry.id)
        assertEquals("Aadhaar card", entry.name)
        assertEquals(2, entry.pageCount)
        assertTrue(entry.sizeBytes > 0)
        assertNull("the library copy is gone", repo.getDocument(id))
        assertEquals(listOf(id), store.listEntries().map { it.id })
        assertEquals(1, store.count())

        val opened = store.openDocument(id)
        assertEquals("Aadhaar card", opened.name)
        assertEquals(original, snapshot(opened.dir))
        assertEquals(2, opened.pageFiles.size)

        store.lock()
        assertFalse(store.isUnlocked())
        assertFalse("cache plaintext is wiped on lock", File(app.cacheDir, "vault_open/$id").exists())
        assertThrows(VaultLockedException::class.java) { store.openDocument(id) }
    }

    @Test
    fun `a wrong recovery code fails and the right one opens the vault`() {
        val store = store()
        val setup = store.setup("135790")
        store.lock()

        assertFalse(store.unlockWithRecovery("AAAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(store.isUnlocked())

        assertTrue(store.unlockWithRecovery(VaultCrypto.formatRecoveryCode(setup.recoveryCode).lowercase()))
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `the biometric challenge opens the vault only when the cipher completes`() {
        val store = store()
        store.setup("246810")
        store.lock()
        assertTrue("a valid Keystore key exists", store.canUseBiometric())

        val challenge = store.beginBiometricUnlock()
        assertNotNull("the prompt gets a CryptoObject cipher", challenge.cipher)
        assertFalse("nothing is unlocked before the prompt", store.isUnlocked())

        assertTrue(store.completeBiometricUnlock(challenge))
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `an invalidated keystore key falls back to the passcode and is re-wrapped`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Passport")
        val store = store()
        store.setup("246810")
        store.addDocument(id)
        store.lock()

        keys.invalidate() // a new fingerprint/face was enrolled on the phone
        assertFalse("the dead key is not offered", store.canUseBiometric())
        assertThrows(VaultKeyInvalidatedException::class.java) { store.beginBiometricUnlock() }

        // The user unlocks with the passcode and the store rebuilds the fingerprint wrap.
        assertTrue(store.unlockWithPasscode("246810").ok)
        assertTrue(store.canUseBiometric())
        assertEquals(listOf(id), store.listEntries().map { it.id })

        // The fresh key can unwrap the same vault key again.
        store.lock()
        assertTrue(store.completeBiometricUnlock(store.beginBiometricUnlock()))
        assertEquals(listOf(id), store.listEntries().map { it.id })
    }

    @Test
    fun `a tampered encrypted byte fails the GCM tag and leaves no decrypted copy`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val store = store()
        store.setup("246810")
        store.addDocument(id)

        val entryDir = File(app.filesDir, "vault2/$id")
        val payload = entryDir.listFiles()!!.first { it.name.startsWith("f") && it.name.endsWith(".enc") }
        val bytes = payload.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
        payload.writeBytes(bytes)

        assertThrows(VaultAuthException::class.java) { store.openDocument(id) }
        assertFalse("nothing half-decrypted is left behind", File(app.cacheDir, "vault_open/$id").exists())
    }

    @Test
    fun `no page or PDF plaintext exists anywhere under filesDir vault2`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val store = store()
        store.setup("246810")
        store.addDocument(id)

        val vault2 = File(app.filesDir, "vault2")
        val leaks = vault2.walkTopDown().filter { it.isFile }.filter { leaksPlaintext(it.readBytes()) }.toList()
        assertTrue("plaintext leaves a visible format header: $leaks", leaks.isEmpty())
        assertFalse("the original directory is gone", File(app.filesDir, "documents/$id").exists())
    }

    @Test
    fun `a wrong passcode counts through the five-try lockout and the right one resumes`() {
        val store = store()
        store.setup("246810")
        store.lock()

        repeat(4) {
            val r = store.unlockWithPasscode("000000")
            assertFalse(r.ok)
            assertEquals(0L, r.pauseMillis)
        }
        val fifth = store.unlockWithPasscode("000000")
        assertFalse(fifth.ok)
        assertEquals(30_000L, fifth.pauseMillis)

        val blocked = store.unlockWithPasscode("246810")
        assertFalse("still paused even with the right passcode", blocked.ok)
        assertEquals(30_000L, blocked.pauseMillis)

        now = 30_000
        val resumed = store.unlockWithPasscode("246810")
        assertTrue(resumed.ok)
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `process death mid-add leaves the original document intact and no half entry`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Bank statement")
        val before = snapshot(File(app.filesDir, "documents/$id"))
        val store = store()
        store.setup("246810")

        val probe = Faults.dieAtOp("vault:verified")
        try {
            store.addDocument(id)
            fail("simulated process death expected")
        } catch (_: SimulatedProcessDeath) {
        }
        assertTrue(probe.fired)
        Faults.resetAll()

        assertNotNull("the original never left the library", repo.getDocument(id))
        assertEquals(before, snapshot(File(app.filesDir, "documents/$id")))

        val fresh = store()
        fresh.recover()
        assertFalse("no published vault entry", hasPublishedEntry(id))
        assertFalse("the abandoned staging folder is cleaned", hasStagingFolder())
    }

    @Test
    fun `a legacy four-digit PIN unlocks once and the legacy library migrates`() {
        Prefs.setVaultPin(app, "1234")
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Old private")
        repo.setVaulted(id, true)
        val store = store()

        assertTrue(store.hasLegacyPin())
        assertTrue(store.verifyLegacyPin("1234"))
        assertFalse(store.verifyLegacyPin("9999"))

        store.setup("246810")
        assertEquals(1, store.migrateLegacyVault())
        assertNull(repo.getDocument(id))
        assertEquals(listOf(id), store.listEntries().map { it.id })
        assertTrue(store.openDocument(id).pageFiles.isNotEmpty())
    }

    @Test
    fun `a passcode must be six or more digits and setup happens once`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.setup("12345") }
        assertThrows(IllegalArgumentException::class.java) { store.setup("12345a") }
        store.setup("123456")
        assertThrows(IllegalStateException::class.java) { store.setup("654321") }
    }

    @Test
    fun `backup contains no readable page or PDF and restores byte-identically with the code`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Medical report")
        val original = snapshot(File(app.filesDir, "documents/$id"))
        val store = store()
        val setup = store.setup("246810")
        store.addDocument(id)

        val backup = File(app.cacheDir, "test.swvault")
        store.exportBackup(backup)
        assertTrue(backup.isFile)
        assertTrue("the backup is encrypted", !leaksPlaintext(backup.readBytes()))

        store.wipeAll()
        assertFalse(store.isSetUp())
        keys.clear()

        val freshKeys = FakeVaultKeys()
        val fresh = VaultStore(app, repo, freshKeys, VaultPasscodeGate(clock = { now }), fastIterations)
        assertFalse("a fresh phone has no vault", fresh.isSetUp())
        assertThrows(VaultAuthException::class.java) { fresh.importBackup(backup, "AAAAAAAAAAAAAAAAAAAAAAAA") }
        assertFalse(fresh.isSetUp())

        val restored = fresh.importBackup(backup, setup.recoveryCode)
        assertEquals(listOf(id), restored.map { it.id })
        assertTrue(fresh.isSetUp())
        assertTrue(fresh.isUnlocked())
        assertEquals(original, snapshot(fresh.openDocument(id).dir))
    }

    // ------------------------------------------------------------------

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(dir).invariantSeparatorsPath to
                MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        }

    private fun hasPublishedEntry(id: String): Boolean =
        File(File(app.filesDir, "vault2"), id).let { it.isDirectory && File(it, "manifest.enc").isFile }

    private fun hasStagingFolder(): Boolean =
        File(app.filesDir, "vault2").listFiles().orEmpty().any { it.name.startsWith(".incoming-") }

    /** True when a byte range holds a JPEG, PNG, PDF or ZIP header: the content was not encrypted. */
    private fun leaksPlaintext(bytes: ByteArray): Boolean {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val pdf = "%PDF".toByteArray()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        fun at(needle: ByteArray): Boolean {
            outer@ for (i in 0..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
        return at(jpeg) || at(pdf) || at(png) || at(zip)
    }
}
