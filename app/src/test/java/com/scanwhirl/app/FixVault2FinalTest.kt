package com.scanwhirl.app
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException

/**
 * T18-FIXVAULT2 final rework (lead ruling after the round-2 BLOCK on 4fa1054f).
 *
 * MUST-FIX 1: a prepared recovery-code candidate is bound to the Vault that prepared it; a stale
 * candidate can never be written into a different (e.g. restored) Vault, and dismissing the step
 * lets it start again.
 * MUST-FIX 2: provisioning fails closed when the non-secret pending marker cannot be saved, a
 * failed marker clear is never reported as success, and leftovers of an interrupted provisioning
 * can never shadow a restored passcode wrap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FixVault2FinalTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        store = newStore(app)
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride = BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        File(app.filesDir, "vault2").deleteRecursively()
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
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.cacheDir, "vault_open").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun newStore(context: Context): VaultStore = VaultStore(
        context = context,
        repo = repo,
        keys = FakeVaultKeys(),
        gate = VaultPasscodeGate(clock = { 0L }),
        iterations = 2_000
    )

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
    private fun <T : View> View.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> BottomSheetDialog.need(id: Int): T = findViewById<View>(id) as T

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> android.app.Activity.need(id: Int): T = findViewById<View>(id) as T

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

    private fun sheet(): BottomSheetDialog {
        idle()
        val dialogs = ShadowDialog.getShownDialogs()
            .filterIsInstance<BottomSheetDialog>()
            .filter { it.findViewById<View>(R.id.swSheetTitle) != null }
        assertTrue("a kit sheet should be showing", dialogs.isNotEmpty())
        return dialogs.last()
    }

    private fun awaitNewSheetSince(before: Int, title: String): BottomSheetDialog? {
        await { openedSince(before).any { titleOf(it) == title } }
        return openedSince(before).lastOrNull { titleOf(it) == title }
    }

    private fun right4(code: String): String = VaultCrypto.normalizeRecoveryCode(code).take(4)

    private fun texts(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { texts(root.getChildAt(it)) }
        else -> emptyList()
    }

    /** A populated Vault on "another phone" plus its .swvault file. */
    private class OtherVault(val backup: File, val recoveryCode: String, val docId: String)

    private fun buildOtherVault(name: String): OtherVault {
        val other = newStore(app)
        val setup = other.setup("135790")
        other.markConfirmationPassed()
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        other.addDocument(id)
        val file = File(app.cacheDir, "other-vault.swvault")
        other.exportBackup(file)
        other.wipeAll()
        File(app.cacheDir, "vault_open").deleteRecursively()
        return OtherVault(file, setup.recoveryCode, id)
    }

    /** Starts the replacement-code flow from Vault options and opens the code sheet. */
    private fun startReplacement(activity: VaultActivity): BottomSheetDialog {
        activity.need<View>(R.id.vaultMore).performClick()
        idle()
        texts(sheet().need<ViewGroup>(R.id.swSheetContent))
            .first { it.text?.toString() == "Recovery code" }
            .performClick()
        idle()
        val before = shownCount()
        sheet().need<View>(R.id.swSheetPrimary).performClick()   // Make a new code
        val codeSheet = awaitNewSheetSince(before, "Save this recovery code")
        assertNotNull("the replacement code is shown", codeSheet)
        return codeSheet!!
    }

    // ------------------------------------------------------------------
    // MUST-FIX 1: candidates and confirmations are bound to their Vault
    // ------------------------------------------------------------------

    @Test
    fun `a prepared replacement cannot be committed into a different Vault`() {
        val other = buildOtherVault("Restored bank statement")
        store = newStore(app)
        VaultActivity.storeOverride = store
        store.setup("246810")
        store.markConfirmationPassed()
        val prepared = store.prepareRecoveryCode()

        // A populated Vault is restored into the empty one: different key, different wrap.
        val recovered = store.importBackup(other.backup, other.recoveryCode)
        assertEquals(1, recovered.size)
        val restoredWrap = File(app.filesDir, "vault2/recovery.bin").readBytes()

        val rejected = runCatching { store.commitRecoveryCode(prepared) }
        assertTrue(
            "a candidate sealed for the previous Vault must never be written: " +
                rejected.exceptionOrNull(),
            rejected.isFailure
        )
        assertTrue(
            "the restored wrap is untouched",
            File(app.filesDir, "vault2/recovery.bin").readBytes().contentEquals(restoredWrap)
        )

        VaultSession.close()
        assertTrue("the restored code still opens the Vault", store.unlockWithRecovery(other.recoveryCode))
        assertEquals(1, store.listEntries().size)
        store.openDocument(other.docId)
    }

    @Test
    fun `a stale confirmation cannot write after the Vault was restored`() {
        val other = buildOtherVault("Restored statement")
        store = newStore(app)
        VaultActivity.storeOverride = store
        store.setup("246810")
        store.markConfirmationPassed()
        val activity = launch()

        val codeSheet = startReplacement(activity)
        val candidateCode = codeSheet.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        saveRecoveryFileForTest(controller!!.get(), codeSheet)
        codeSheet.need<View>(R.id.swSheetPrimary).performClick()   // I saved it
        idle()
        val confirm = sheet()
        assertEquals("Type 4 characters", titleOf(confirm))

        // The Vault changes identity under the open confirmation (a fictional restore).
        assertTrue(store.importBackup(other.backup, other.recoveryCode).isNotEmpty())
        val restoredWrap = File(app.filesDir, "vault2/recovery.bin").readBytes()

        val beforeRotate = shownCount()
        controller!!.recreate()
        idle()
        val restored = openedSince(beforeRotate).lastOrNull { titleOf(it) == "Type 4 characters" }
        assertNotNull("the check is still on screen after the rotation", restored)
        restored!!.need<EditText>(R.id.vaultRecoveryInput).setText(right4(candidateCode))
        restored.need<View>(R.id.swSheetPrimary).performClick()
        idle()

        assertTrue(
            "the stale confirmation must not write the old wrap into the restored Vault",
            File(app.filesDir, "vault2/recovery.bin").readBytes().contentEquals(restoredWrap)
        )
        assertFalse(
            "the failed stale attempt leaves the setup state alone",
            store.isConfirmationPending()
        )
        VaultSession.close()
        assertTrue(
            "the restored credential still opens the Vault",
            store.unlockWithRecovery(other.recoveryCode)
        )
        assertEquals(1, store.listEntries().size)
        store.openDocument(other.docId)
    }

    @Test
    fun `dismissing the code sheet lets the recovery step start again`() {
        store.setup("246810")
        store.markConfirmationPassed()
        val activity = launch()

        val first = startReplacement(activity)
        val firstCode = first.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        first.need<View>(R.id.swSheetClose).performClick()
        idle()
        assertFalse("the code sheet is closed", first.isShowing)

        // The same step must be startable again (a dismiss is not a permanent dead end).
        val before = shownCount()
        activity.need<View>(R.id.vaultRecoveryButton).performClick()
        idle()
        sheet().need<View>(R.id.swSheetPrimary).performClick()   // Make a new code
        val again = awaitNewSheetSince(before, "Save this recovery code")
        assertNotNull("the step starts again after a dismiss", again)
        assertNotEquals(
            "a fresh code is prepared",
            firstCode,
            again!!.need<TextView>(R.id.vaultRecoveryCode).text.toString()
        )
    }

    // ------------------------------------------------------------------
    // MUST-FIX 2: fail closed provisioning and no shadowing leftovers
    // ------------------------------------------------------------------

    @Test
    fun `a marker that cannot be saved does not provision a Vault`() {
        val broken = newStore(FailingPrefsContext(app, emptyMap()))
        val failed = runCatching { broken.setup("246810") }.isFailure
        assertTrue("setup must fail closed when the pending marker cannot be saved", failed)
        assertFalse("no Vault is set up", broken.isSetUp())
        assertFalse(
            "the half-written passcode wrap is removed",
            File(app.filesDir, "vault2/passcode.bin").exists()
        )
        assertFalse("the Vault is not left unlocked", VaultSession.isUnlocked())

        // A cold start sees no Vault and can set up cleanly.
        VaultSession.close()
        val fresh = newStore(app)
        fresh.recover()
        assertFalse(fresh.isSetUp())
        assertTrue("a retry succeeds", runCatching { fresh.setup("246810") }.isSuccess)
    }

    @Test
    fun `clearing the marker fails closed instead of reporting completion`() {
        val real = newStore(app)
        real.setup("246810")   // the real marker is now pending
        assertTrue(real.isConfirmationPending())

        val broken = newStore(FailingPrefsContext(app, mapOf("vault_setup_confirm_pending" to true)))
        assertThrows(IOException::class.java) { broken.markConfirmationPassed() }
        assertTrue(
            "the marker is still pending, so the Vault is not treated as finished",
            broken.isConfirmationPending()
        )
    }

    @Test
    fun `an interrupted setup leaves nothing that can shadow a later Vault`() {
        val probe = Faults.dieAtFileSync("recovery.bin.tmp")
        val interrupted = newStore(app)
        val failure = runCatching { interrupted.setup("246810") }
        assertTrue("the process dies before the set-up blob", failure.isFailure && probe.fired)
        assertFalse(interrupted.isSetUp())
        assertTrue(
            "the kill leaves a provisioning wrap behind",
            File(app.filesDir, "vault2/passcode.bin").exists()
        )

        VaultSession.close()
        val fresh = newStore(app)
        fresh.recover()
        assertFalse(
            "an incomplete provisioning transaction is cleaned up on the next start",
            File(app.filesDir, "vault2/passcode.bin").exists()
        )
        assertFalse(fresh.isSetUp())
    }

    @Test
    fun `a full backup restore replaces leftovers from an interrupted setup`() {
        // Another phone: a populated Vault, backed up with its recovery code.
        val other = newStore(app)
        val setup = other.setup("135790")
        other.markConfirmationPassed()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Bank statement")
        other.addDocument(id)
        val out = File(app.cacheDir, "full-backup.scanwhirl")
        FullBackup(app, other).write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)
        other.wipeAll()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()

        // This phone: a setup killed before its set-up blob (leftover passcode.bin).
        val probe = Faults.dieAtFileSync("recovery.bin.tmp")
        val interrupted = newStore(app)
        assertTrue(runCatching { interrupted.setup("246810") }.isFailure && probe.fired)
        assertFalse(interrupted.isSetUp())
        Faults.resetAll()
        VaultSession.close()

        // Full backup restore must not let the leftover wrap shadow the backup's own wrap.
        val fresh = newStore(app)
        val report = FullBackup(app, fresh).restore(out, setup.recoveryCode)
        assertEquals(1, report.vaultEntries)

        val unlock = fresh.unlockWithPasscode("135790")
        assertTrue("the backup's original passcode opens the Vault", unlock.ok)
        assertEquals("the restored entry is there", 1, fresh.listEntries().size)
        fresh.openDocument(fresh.listEntries().single().id)
        assertFalse("a restored Vault is not a half-finished setup", fresh.isConfirmationPending())
    }

    // ------------------------------------------------------------------
    // Follow-up (small): a pending Vault with a real entry
    // ------------------------------------------------------------------

    @Test
    fun `a pending Vault with a real entry keeps it shut`() {
        store.setup("246810")
        store.markConfirmationPassed()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Tax paper")
        store.addDocument(id)
        // The check is owed again (a re-enrollment that was interrupted).
        Prefs.setVaultSetupConfirmPending(app, true)

        val activity = launch()
        assertFalse(
            "adding files is hidden while the check is owed",
            activity.need<View>(R.id.vaultAddFiles).isVisible
        )
        assertTrue(
            "the store refuses to open an entry",
            runCatching { store.openDocument(id) }.isFailure
        )
        val list = activity.need<ViewGroup>(R.id.vaultList)
        assertEquals("the entry is still listed", 1, list.childCount)
        list.getChildAt(0).performClick()
        idle()
        assertNull("tapping it never opens the private PDF", shadowOf(activity).peekNextStartedActivity())
    }

    // ------------------------------------------------------------------
    // A SharedPreferences that cannot commit (test-only)
    // ------------------------------------------------------------------

    private class FailingPrefsContext(base: Context, private val initial: Map<String, Any>) :
        ContextWrapper(base) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            FailingPrefs(initial)
    }

    private class FailingPrefs(private val initial: Map<String, Any>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = initial.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            initial[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

        override fun getInt(key: String?, defValue: Int): Int = initial[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = initial[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = initial[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            initial[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = initial.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FailingEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class FailingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }
}
