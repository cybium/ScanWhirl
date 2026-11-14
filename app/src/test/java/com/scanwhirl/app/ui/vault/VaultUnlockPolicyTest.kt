package com.scanwhirl.app.ui.vault

import androidx.biometric.BiometricManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 UI: 04_vault — which unlock path the screen offers (design §4.1). The passcode is always
 * there; the fingerprint is offered only when the prompt can actually run and succeed.
 */
class VaultUnlockPolicyTest {

    @Test
    fun `a supported, enrolled phone with a keystore key offers the fingerprint`() {
        assertTrue(
            VaultUnlockPolicy.fingerprintOffered(
                apiLevel = 35,
                haveKeystoreKey = true,
                canAuthenticate = BiometricManager.BIOMETRIC_SUCCESS
            )
        )
        assertTrue(
            VaultUnlockPolicy.fingerprintOffered(
                apiLevel = VaultUnlockPolicy.MIN_API,
                haveKeystoreKey = true,
                canAuthenticate = BiometricManager.BIOMETRIC_SUCCESS
            )
        )
    }

    @Test
    fun `every canAuthenticate failure goes straight to the passcode`() {
        for (status in listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN
        )) {
            assertFalse(
                "status $status must not offer the fingerprint",
                VaultUnlockPolicy.fingerprintOffered(35, true, status)
            )
        }
    }

    @Test
    fun `below API 28 there is no prompt even when the hardware reports success`() {
        assertFalse(VaultUnlockPolicy.fingerprintOffered(27, true, BiometricManager.BIOMETRIC_SUCCESS))
        assertFalse(VaultUnlockPolicy.fingerprintOffered(24, true, BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun `a vault without a usable keystore key offers only the passcode`() {
        assertFalse(VaultUnlockPolicy.fingerprintOffered(35, false, BiometricManager.BIOMETRIC_SUCCESS))
    }
}
