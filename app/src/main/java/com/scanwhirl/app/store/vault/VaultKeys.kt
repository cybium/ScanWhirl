package com.scanwhirl.app.store.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seam around the Android Keystore. Robolectric has no hardware Keystore, so tests inject an
 * in-memory implementation. The real path is **DEVICE-UNPROVEN** until a phone run is recorded.
 */
interface VaultKeys {
    fun isAvailable(): Boolean
    fun wrap(vaultKey: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
    fun delete()
}

/**
 * AES-256 key in the Android Keystore. [wrap] of the random Vault key happens once at setup;
 * [unwrap] is the fingerprint/face path and may require user authentication.
 *
 * DEVICE-UNPROVEN: `setUserAuthenticationRequired(true)` means unwrap needs a
 * `BiometricPrompt` CryptoObject on a real phone. Not exercised by Robolectric.
 */
class AndroidVaultKeys(
    private val alias: String = DEFAULT_ALIAS
) : VaultKeys {

    companion object {
        const val DEFAULT_ALIAS = "scanwhirl_vault_key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun isAvailable(): Boolean = try {
        keyStore()
        true
    } catch (_: Exception) {
        false
    }

    override fun wrap(vaultKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(vaultKey)
        return cipher.iv + encrypted
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw VaultAuthException("Damaged Keystore wrap")
        val iv = blob.copyOfRange(0, IV_BYTES)
        val encrypted = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    override fun delete() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    private fun key(): SecretKey {
        val store = keyStore()
        val existing = try {
            (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (_: UnrecoverableKeyException) {
            null
        }
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(30)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
