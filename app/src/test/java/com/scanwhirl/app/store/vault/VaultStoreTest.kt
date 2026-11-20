package com.scanwhirl.app.store.vault

import android.app.Application
import android.util.Base64
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
import java.lang.reflect.InvocationTargetException
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

    /**
     * A normal, fully set-up Vault: the one-time recovery code's four-character check already
     * passed (T18). Provisioning alone leaves a non-secret pending marker, which blocks normal
     * use until the check does pass.
     */
    private fun VaultStore.setupConfirmed(passcode: String): VaultStore.Setup =
        setup(passcode).also { markConfirmationPassed() }
    private fun store(): VaultStore = VaultStore(
        context = app,
        repo = repo,
        keys = keys,
        gate = VaultPasscodeGate(clock = { now }),
        iterations = fastIterations
    )

    @Test
    fun `manifest parser rejects invalid paths and declarations without touching storage`() {
        val vault = store()
        val roots = listOf(
            app.filesDir,
            File(app.filesDir, "vault2"),
            File(app.filesDir, "vault2/.incoming-fixture"),
            File(app.cacheDir, "vault_open"),
            File(app.cacheDir, "vault_verify")
        )
        roots.forEachIndexed { index, dir ->
            dir.mkdirs()
            File(dir, "sentinel").writeText("Fictional sentinel $index")
        }
        fun state() = roots.map(::snapshot)
        val before = state()
        val parse = VaultStore::class.java.getDeclaredMethod(
            "parseManifest", ByteArray::class.java, String::class.java
        ).apply { isAccessible = true }
        fun encoded(value: String) = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        fun record(rel: String, stored: String, size: Long = 5) =
            "file\t${encoded(rel)}\t$stored\t$size\t${"0".repeat(64)}"
        fun manifest(records: List<String>, pdf: String = "document.pdf") =
            (listOf("v1", "id\tfixture", "pdf\t${encoded(pdf)}") + records).joinToString("\n").toByteArray()
        val correct = record("document.pdf", "f0000.enc")
        val invalidNames = listOf("../x", "/x", "\\x", "\u0000", "", ".", "..")
        val cases = buildList {
            invalidNames.forEach { add(manifest(listOf(record("document.pdf", it)))) }
            invalidNames.forEach { add(manifest(listOf(correct, record(it, "f0001.enc")))) }
            add(manifest(listOf(correct, record("other.pdf", "f0000.enc"))))
            add(manifest(listOf(correct, record("document.pdf", "f0001.enc"))))
            add(manifest(listOf(correct), pdf = "missing.pdf"))
            add(manifest(listOf(correct), pdf = "scan.pdf"))
            add(manifest(listOf(correct, record("page_000.jpg", "f0001.enc")), pdf = "../scan.pdf"))
            add(manifest(listOf(correct, record("large.pdf", "f0001.enc", Long.MAX_VALUE))))
            add(manifest(listOf(correct) + List(10_000) { record("p$it.jpg", "f${it + 1}.enc") }))
        }
        val refused = cases.count { bytes ->
            try {
                parse.invoke(vault, bytes, "fixture")
                false
            } catch (e: InvocationTargetException) {
                e.cause is VaultAuthException
            }
        }
        assertEquals(before, state())
        assertEquals(0, vault.count())
        assertEquals("All invalid manifests must be rejected", cases.size, refused)
    }

    @Test
    fun `a v1 page-only entry stays portable but never opens an undeclared PDF`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional page-only entry")
        assertTrue(repo.getDocument(id)!!.pdfFile.delete())
        val vault = store()
        val setup = vault.setupConfirmed("246810")
        vault.addDocument(id)
        val backup = File(app.cacheDir, "page-only.swvault")
        vault.exportBackup(backup)
        vault.wipeAll()
        val restored = store()
        assertEquals(listOf(id), restored.importBackup(backup, setup.recoveryCode).map { it.id })
        assertThrows(VaultAuthException::class.java) { restored.openDocument(id) }
        assertFalse(File(app.cacheDir, "vault_open/$id").exists())
    }

    @Test
    fun `adoption preflight authenticates payloads without publishing or writing plaintext`() {
        val goodId = DocFixtures.saved(repo, app, pages = 1, name = "Fictional valid entry")
        val badId = DocFixtures.saved(repo, app, pages = 1, name = "Fictional damaged entry")
        val vault = store()
        vault.setupConfirmed("246810")
        vault.addDocument(goodId)
        vault.addDocument(badId)
        val oldKey = VaultSession.keyOrNull()!!.copyOf()
        val staged = File(app.cacheDir, "adoption-fixture")
        val vaultRoot = File(app.filesDir, "vault2")
        val valid = File(staged, goodId)
        val damaged = File(staged, badId)
        File(vaultRoot, goodId).copyRecursively(valid)
        File(vaultRoot, badId).copyRecursively(damaged)
        val changed = damaged.listFiles()!!.first { it.name.startsWith("f") }
        val bytes = changed.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        changed.writeBytes(bytes)
        vault.wipeAll()
        val verify = File(app.cacheDir, "vault_verify").apply { mkdirs() }
        File(verify, "sentinel").writeText("Fictional verification sentinel")
        val initialStaging = snapshot(staged)
        val initialCache = snapshot(verify)
        try {
            vault.validateStagedEntryForAdoption(valid, oldKey)
            assertThrows(VaultAuthException::class.java) {
                vault.validateStagedEntryForAdoption(damaged, oldKey)
            }
            assertEquals(initialStaging, snapshot(staged))
            assertEquals(initialCache, snapshot(verify))
            assertEquals(0, vault.count())
            assertFalse(vault.isSetUp())
        } finally {
            oldKey.fill(0)
        }
    }

    @Test
    fun `reencryption refuses a damaged entry and still restores a valid entry byte for byte`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Fictional restore entry")
        val original = snapshot(File(app.filesDir, "documents/$id"))
        val old = store()
        old.setupConfirmed("246810")
        old.addDocument(id)
        val oldKey = VaultSession.keyOrNull()!!.copyOf()
        val staged = File(app.cacheDir, "reencrypt-fixture/$id")
        File(app.filesDir, "vault2/$id").copyRecursively(staged)
        val payload = staged.listFiles()!!.first { it.name.startsWith("f") }
        val validBytes = payload.readBytes()
        val damaged = validBytes.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        payload.writeBytes(damaged)
        old.wipeAll()
        val vault = store()
        vault.setupConfirmed("135790")
        val vaultRoot = File(app.filesDir, "vault2")
        val cache = File(app.cacheDir, "vault_verify").apply { mkdirs() }
        val open = File(app.cacheDir, "vault_open").apply { mkdirs() }
        val roots = listOf(vaultRoot, cache, open, staged.parentFile!!)
        roots.forEach { File(it, "sentinel").writeText("Fictional sentinel") }
        val before = roots.map(::snapshot)
        try {
            assertThrows(VaultAuthException::class.java) { vault.restoreEntryFromBackup(staged, oldKey) }
            assertEquals(before, roots.map(::snapshot))
            assertEquals(0, vault.count())
            assertTrue(vaultRoot.listFiles()!!.none { it.isDirectory })
            assertTrue(cache.listFiles()!!.none { it.isDirectory })

            payload.writeBytes(validBytes)
            val result = vault.restoreEntryFromBackup(staged, oldKey)
            assertFalse(result.skipped)
            assertEquals(id, result.id)
            assertEquals(original, snapshot(vault.openDocument(id).dir))
        } finally {
            oldKey.fill(0)
        }
    }

    @Test
    fun `add to vault then open gives byte-identical files and lock wipes the plaintext copy`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Aadhaar card")
        val original = snapshot(File(app.filesDir, "documents/$id"))
        val store = store()
        val setup = store.setupConfirmed("246810")

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
        val setup = store.setupConfirmed("135790")
        store.lock()

        assertFalse(store.unlockWithRecovery("AAAAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(store.isUnlocked())

        assertTrue(store.unlockWithRecovery(VaultCrypto.formatRecoveryCode(setup.recoveryCode).lowercase()))
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `the biometric challenge opens the vault only when the cipher completes`() {
        val store = store()
        store.setupConfirmed("246810")
        assertFalse("a fresh setup has no fingerprint wrap yet", store.canUseBiometric())

        assertTrue(store.completeKeystoreWrap(store.beginKeystoreWrap()))
        assertTrue("the wrap makes the fingerprint available", store.canUseBiometric())
        store.lock()

        val challenge = store.beginBiometricUnlock()
        assertNotNull("the prompt gets a CryptoObject cipher", challenge.cipher)
        assertFalse("nothing is unlocked before the prompt", store.isUnlocked())

        assertTrue(store.completeBiometricUnlock(challenge))
        assertTrue(store.isUnlocked())
    }

    @Test
    fun `a vault from the previous build still unlocks with the passcode`() {
        // The previous build wrote keystore.bin with a Keystore key that no longer exists after
        // the upgrade (old alias), and no password list. Everything else is the same format.
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Old private document")
        val previous = store()
        val setup = previous.setupConfirmed("135790")
        previous.addDocument(id)
        previous.lock()

        val upgraded = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(), // same phone, but the old Keystore key is gone
            gate = VaultPasscodeGate(),
            iterations = fastIterations
        )
        assertTrue("the vault is still set up", upgraded.isSetUp())
        assertFalse("no usable fingerprint key, so the passcode is the offer", upgraded.canUseBiometric())

        val result = upgraded.unlockWithPasscode("135790")
        assertTrue("the passcode from the previous build still opens the vault", result.ok)
        assertTrue(upgraded.isUnlocked())
        assertEquals(listOf(id), upgraded.listEntries().map { it.id })
        assertEquals(24, VaultCrypto.normalizeRecoveryCode(setup.recoveryCode).length)
    }

    @Test
    fun `an invalidated keystore key falls back to the passcode and a new wrap is created`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Passport")
        val store = store()
        store.setupConfirmed("246810")
        assertTrue(store.completeKeystoreWrap(store.beginKeystoreWrap()))
        store.addDocument(id)
        store.lock()

        keys.invalidate() // a new fingerprint/face was enrolled on the phone
        assertFalse("the dead key is not offered", store.canUseBiometric())
        assertThrows(VaultKeyInvalidatedException::class.java) { store.beginBiometricUnlock() }

        // The user unlocks with the passcode; the screen then asks the prompt for a new wrap.
        assertTrue(store.unlockWithPasscode("246810").ok)
        assertFalse("the wrap is not silently recreated without authentication", store.canUseBiometric())
        assertEquals(listOf(id), store.listEntries().map { it.id })

        assertTrue(store.completeKeystoreWrap(store.beginKeystoreWrap()))
        assertTrue(store.canUseBiometric())

        // The fresh key can unwrap the same vault key again.
        store.lock()
        assertTrue(store.completeBiometricUnlock(store.beginBiometricUnlock()))
        assertEquals(listOf(id), store.listEntries().map { it.id })
    }

    @Test
    fun `a tampered encrypted byte fails the GCM tag and leaves no decrypted copy`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val store = store()
        store.setupConfirmed("246810")
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
        store.setupConfirmed("246810")
        store.addDocument(id)

        val vault2 = File(app.filesDir, "vault2")
        val leaks = vault2.walkTopDown().filter { it.isFile }.filter { leaksPlaintext(it.readBytes()) }.toList()
        assertTrue("plaintext leaves a visible format header: $leaks", leaks.isEmpty())
        assertFalse("the original directory is gone", File(app.filesDir, "documents/$id").exists())
    }

    @Test
    fun `a wrong passcode counts through the five-try lockout and the right one resumes`() {
        val store = store()
        store.setupConfirmed("246810")
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
        store.setupConfirmed("246810")

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

        store.setupConfirmed("246810")
        assertEquals(1, store.migrateLegacyVault())
        assertNull(repo.getDocument(id))
        assertEquals(listOf(id), store.listEntries().map { it.id })
        assertTrue(store.openDocument(id).pageFiles.isNotEmpty())
    }

    @Test
    fun `a passcode must be six or more digits and setup happens once`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.setupConfirmed("12345") }
        assertThrows(IllegalArgumentException::class.java) { store.setupConfirmed("12345a") }
        store.setupConfirmed("123456")
        assertThrows(IllegalStateException::class.java) { store.setupConfirmed("654321") }
    }

    @Test
    fun `backup contains no readable page or PDF and restores byte-identically with the code`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Medical report")
        val original = snapshot(File(app.filesDir, "documents/$id"))
        val store = store()
        val setup = store.setupConfirmed("246810")
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
