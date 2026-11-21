package com.scanwhirl.app.ux130.l5

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.vault.VaultActivity
import com.scanwhirl.app.ui.vault.VaultCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** L5-02 — reachable recovery/overflow targets and intact security copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L5VaultTrustTest {

    private lateinit var app: Application
    private lateinit var store: VaultStore
    private var controller: ActivityController<VaultActivity>? = null

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        store = VaultStore(app, ScanRepository(app), FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000)
        VaultActivity.storeOverride = store
        VaultActivity.biometricStatusOverride =
            androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        VaultActivity.fingerprintStarter = null
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        controller?.close()
        controller = null
        VaultActivity.storeOverride = null
        VaultActivity.biometricStatusOverride = null
        VaultActivity.fingerprintStarter = null
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): VaultActivity {
        controller = Robolectric.buildActivity(VaultActivity::class.java).setup()
        idle()
        return controller!!.get()
    }

    private fun sheet(): BottomSheetDialog = ShadowDialog.getLatestDialog() as BottomSheetDialog

    private fun reachRecoveryCode(): BottomSheetDialog {
        val setup = sheet()
        setup.findViewById<TextView>(R.id.vaultPasscodeInput)!!.text = "246810"
        setup.findViewById<TextView>(R.id.vaultPasscodeConfirm)!!.text = "246810"
        setup.findViewById<View>(R.id.swSheetPrimary)!!.performClick()
        val deadline = System.currentTimeMillis() + 5_000
        while (setup.isShowing && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(10) }
        idle()
        return sheet()
    }

    @Test
    fun `the lockout recovery link is a focusable 48dp Button`() {
        store.setup("246810")
        store.markConfirmationPassed()
        store.lock()
        launch()
        val link = sheet().findViewById<View>(R.id.vaultUseRecovery)
        assertNotNull(link)
        assertTrue(
            "recovery escape needs 48dp, was ${link!!.minimumHeight}px (RED on base: 44dp)",
            link.minimumHeight >= SwStyle.dp(app, 48f)
        )
        assertTrue("it is a focus stop", link.isFocusable)
        val info = AccessibilityNodeInfo.obtain()
        link.onInitializeAccessibilityNodeInfo(info)
        assertEquals(
            "TalkBack reads a Button",
            android.widget.Button::class.java.name, info.className.toString()
        )
        assertEquals("Use recovery code", (link as TextView).text.toString())
        link.performClick()
        idle()
        assertEquals("Use recovery code", sheet().findViewById<TextView>(R.id.swSheetTitle)!!.text.toString())
    }

    @Test
    fun `a Vault card overflow is 48dp and named for its entry`() {
        val card = VaultCard(app)
        val entry = VaultStore.Entry(
            id = "fictional", name = "Fictional insurance", createdAt = 0L, addedAt = 0L,
            pageCount = 2, fileCount = 1, sizeBytes = 1024L
        )
        card.bind(entry, "2 pages", {}, {})
        val more = card.moreButton
        assertTrue(
            "overflow needs 48dp, was ${more.layoutParams.width}x${more.layoutParams.height}px (RED on base: 36dp)",
            more.layoutParams.width >= SwStyle.dp(app, 48f) && more.layoutParams.height >= SwStyle.dp(app, 48f)
        )
        assertEquals(
            "More options for Fictional insurance",
            more.contentDescription.toString()
        )
    }

    @Test
    fun `recovery keeps every warning and adds the consequence and proof steps`() {
        launch()
        val recovery = reachRecoveryCode()
        val text = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) text.append(v.text).append('\n')
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(recovery.findViewById<View>(R.id.swSheetRoot)!!)
        val all = text.toString()
        assertTrue(
            "consequence summary is in the sheet (RED on base)",
            all.contains("This code is the only way back if you forget your passcode. It is shown once.")
        )
        assertTrue(
            "backup-sharing warning stays before consent",
            all.contains(app.getString(R.string.vault_recovery_copy_warning))
        )
        assertTrue(
            "loss warning stays before consent",
            all.contains(app.getString(R.string.vault_recovery_once))
        )
        assertTrue(
            "the proof step is announced before consent (RED on base)",
            all.contains("Next, type the first 4 characters to confirm you saved it.")
        )
        assertTrue("the code itself is still shown", recovery.findViewById<View>(R.id.vaultRecoveryCode) != null)
        assertTrue("Save as file is still offered", recovery.findViewById<View>(R.id.vaultRecoveryCopy) != null)
    }

    @Test
    fun `the recovery sheet keeps FLAG_SECURE`() {
        launch()
        val recovery = reachRecoveryCode()
        assertTrue(
            "no screenshot of a live recovery code",
            com.scanwhirl.app.util.SecureView.isSecure(recovery.window!!)
        )
    }
}
