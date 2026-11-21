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

    /** A controllable wall clock for lifecycle/reader regression tests. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var key: ByteArray? = null

    /** When the process went to the background, or 0 while in the foreground. */
    @Volatile
    private var backgroundAt: Long = 0L

    /**
     * V2: the Vault store registers a wipe of its `cacheDir/vault_open` copies here. Dropping
     * the key on expiry must also drop the plaintext pages, not just stop future decryption.
     */
    @Volatile
    internal var openCopyWipe: (() -> Unit)? = null

    internal fun registerOpenCopyWipe(wipe: () -> Unit) {
        openCopyWipe = wipe
    }

    /** True while unlocked and inside the background grace period. Expires lazily. */
    fun isUnlocked(now: Long = clock()): Boolean = keyOrNull(now) != null

    /**
     * The vault key, or null when locked. Callers must not keep or modify it; it is the
     * session's copy.
     */
    internal fun keyOrNull(now: Long = clock()): ByteArray? {
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
        // A new key replaces the old one in memory. It must not wipe copies a caller has just
        // decrypted under this key: only close()/expiry ends the lifetime of plaintext copies.
        dropKey()
        key = vaultKey.copyOf()
        backgroundAt = 0L
    }

    /** Drops the key and forgets the background timestamp. */
    fun close() {
        dropKey()
        // The key is already gone, so a failure to delete files cannot leave a usable Vault.
        try {
            openCopyWipe?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun dropKey() {
        key?.fill(0)
        key = null
        backgroundAt = 0L
    }

    /** The app went to the background: start the grace period if a key is held. */
    fun onBackground(now: Long = clock()) {
        if (key != null) backgroundAt = now
    }

    /** Validate the elapsed background grace BEFORE clearing its timestamp. */
    fun onForeground(now: Long = clock()) {
        keyOrNull(now)
        backgroundAt = 0L
    }
}
