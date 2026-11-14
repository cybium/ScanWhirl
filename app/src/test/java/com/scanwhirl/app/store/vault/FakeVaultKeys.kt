package com.scanwhirl.app.store.vault

import java.util.UUID

/**
 * Test implementation of the Keystore seam. Robolectric has no hardware Keystore, so the
 * wrapped key lives in this instance's memory only; [clear] stands for a new phone.
 */
class FakeVaultKeys(var available: Boolean = true) : VaultKeys {

    private val wrapped = mutableMapOf<String, ByteArray>()

    override fun isAvailable(): Boolean = available

    override fun wrap(vaultKey: ByteArray): ByteArray {
        check(available) { "keystore unavailable" }
        val token = "fake-keystore-${UUID.randomUUID()}"
        wrapped[token] = vaultKey.copyOf()
        return token.toByteArray(Charsets.UTF_8)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val token = String(blob, Charsets.UTF_8)
        return wrapped[token]?.copyOf() ?: throw IllegalStateException("no such key")
    }

    override fun delete() {
        wrapped.clear()
    }

    fun clear() {
        wrapped.clear()
    }
}
