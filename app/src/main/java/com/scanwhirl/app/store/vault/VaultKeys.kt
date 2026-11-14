package com.scanwhirl.app.store.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The fingerprint key was reset (new biometrics enrolled): the wrap must be rebuilt. */
class VaultKeyInvalidatedException(message: String, cause: Throwable? = null) :
    VaultAuthException(message, cause)

/**
 * One Keystore operation waiting for the user: [cipher] is what `BiometricPrompt` authorizes
 * (as its CryptoObject), and [complete] runs it afterwards.
 *
 * Used for both directions, because a per-use auth-bound key needs a fresh authentication for
 * encrypt *and* decrypt: [VaultStore.beginBiometricUnlock] unwraps the vault key, and
 * [VaultStore.beginKeystoreWrap] creates the wrap that makes fingerprint unlock possible.
 */
class VaultKeyChallenge internal constructor(
    val cipher: Cipher?,
    private val finish: () -> ByteArray
) {
    /** Runs the authorized operation and returns its bytes. */
    fun complete(): ByteArray = try {
        finish()
    } catch (e: AEADBadTagException) {
        throw VaultAuthException("The fingerprint key no longer matches this Vault", e)
    }
}

/**
 * Seam around the Android Keystore. Robolectric has no hardware Keystore, so tests inject an
 * in-memory implementation. The real path is **DEVICE-UNPROVEN** until a phone run is recorded.
 */
interface VaultKeys {
    /** The Keystore itself can be opened on this phone. */
    fun isAvailable(): Boolean

    /** An auth-bound key for [ALIAS] exists (so the fingerprint prompt can be offered). */
    fun hasKey(): Boolean

    /**
     * Prepares the CryptoObject encrypt that writes the vault-key wrap. Only a prompt-authorized
     * cipher can encrypt with a per-use auth-bound key, so setup/repair must go through here.
     * Throws [VaultKeyInvalidatedException] when the key was invalidated.
     */
    fun wrapChallenge(vaultKey: ByteArray): VaultKeyChallenge

    /**
     * Prepares the CryptoObject decrypt of [blob]. Throws [VaultKeyInvalidatedException] when the
     * key was invalidated (new biometrics enrolled) and [VaultAuthException] for a damaged wrap.
     */
    fun challenge(blob: ByteArray): VaultKeyChallenge

    fun delete()
}

/**
 * AES-256-GCM key in the Android Keystore, **auth-bound**: `setUserAuthenticationRequired(true)`
 * without a validity duration means every use needs a fresh user authentication, provided by
 * `androidx.biometric.BiometricPrompt` as a CryptoObject. `setInvalidatedByBiometricEnrollment`
 * makes a new fingerprint/face invalidate the key instead of silently trusting it.
 *
 * The alias changed with the CryptoObject design (`_v2`); a wrap made with the old time-based
 * alias fails its GCM tag, the UI falls back to the passcode and the Vault re-wraps the key.
 *
 * DEVICE-UNPROVEN: Robolectric has no hardware Keystore; tests use [VaultKeys] fakes.
 */
class AndroidVaultKeys(
    private val alias: String = DEFAULT_ALIAS
) : VaultKeys {

    companion object {
        const val DEFAULT_ALIAS = "scanwhirl_vault_key_v2"

        /** The alias used before the CryptoObject design; deleted alongside the new one. */
        const val LEGACY_ALIAS = "scanwhirl_vault_key"
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

    override fun hasKey(): Boolean = try {
        keyStore().containsAlias(alias)
    } catch (_: Exception) {
        false
    }

    override fun wrapChallenge(vaultKey: ByteArray): VaultKeyChallenge {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete()
            throw VaultKeyInvalidatedException("The fingerprint key was reset on this phone", e)
        } catch (e: UnrecoverableKeyException) {
            delete()
            throw VaultKeyInvalidatedException("The fingerprint key is no longer usable", e)
        }
        return VaultKeyChallenge(cipher) {
            val encrypted = cipher.doFinal(vaultKey)
            cipher.iv + encrypted
        }
    }

    override fun challenge(blob: ByteArray): VaultKeyChallenge {
        if (blob.size <= IV_BYTES) throw VaultAuthException("Damaged Keystore wrap")
        val iv = blob.copyOfRange(0, IV_BYTES)
        val encrypted = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete()
            throw VaultKeyInvalidatedException("The fingerprint key was reset on this phone", e)
        } catch (e: UnrecoverableKeyException) {
            delete()
            throw VaultKeyInvalidatedException("The fingerprint key is no longer usable", e)
        }
        return VaultKeyChallenge(cipher) { cipher.doFinal(encrypted) }
    }

    override fun delete() {
        try {
            val store = keyStore()
            store.deleteEntry(alias)
            if (alias != LEGACY_ALIAS) store.deleteEntry(LEGACY_ALIAS)
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
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            // Per-use authentication (timeout 0) satisfied by a strong biometric only, which is
            // exactly what the BIOMETRIC_STRONG prompt offers. A device credential cannot unwrap.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        // Below API 30 the default for setUserAuthenticationRequired(true) is already per-use.
        generator.init(builder.build())
        return generator.generateKey()
    }
}
