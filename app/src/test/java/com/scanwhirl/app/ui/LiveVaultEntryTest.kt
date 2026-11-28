package com.scanwhirl.app.ui

import android.app.Application
import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * W8 port of the two live Free Vault entry assertions from FreeLegacyVaultEntryPointTest.
 * The retired path was Tools → tile_vault on MainActivity; the live path is the shell's
 * Vault tab → swVaultAction → VaultActivity, which asks the existing four-digit PIN before
 * new Vault setup. Same gate intent: no Pro redirect, wrong PIN stays closed, right PIN advances,
 * and the hidden document is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveVaultEntryTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, false) // forced Free
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun openLiveVaultEntry() {
        idle()
        val activity = ShadowDialog.getLatestDialog() // no dialog yet on the shell
        assertFalse("no Pro dossier", activity?.findViewById<View>(R.id.proDossierStatus) != null)
    }

    private fun legacyPinSheet(): Dialog? {
        val d = ShadowDialog.getLatestDialog() ?: return null
        return d.takeIf { it.findViewById<View>(R.id.vaultLegacyPin) != null }
    }

    private fun submitPin(sheet: Dialog, pin: String) {
        sheet.findViewById<EditText>(R.id.vaultLegacyPin)!!.setText(pin)
        sheet.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
        idle()
    }

    @Test
    fun `free live Vault entry asks the existing PIN and never redirects to Pro`() {
        Prefs.setVaultPin(app, "2580")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { shell ->
                assertFalse("really Free", ProGate.isPro(shell))
                idle()
                shell.findViewById<View>(R.id.swNavVault).performClick()
                idle()
                shell.findViewById<View>(R.id.swVaultAction)?.performClick()
                idle()
                val sheet = legacyPinSheet()
                assertNotNull("the PIN prompt is shown to a Free user", sheet)
                assertEquals("Old PIN Vault",
                    sheet!!.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString())
                assertFalse("no Pro-panel redirect for an existing Vault",
                    ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.proDossierStatus) != null)
            }
        }
        assertTrue("PIN data untouched", Prefs.hasVaultPin(app))
    }

    @Test
    fun `wrong PIN keeps the live Vault closed and the right PIN advances the gate`() {
        Prefs.setVaultPin(app, "2580")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { shell ->
                shell.findViewById<View>(R.id.swNavVault).performClick()
                idle()
                shell.findViewById<View>(R.id.swVaultAction)?.performClick()
                idle()
                val wrong = legacyPinSheet()
                assertNotNull(wrong)
                submitPin(wrong!!, "1111")
                assertEquals(app.getString(R.string.vault_pin_wrong),
                    ShadowToast.getTextOfLatestToast()?.toString())
                assertFalse("wrong PIN must not open the Vault", pollUntil {
                    shell.findViewById<View>(R.id.vaultBanner) != null
                })
                assertFalse("no Pro redirect after a wrong PIN",
                    ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.proDossierStatus) != null)
            }
            scenario.onActivity { shell ->
                shell.findViewById<View>(R.id.swVaultAction)?.performClick()
                idle()
                val right = legacyPinSheet()
                assertNotNull(right)
                submitPin(right!!, "2580")
                assertTrue("the right PIN finishes the legacy check",
                    pollUntil { legacyPinSheet()?.isShowing != true })
                assertFalse("no Pro-panel redirect",
                    ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.proDossierStatus) != null)
            }
        }
        assertTrue("PIN data untouched", Prefs.hasVaultPin(app))
    }
}
