package com.scanwhirl.app.store.vault

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.UUID

/**
 * Test implementation of the Keystore seam. Robolectric has no hardware Keystore, so the
 * wrapped key lives in this instance's memory only; [clear] stands for a new phone and
 * [invalidate] stands for "a new fingerprint was enrolled" (KeyPermanentlyInvalidatedException).
 */
class FakeVaultKeys(var available: Boolean = true) : VaultKeys {

    private val wrapped = mutableMapOf<String, ByteArray>()

    /** Set when a biometric enrolment wiped the key; [wrap] creates a fresh one. */
    var invalidated: Boolean = false
        private set

    override fun isAvailable(): Boolean = available

    override fun hasKey(): Boolean = !invalidated && wrapped.isNotEmpty()

    override fun wrap(vaultKey: ByteArray): ByteArray {
        check(available) { "keystore unavailable" }
        invalidated = false
        val token = "fake-keystore-${UUID.randomUUID()}"
        wrapped[token] = vaultKey.copyOf()
        return token.toByteArray(Charsets.UTF_8)
    }

    override fun challenge(blob: ByteArray): VaultKeyChallenge {
        if (invalidated) throw VaultKeyInvalidatedException("fake: key invalidated")
        val token = String(blob, Charsets.UTF_8)
        val key = wrapped[token] ?: throw VaultAuthException("fake: no such key")
        // A real Cipher instance, like the Keystore hands to BiometricPrompt.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"))
        }
        return VaultKeyChallenge(cipher) { key.copyOf() }
    }

    override fun delete() {
        wrapped.clear()
        invalidated = false
    }

    /** A new fingerprint/face was enrolled: the old key cannot be used again. */
    fun invalidate() {
        wrapped.clear()
        invalidated = true
    }

    fun clear() {
        wrapped.clear()
        invalidated = false
    }
}
