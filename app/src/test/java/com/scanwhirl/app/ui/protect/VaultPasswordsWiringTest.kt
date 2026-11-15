package com.scanwhirl.app.ui.protect

import android.app.Application
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultPasswords
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * v2 do 05/09 with L2's Vault: the Protect sheet remembers the PDF password in the Vault and the
 * Unlock sheet can use it. Tests use L2's own `FakeVaultKeys` seam; the [VaultPasswordsFlow]
 * launcher is replaced so the "vault is locked" path runs without a second activity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultPasswordsWiringTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var vaultStore: VaultStore

    private val passcode = "246810"

    @Before
    fun setUp() {
        VaultSession.close()
        VaultPasswordsFlow.useDefaultLauncher()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanWhirlApp.from(app).repository
        File(app.filesDir, "vault2").deleteRecursively()
        vaultStore = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(),
            iterations = 2_000
        )
        com.scanwhirl.app.util.ProGate.setTestOverride(app, true)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        VaultPasswordsFlow.useDefaultLauncher()
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 12000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun setUpVault() {
        assertTrue(vaultStore.setup(passcode).recoveryCode.isNotEmpty())
        assertTrue("setup leaves the Vault unlocked", VaultSession.isUnlocked())
    }

    private fun waitForHome(scenario: ActivityScenario<MainActivity>, docs: Int) {
        assertTrue("home list loads", pollUntil {
            var ok = false
            scenario.onActivity { a ->
                ok = (a.findViewById<RecyclerView>(R.id.scanList).adapter?.itemCount ?: 0) >= docs
            }
            ok
        })
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun clickItem(index: Int) {
        val d = ShadowDialog.getLatestDialog()
        if (d is AlertDialog) d.listView.performItemClick(null, index, 0)
        else d.findViewById<ViewGroup>(R.id.swSheetItems).getChildAt(index).performClick()
        idle()
    }

    private fun openProtect(activity: MainActivity, docId: String) {
        val doc = requireNotNull(repo.getDocument(docId))
        LockFlow(activity, repo, activity, { listOf(doc) }, { _ -> }).protectDocument(doc)
        idle()
    }

    /** Tools → Lock → Saved scan → the document → the Unlock sheet. */
    private fun openUnlock(activity: MainActivity, docId: String) {
        val doc = requireNotNull(repo.getDocument(docId))
        LockFlow(activity, repo, activity, { listOf(doc) }, { _ -> }).chooseLockSource()
        idle()
        clickItem(0) // source_saved_scan
        clickItem(0) // the document
        clickItem(0) // Unlock PDF
    }

    /** Tools → Lock → Saved scan → the document → Password (change). */
    private fun openChangePassword(activity: MainActivity, docId: String) {
        val doc = requireNotNull(repo.getDocument(docId))
        LockFlow(activity, repo, activity, { listOf(doc) }, { _ -> }).chooseLockSource()
        idle()
        clickItem(0)
        clickItem(0)
        clickItem(1) // Password
    }

    private fun latestSheetWith(id: Int): Dialog? {
        val d = ShadowDialog.getLatestDialog() ?: return null
        return d.takeIf { it.findViewById<View>(id) != null }
    }

    private fun submitProtect(dialog: Dialog, password: String) {
        dialog.findViewById<EditText>(R.id.protectPassword)!!.setText(password)
        dialog.findViewById<EditText>(R.id.protectConfirm)!!.setText(password)
        dialog.findViewById<View>(R.id.protectSave)!!.performClick()
        idle()
    }

    private fun submitCurrentPassword(dialog: Dialog, password: String) {
        dialog.findViewById<EditText>(R.id.unlockPassword)!!.setText(password)
        dialog.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
        idle()
    }

    private fun assertNoPlaintext(password: String) {
        val roots = listOf(
            app.filesDir,
            File(app.dataDir, "shared_prefs"),
            app.cacheDir
        )
        fun scan(file: File) {
            if (file.isDirectory) {
                file.listFiles()?.forEach(::scan)
                return
            }
            if (!file.isFile || file.length() > 8L * 1024 * 1024) return
            val blob = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("plaintext password found in ${file.absolutePath}", blob.contains(password))
        }
        roots.forEach { if (it.exists()) scan(it) }
    }

    // ---- Protect sheet --------------------------------------------------

    @Test
    fun `Remember row is shown and ON only when a Vault is set up`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Book scan")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)

            scenario.onActivity { openProtect(it, id) }
            val before = latestSheetWith(R.id.protectPassword)
            assertNotNull("Protect sheet opens", before)
            assertEquals(
                "no Vault, no row",
                View.GONE,
                before!!.findViewById<View>(R.id.protectRememberRow).visibility
            )
            before.dismiss()

            setUpVault()
            scenario.onActivity { openProtect(it, id) }
            val after = latestSheetWith(R.id.protectPassword)
            assertNotNull("Protect sheet opens again", after)
            assertEquals(View.VISIBLE, after!!.findViewById<View>(R.id.protectRememberRow).visibility)
            assertTrue(
                "Remember in Vault is ON by default",
                after.findViewById<SwitchCompat>(R.id.protectRemember).isChecked
            )
            after.dismiss()
        }
    }

    @Test
    fun `protect with Remember saves the password encrypted in the Vault`() {
        setUpVault()
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Book scan")
        val password = "zQ7-remember-me-42"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openProtect(it, id) }
            val sheet = latestSheetWith(R.id.protectPassword)
            assertNotNull("Protect sheet opens", sheet)
            scenario.onActivity { submitProtect(sheet!!, password) }

            assertTrue("the PDF is protected", pollUntil { repo.getDocument(id)!!.isLocked })
            assertTrue(
                "the password was remembered",
                pollUntil { VaultPasswords.of(app).get(id) == password }
            )
            assertNoPlaintext(password)
        }
    }

    @Test
    fun `a locked Vault offers Unlock after the save completes`() {
        setUpVault()
        VaultSession.close()
        var unlockRequests = 0
        VaultPasswordsFlow.unlockLauncher = { _, reason, onReturn ->
            unlockRequests++
            assertEquals(
                "the reason names the save",
                app.getString(R.string.vault_reason_save_password),
                reason
            )
            assertTrue(vaultStore.unlockWithPasscode(passcode).ok)
            onReturn()
        }
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Book scan")
        val password = "locked-vault-pw-9"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openProtect(it, id) }
            val sheet = latestSheetWith(R.id.protectPassword)
            assertNotNull("Protect sheet opens", sheet)
            scenario.onActivity { submitProtect(sheet!!, password) }

            assertTrue("protection is not blocked by the locked Vault",
                pollUntil { repo.getDocument(id)!!.isLocked })
            assertTrue("the save screen opens first", pollUntil {
                var started: String? = null
                scenario.onActivity {
                    started = shadowOf(it).peekNextStartedActivity()?.component?.className
                }
                started == com.scanwhirl.app.ui.ExportResultActivity::class.java.name
            })
            val prompt = ShadowDialog.getLatestDialog()
            assertNotNull("the Vault prompt follows the save", prompt)
            assertEquals(
                "Save the password in Vault?",
                prompt!!.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )

            scenario.onActivity { prompt.findViewById<View>(R.id.swSheetPrimary).performClick() }
            assertTrue("Unlock stores the password",
                pollUntil { VaultPasswords.of(app).get(id) == password })
            assertEquals(1, unlockRequests)
        }
    }

    @Test
    fun `declining the Vault prompt says the password was not saved`() {
        setUpVault()
        VaultSession.close()
        VaultPasswordsFlow.unlockLauncher = { _, _, _ ->
            throw AssertionError("declining must not open the Vault")
        }
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Book scan")
        val password = "declined-vault-pw"

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openProtect(it, id) }
            val sheet = latestSheetWith(R.id.protectPassword)
            assertNotNull("Protect sheet opens", sheet)
            scenario.onActivity { submitProtect(sheet!!, password) }

            assertTrue("the prompt follows the save",
                pollUntil {
                    ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetTitle)
                        ?.text?.toString() == "Save the password in Vault?"
                })
            val prompt = ShadowDialog.getLatestDialog()!!
            scenario.onActivity { prompt.findViewById<View>(R.id.swSheetSecondary).performClick() }

            assertTrue("the decline note is shown",
                pollUntil {
                    ShadowToast.getTextOfLatestToast() ==
                        app.getString(R.string.vault_password_not_saved)
                })
            assertFalse("nothing was saved", VaultPasswordsFlow.has(app, id))
        }
    }

    // ---- Unlock sheet ---------------------------------------------------

    @Test
    fun `Vault button appears only when a password is saved`() {
        setUpVault()
        val id = DocFixtures.locked(repo, app, password = "1234")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)

            scenario.onActivity { openUnlock(it, id) }
            val without = latestSheetWith(R.id.unlockPassword)
            assertNotNull("Unlock sheet opens", without)
            assertEquals(
                "no saved password, no Vault button",
                View.GONE,
                without!!.findViewById<View>(R.id.unlockVault).visibility
            )
            without.dismiss()

            VaultPasswords.of(app).save(id, "1234")
            scenario.onActivity { openUnlock(it, id) }
            val with = latestSheetWith(R.id.unlockPassword)
            assertNotNull("Unlock sheet opens again", with)
            assertEquals(
                "a saved password offers the Vault",
                View.VISIBLE,
                with!!.findViewById<View>(R.id.unlockVault).visibility
            )
            with.dismiss()
        }
    }

    @Test
    fun `Use password saved in Vault unlocks the document, even when the Vault relocked`() {
        setUpVault()
        val id = DocFixtures.locked(repo, app, password = "1234")
        VaultPasswords.of(app).save(id, "1234")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openUnlock(it, id) }
            val sheet = latestSheetWith(R.id.unlockPassword)
            assertNotNull("Unlock sheet opens", sheet)
            assertEquals(View.VISIBLE, sheet!!.findViewById<View>(R.id.unlockVault).visibility)

            // The 2-minute grace expires while the sheet is open: tapping must unlock and retry.
            VaultSession.close()
            VaultPasswordsFlow.unlockLauncher = { _, reason, onReturn ->
                assertEquals(
                    "the unlock sheet says why the Vault opened",
                    app.getString(R.string.vault_reason_use_password),
                    reason
                )
                assertTrue(vaultStore.unlockWithPasscode(passcode).ok)
                onReturn()
            }
            scenario.onActivity { sheet.findViewById<View>(R.id.unlockVault).performClick() }

            assertTrue("the saved password opens the document",
                pollUntil { !repo.getDocument(id)!!.isLocked })
            assertFalse("the entry is dropped after unlocking",
                VaultPasswords.of(app).has(id))
        }
    }

    @Test
    fun `a stale saved password falls back to typing and leaves the document locked`() {
        setUpVault()
        val id = DocFixtures.locked(repo, app, password = "1234")
        VaultPasswords.of(app).save(id, "0000") // saved, but wrong

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openUnlock(it, id) }
            val sheet = latestSheetWith(R.id.unlockPassword)
            assertNotNull("Unlock sheet opens", sheet)
            scenario.onActivity { sheet!!.findViewById<View>(R.id.unlockVault).performClick() }

            assertTrue("the stale entry is reported",
                pollUntil {
                    ShadowToast.getTextOfLatestToast() ==
                        app.getString(R.string.vault_password_stale)
                })
            assertTrue("the document stays locked", repo.getDocument(id)!!.isLocked)
            assertNotNull("the sheet stays open for typing",
                latestSheetWith(R.id.unlockPassword))
        }
    }

    @Test
    fun `Remove protection unlocks the document and forgets the saved password`() {
        setUpVault()
        val id = DocFixtures.locked(repo, app, password = "1234")
        VaultPasswords.of(app).save(id, "1234")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openUnlock(it, id) }
            val sheet = latestSheetWith(R.id.unlockPassword)
            assertNotNull("Unlock sheet opens", sheet)
            scenario.onActivity {
                sheet!!.findViewById<EditText>(R.id.unlockPassword).setText("1234")
                sheet.findViewById<View>(R.id.unlockRemove).performClick()
            }
            idle()

            // The second confirmation: its buttons carry no ids, so find the confirm by its text.
            val confirm = ShadowDialog.getLatestDialog()
            assertNotNull("the confirmation opens", confirm)
            val button = allViews(confirm!!.window!!.decorView)
                .firstOrNull {
                    it is TextView && it.text?.toString() ==
                        app.getString(R.string.unlock_remove_confirm)
                }
            assertNotNull("the confirmation button exists", button)
            scenario.onActivity { button!!.performClick() }

            assertTrue("protection is removed", pollUntil { !repo.getDocument(id)!!.isLocked })
            assertFalse("the saved password is removed too",
                VaultPasswords.of(app).has(id))
        }
    }

    @Test
    fun `changing the password updates the Vault entry`() {
        setUpVault()
        val id = DocFixtures.locked(repo, app, password = "1234")
        VaultPasswords.of(app).save(id, "1234")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHome(scenario, 1)
            scenario.onActivity { openChangePassword(it, id) }
            val current = latestSheetWith(R.id.unlockPassword)
            assertNotNull("the current-password sheet opens", current)
            scenario.onActivity { submitCurrentPassword(current!!, "1234") }

            val change = latestSheetWith(R.id.protectPassword)
            assertNotNull("the Change password sheet opens", change)
            val newPassword = "new-pw-5678-ok"
            change!!.findViewById<EditText>(R.id.protectPassword).setText(newPassword)
            change.findViewById<EditText>(R.id.protectConfirm).setText(newPassword)
            scenario.onActivity { change.findViewById<View>(R.id.protectShare).performClick() }

            assertTrue("the PDF takes the new password",
                pollUntil { repo.verifyPdfPassword(id, newPassword) })
            assertTrue("the Vault entry is updated",
                pollUntil { VaultPasswords.of(app).get(id) == newPassword })
        }
    }
}
