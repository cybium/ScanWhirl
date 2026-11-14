package com.scanwhirl.app.store.vault

/**
 * The Vault's unlocked state for the whole process (design §4.1).
 *
 * The vault key is held once, not once per screen: the Vault screen unlocks it, the PDF
 * viewer reads decrypted copies, and `VaultPasswords` (lane L3's "Remember in Vault") reads
 * it. Any screen therefore sees the same lock state, and the 2-minute background grace
 * expires the key for everyone at once — a second `VaultStore` can never keep a stale key.
 *
 * The key exists only in memory, only while unlocked; [close] overwrites it.
 */
object VaultSession {

    @Volatile
    private var key: ByteArray? = null

    /** When the process went to the background, or 0 while in the foreground. */
    @Volatile
    private var backgroundAt: Long = 0L

    /** True while unlocked and inside the background grace period. Expires lazily. */
    fun isUnlocked(now: Long = System.currentTimeMillis()): Boolean = keyOrNull(now) != null

    /**
     * The vault key, or null when locked. Callers must not keep or modify it; it is the
     * session's copy.
     */
    internal fun keyOrNull(now: Long = System.currentTimeMillis()): ByteArray? {
        val current = key ?: return null
        if (VaultSessionPolicy.shouldRelock(backgroundAt, now)) {
            close()
            return null
        }
        return current
    }

    /** Remembers a freshly unwrapped vault key (setup or unlock). */
    internal fun open(vaultKey: ByteArray) {
        require(vaultKey.size == VaultCrypto.KEY_BYTES) { "A Vault key is 256 bits" }
        close()
        key = vaultKey.copyOf()
        backgroundAt = 0L
    }

    /** Drops the key and forgets the background timestamp. */
    fun close() {
        key?.fill(0)
        key = null
        backgroundAt = 0L
    }

    /** The app went to the background: start the grace period if a key is held. */
    fun onBackground(now: Long = System.currentTimeMillis()) {
        if (key != null) backgroundAt = now
    }

    /** A Vault screen is in front again: the grace period no longer applies. */
    fun onForeground() {
        backgroundAt = 0L
    }
}
