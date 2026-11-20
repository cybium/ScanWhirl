package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.backup.FullBackup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultControlBlobTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun normalFullBackupRestoreAndBothUnlockPathsAreUnchanged() {
        val store = VaultStore(app, ScanRepository(app), keys = FakeVaultKeys(), iterations = 2000)
        val setup = store.setup("246810")
        store.markConfirmationPassed()
        VaultPasswords.of(app).save("fictional-id", "fictional-password")
        val file = File(app.cacheDir, "fictional-roundtrip.swbackup")
        FullBackup(app).write(file, "fictional-backup-password", FullBackup.Wrap.PASSWORD)
        store.wipeAll()
        val report = FullBackup(app).restore(file, "fictional-backup-password")
        assertTrue(report.warnings.isEmpty())
        assertTrue(store.unlockWithPasscode("246810").ok)
        assertEquals("fictional-password", VaultPasswords.of(app).get("fictional-id"))
        store.lock()
        assertTrue(store.unlockWithRecovery(setup.recoveryCode))
        assertEquals("fictional-password", VaultPasswords.of(app).get("fictional-id"))
        file.delete()
    }

    @Test fun oversizePreflightNeverOpensStreamAndGrowthIsBounded() {
        val file = File(app.cacheDir, "fictional-large-control")
        RandomAccessFile(file, "rw").use { it.setLength(512L * 1024 * 1024) }
        var opened = false
        assertThrows(VaultAuthException::class.java) {
            VaultControlBlobs.read(file, 4096) { opened = true; error("Must not open") }
        }
        assertFalse(opened)
        file.writeBytes(byteArrayOf(1))
        var requested = 0
        val growing = object : ByteArrayInputStream(ByteArray(20000)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                requested += length
                return super.read(bytes, offset, length)
            }
        }
        assertThrows(VaultAuthException::class.java) { VaultControlBlobs.read(file, 4096) { growing } }
        assertEquals("Only limit plus one byte may be requested", 4097, requested)
        file.delete()
    }

    @Test fun authenticatedPasswordPlaintextHasByteBudget() {
        val key = VaultCrypto.newKey()
        val file = File(app.cacheDir, "fictional-passwords.bin").apply {
            writeBytes(VaultCrypto.encryptBytes(ByteArray(1024 * 1024 + 1) { 65 }, key))
        }
        assertTrue(file.length() < VaultControlBlobs.MAX_PASSWORD_BYTES)
        assertThrows(VaultAuthException::class.java) { VaultPasswords.verifyFile(file, key) }
        file.delete()
    }

    @Test fun oversizedLocalWrapsAndPasswordsAreDamaged() {
        val store = VaultStore(app, ScanRepository(app), keys = FakeVaultKeys(), iterations = 2000)
        val setup = store.setup("246810")
        store.markConfirmationPassed()
        val dir = File(app.filesDir, "vault2")
        File(dir, "passwords.bin").writeBytes(ByteArray(2 * 1024 * 1024 + 1))
        assertThrows(VaultAuthException::class.java) { VaultPasswords.of(app).count() }
        File(dir, "passcode.bin").writeBytes(ByteArray(4097))
        store.lock()
        assertFalse(store.unlockWithPasscode("246810").ok)
        File(dir, "recovery.bin").writeBytes(ByteArray(4097))
        assertFalse(store.unlockWithRecovery(setup.recoveryCode))
    }

    @Test fun authenticatedPasswordListHasEntryBudget() {
        val store = VaultStore(app, ScanRepository(app), keys = FakeVaultKeys(), iterations = 2000)
        store.setup("246810")
        store.markConfirmationPassed()
        val plain = ("v1\n" + "aWQ=\tcHc=\n".repeat(10001)).toByteArray()
        val blob = VaultCrypto.encryptBytes(plain, VaultSession.keyOrNull()!!)
        val file = File(app.filesDir, "vault2/passwords.bin").apply { writeBytes(blob) }
        assertThrows(VaultAuthException::class.java) { VaultPasswords.verifyFile(file, VaultSession.keyOrNull()!!) }
    }
}
