package com.scanwhirl.app.ui.vault

import androidx.biometric.BiometricManager

/**
 * v2 UI: 04_vault / 09_unlock_sheet — which unlock path the Vault screen offers (design §4.1).
 *
 * The fingerprint path exists only when all three hold: the platform can run the AndroidX
 * biometric prompt (API 28+), the Vault has a Keystore wrap whose auth-bound key is still
 * valid, and `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` says the hardware is ready
 * with an enrolled fingerprint/face. In every other case the passcode is the only offer, so a
 * missing sensor or a wiped enrolment is never a dead end.
 *
 * Pure, so every `canAuthenticate` result can be tested without a sensor.
 */
object VaultUnlockPolicy {

    const val MIN_API = 28

    fun fingerprintOffered(
        apiLevel: Int,
        haveKeystoreKey: Boolean,
        canAuthenticate: Int
    ): Boolean = apiLevel >= MIN_API &&
        haveKeystoreKey &&
        canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
}
