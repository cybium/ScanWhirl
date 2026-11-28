package com.scanwhirl.app.store.vault

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.File

/**
 * ScanWhirl 1.3.1 fix round 2, group G2 (bughunt R02 / R03).
 *
 * R02 F1: a `.swvault` restore left the old fingerprint wrap, so a later fingerprint unlock
 * opened the OLD key over the restored entries.
 * R02 F2: a restore after an interrupted setup left the setup-check marker set (the restored
 * Vault stayed shut, and "Save" on the post-restore passcode sheet crashed).
 * R02 NEW-1: a v1 `.swvault` restore kept the old key's passwords.bin, breaking every later
 * "Remember in Vault".
 * R03 NEW-1: a keyed Full Backup restore adopted an unparseable vault2/recovery.bin, marking
 * the Vault "set up" while no code could ever open it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Round2VaultRestoreTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var keys: FakeVaultKeys
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null

    private val vaultDir get() = File(app.filesDir, "vault2")

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        keys = FakeVaultKeys()
        store = newStore()
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        Faults.resetAll()
        VaultSession.close()
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun newStore(k: FakeVaultKeys = keys): VaultStore = VaultStore(
        context = app,
        repo = repo,
        keys = k,
        gate = VaultPasscodeGate(clock = { 0L }),
        iterations = 2_000
    )

    private fun VaultStore.setupConfirmed(passcode: String): VaultStore.Setup =
        setup(passcode).also { markConfirmationPassed() }

    /** A populated Vault on "another phone" plus its .swvault file. */
    private class OtherVault(val backup: File, val recoveryCode: String, val docId: String)

    private fun buildOtherVault(name: String, password: String? = null, v1: Boolean = false): OtherVault {
        val other = newStore(FakeVaultKeys())
        val setup = other.setupConfirmed("135790")
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        other.addDocument(id)
        if (password != null) VaultPasswords.of(app).save(id, password)
        val file = File(app.cacheDir, "other-vault-${System.nanoTime()}.swvault")
        other.exportBackup(file)
        if (v1) {
            // Same patch as VaultPasswordsTest: a v1 container has no extras section.
            val bytes = file.readBytes()
            bytes["SWVAULT1".length + 3] = 1
            file.writeBytes(bytes)
        }
        other.wipeAll()
        File(app.cacheDir, "vault_open").deleteRecursively()
        return OtherVault(file, setup.recoveryCode, id)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (true) {
            idle()
            if (condition()) return true
            if (System.nanoTime() >= deadline) {
                idle()
                return condition()
            }
            Thread.sleep(10)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> BottomSheetDialog.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> Activity.need(id: Int): T = findViewById<View>(id) as T

    private fun launch(): VaultActivity {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun shownCount(): Int {
        idle()
        return ShadowDialog.getShownDialogs().size
    }

    private fun openedSince(before: Int): List<BottomSheetDialog> {
        idle()
        return ShadowDialog.getShownDialogs().drop(before)
            .mapNotNull { it as? BottomSheetDialog }
            .filter { it.findViewById<View>(R.id.swSheetTitle) != null }
    }

    private fun titleOf(dialog: BottomSheetDialog): String =
        (dialog.findViewById<View>(R.id.swSheetTitle) as? TextView)?.text?.toString().orEmpty()

    private fun messageOf(dialog: BottomSheetDialog): String =
        (dialog.findViewById<View>(R.id.swSheetMessage) as? TextView)?.text?.toString().orEmpty()

    private fun awaitSheet(before: Int, title: String): BottomSheetDialog? {
        await { openedSince(before).any { titleOf(it) == title && it.isShowing } }
        return openedSince(before).lastOrNull { titleOf(it) == title && it.isShowing }
    }

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    /** Opens Vault options and taps the row labelled [label]. */
    private fun tapOption(activity: VaultActivity, label: String) {
        val before = shownCount()
        activity.need<View>(R.id.vaultMore).performClick()
        val more = awaitSheet(before, app.getString(R.string.vault_more_title))
        assertNotNull("Vault options open", more)
        texts(more!!.need<ViewGroup>(R.id.swSheetContent))
            .first { it.text?.toString() == label }
            .performClick()
        idle()
    }

    // ------------------------------------------------------------------
    // R02 F1: the old fingerprint wrap does not survive a .swvault restore
    // ------------------------------------------------------------------

    @Test
    fun `a swvault restore drops the old fingerprint wrap`() {
        val other = buildOtherVault("Restored payslip")
        store.setupConfirmed("246810")
        assertTrue(store.completeKeystoreWrap(store.beginKeystoreWrap()))
        assertTrue("precondition: this phone has a fingerprint wrap", store.canUseBiometric())
        File(vaultDir, "keystore.bin.tmp").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(1, store.importBackup(other.backup, other.recoveryCode).size)

        assertFalse("the old key's fingerprint wrap is gone", File(vaultDir, "keystore.bin").exists())
        assertFalse("and so is a half-written sibling", File(vaultDir, "keystore.bin.tmp").exists())
        assertFalse("fingerprint is not offered for the old key", store.canUseBiometric())
        store.lock()
        assertThrows(
            "no fingerprint unlock can open the old key",
            VaultAuthException::class.java
        ) { store.beginBiometricUnlock() }
        assertTrue("fail-safe: the restored code still opens the Vault", store.unlockWithRecovery(other.recoveryCode))
        assertEquals(1, store.listEntries().size)
    }

    @Test
    fun `a rebuilt fingerprint wrap after a restore opens the restored entries`() {
        // Positive control: the wrap created after the restore holds the restored key.
        val other = buildOtherVault("Restored lease")
        store.setupConfirmed("246810")
        store.completeKeystoreWrap(store.beginKeystoreWrap())
        store.importBackup(other.backup, other.recoveryCode)

        assertTrue(store.completeKeystoreWrap(store.beginKeystoreWrap()))
        store.lock()
        assertTrue(store.completeBiometricUnlock(store.beginBiometricUnlock()))
        assertEquals(listOf(other.docId), store.listEntries().map { it.id })
        store.openDocument(other.docId)
    }

    @Test
    fun `the post-restore passcode step offers fingerprint again and it opens the restored Vault`() {
        val other = buildOtherVault("Restored statement")
        store.setupConfirmed("246810")
        store.lock()
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_SUCCESS
        var prompts = 0
        VaultActivity.fingerprintStarter = { prompts++; true }
        val activity = launch()

        // A passcode unlock on this screen builds the first wrap (the one-per-screen offer).
        val unlock = openedSince(0).last { titleOf(it) == "Unlock Vault" }
        unlock.need<EditText>(R.id.vaultUnlockPasscode).setText("246810")
        unlock.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue("the first wrap is built", await { prompts == 1 && store.canUseBiometric() })

        // Restore through the screen: More -> Restore backup -> picker -> recovery code.
        val uri = Uri.parse("content://scanwhirl-test/other.swvault")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(other.backup.readBytes()))
        tapOption(activity, app.getString(R.string.vault_restore_title))
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Restore opens the file picker", request)
        val beforeCode = shownCount()
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
        val codeSheet = awaitSheet(beforeCode, app.getString(R.string.vault_restore_title))
        assertNotNull("the restore asks for the recovery code", codeSheet)
        codeSheet!!.need<EditText>(R.id.vaultRestoreCode).setText(other.recoveryCode)
        val beforePass = shownCount()
        codeSheet.need<View>(R.id.swSheetPrimary).performClick()
        val passSheet = awaitSheet(beforePass, app.getString(R.string.vault_new_passcode_title))
        assertNotNull("a new passcode is asked for after the restore", passSheet)
        assertFalse("the restore removed the old wrap", store.canUseBiometric())

        passSheet!!.need<EditText>(R.id.vaultNewPasscode).setText("112233")
        passSheet.need<EditText>(R.id.vaultNewPasscodeConfirm).setText("112233")
        passSheet.need<View>(R.id.swSheetPrimary).performClick()
        assertTrue(
            "the screen offers fingerprint again for the restored key (prompts=$prompts)",
            await { prompts == 2 && store.canUseBiometric() }
        )

        store.lock()
        assertTrue(store.completeBiometricUnlock(store.beginBiometricUnlock()))
        assertEquals("fingerprint opens the RESTORED Vault", listOf(other.docId), store.listEntries().map { it.id })
        store.openDocument(other.docId)
    }

    // ------------------------------------------------------------------
    // R02 F2: an interrupted setup's marker never outlives a restore
    // ------------------------------------------------------------------

    @Test
    fun `startup clean-up of an interrupted setup also clears its pending marker`() {
        val probe = Faults.dieAtFileSync("recovery.bin.tmp")
        assertTrue(runCatching { newStore().setup("246810") }.isFailure && probe.fired)
        Faults.resetAll()
        VaultSession.close()
        assertTrue("precondition: the kill left the marker", Prefs.vaultSetupConfirmPending(app))

        newStore().recover()

        assertFalse("the marker belongs to no Vault", Prefs.vaultSetupConfirmPending(app))
        assertFalse(File(vaultDir, "passcode.bin").exists())
    }

    @Test
    fun `a swvault restore after an interrupted setup finishes the Vault`() {
        val other = buildOtherVault("Restored policy")
        val probe = Faults.dieAtFileSync("recovery.bin.tmp")
        assertTrue(runCatching { newStore().setup("246810") }.isFailure && probe.fired)
        Faults.resetAll()
        VaultSession.close()
        assertTrue("precondition: the marker is still set", Prefs.vaultSetupConfirmPending(app))

        val fresh = newStore()
        assertEquals(1, fresh.importBackup(other.backup, other.recoveryCode).size)

        assertFalse("typing the backup's code proves it: nothing is owed", fresh.isConfirmationPending())
        fresh.changePasscode("112233")   // threw VaultPendingException before the fix
        fresh.lock()
        assertTrue(fresh.unlockWithPasscode("112233").ok)
        fresh.openDocument(other.docId)
    }

    @Test
    fun `a pending check while saving a new passcode shows the notice instead of crashing`() {
        store.setupConfirmed("246810")
        val before = File(vaultDir, "passcode.bin").readBytes()
        val activity = launch()
        val beforePass = shownCount()
        tapOption(activity, app.getString(R.string.vault_change_passcode))
        val passSheet = awaitSheet(beforePass, app.getString(R.string.vault_new_passcode_title))
        assertNotNull(passSheet)

        // The check becomes owed under the open sheet (e.g. a re-enrollment elsewhere).
        Prefs.setVaultSetupConfirmPending(app, true)
        passSheet!!.need<EditText>(R.id.vaultNewPasscode).setText("112233")
        passSheet.need<EditText>(R.id.vaultNewPasscodeConfirm).setText("112233")
        val beforeSave = shownCount()
        passSheet.need<View>(R.id.swSheetPrimary).performClick()

        val pendingNote = app.getString(R.string.vault_pending_note)
        assertTrue(
            "the pending notice is shown",
            await { openedSince(beforeSave).any { messageOf(it) == pendingNote && it.isShowing } }
        )
        assertFalse("the passcode sheet is closed", passSheet.isShowing)
        assertTrue("the passcode wrap is unchanged", File(vaultDir, "passcode.bin").readBytes().contentEquals(before))
    }

    // ------------------------------------------------------------------
    // R02 NEW-1: a v1 restore never keeps the old key's passwords.bin
    // ------------------------------------------------------------------

    @Test
    fun `a v1 restore drops the old key's remembered passwords so new saves work`() {
        val other = buildOtherVault("Old v1 backup", v1 = true)
        store.setupConfirmed("246810")
        VaultPasswords.of(app).save("doc-from-old-vault", "pw-old")
        assertTrue(File(vaultDir, VaultPasswords.FILE_NAME).isFile)

        store.importBackup(other.backup, other.recoveryCode)

        assertFalse("the old key's list is gone", File(vaultDir, VaultPasswords.FILE_NAME).exists())
        VaultPasswords.of(app).save(other.docId, "pw-new")   // threw VaultAuthException before the fix
        assertEquals("pw-new", VaultPasswords.of(app).get(other.docId))
        assertNull(VaultPasswords.of(app).get("doc-from-old-vault"))
    }

    @Test
    fun `a v2 restore still replaces the local list with the backup's passwords`() {
        val other = buildOtherVault("New v2 backup", password = "pw-backup")
        store.setupConfirmed("246810")
        VaultPasswords.of(app).save("doc-from-old-vault", "pw-old")

        store.importBackup(other.backup, other.recoveryCode)

        assertEquals("pw-backup", VaultPasswords.of(app).get(other.docId))
        VaultSession.close()
        assertTrue(store.unlockWithRecovery(other.recoveryCode))
        assertEquals("pw-backup", VaultPasswords.of(app).get(other.docId))
    }

    // ------------------------------------------------------------------
    // R03 NEW-1: a keyed full restore never adopts an unparseable recovery wrap
    // ------------------------------------------------------------------

    private val password = "correct horse battery"

    /** A keyed (vault-key.bin) full backup of a populated Vault, optionally with a junk wrap. */
    private fun keyedFullBackup(junkWrap: Boolean): Pair<File, VaultStore.Setup> {
        val old = newStore(FakeVaultKeys())
        val setup = old.setupConfirmed("246810")
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Payslip")
        old.addDocument(id)
        if (junkWrap) File(vaultDir, "recovery.bin").writeBytes(ByteArray(200) { 0x41 })
        val out = File(app.cacheDir, "keyed-${System.nanoTime()}.swbackup")
        FullBackup(app, old).write(out, password, FullBackup.Wrap.PASSWORD)
        assertTrue("the archive carries the Vault key", FullBackup(app).header(out).hasVaultKey)
        VaultSession.close()
        vaultDir.deleteRecursively()
        return out to setup
    }

    @Test
    fun `a keyed full restore rejects an unparseable recovery wrap instead of bricking the Vault`() {
        val (out, _) = keyedFullBackup(junkWrap = true)
        val phone = newStore()

        val report = FullBackup(app, phone).restore(out, password)

        assertFalse("no wrap that no code can open is adopted", File(vaultDir, "recovery.bin").exists())
        assertFalse("the phone's Vault is not marked set up", phone.isSetUp())
        assertEquals("no Vault entry is counted", 0, report.vaultEntries)
        assertTrue(
            "no entry folder was adopted",
            vaultDir.listFiles().orEmpty().none { it.isDirectory && File(it, "manifest.enc").isFile }
        )
        val prefix = app.getString(R.string.backup_vault_entries_rejected, 0).substringBefore(':')
        assertTrue("the person is told: ${report.warnings}", report.warnings.any { it.startsWith(prefix) })
        assertTrue("a fresh setup is still possible", runCatching { phone.setup("112233") }.isSuccess)
    }

    @Test
    fun `a keyed full restore with a valid wrap still adopts and opens with the recovery code`() {
        val (out, setup) = keyedFullBackup(junkWrap = false)
        val phone = newStore()

        val report = FullBackup(app, phone).restore(out, password)

        assertEquals(1, report.vaultEntries)
        assertTrue(phone.isSetUp())
        assertTrue(phone.unlockWithRecovery(setup.recoveryCode))
        assertEquals(1, phone.listEntries().size)
        phone.openDocument(phone.listEntries().single().id)
    }

    @Test
    fun `the structural wrap check matches what open accepts`() {
        val key = VaultCrypto.newKey()
        val good = VaultKeyWrap.seal(key, "secret", VaultCrypto.newSalt(), 2_000)
        assertTrue(VaultKeyWrap.isWellFormed(good))
        assertTrue(VaultKeyWrap.open(good, "secret").contentEquals(key))
        assertFalse(VaultKeyWrap.isWellFormed(ByteArray(200) { 0x41 }))
        assertFalse("truncated", VaultKeyWrap.isWellFormed(good.copyOf(good.size - 1)))
        assertFalse("empty", VaultKeyWrap.isWellFormed(ByteArray(0)))
        assertThrows(VaultAuthException::class.java) { VaultKeyWrap.open(good, "wrong") }
    }
}
