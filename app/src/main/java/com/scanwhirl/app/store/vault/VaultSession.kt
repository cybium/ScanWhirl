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

    /** One lock for open/close/expiry, so a close already in flight cannot race a fresh open. */
    private val lock = Any()

    // An opaque, in-memory session generation (never the key). A new unlock invalidates
    // enrollment save receipts even if the Vault's persistent identity did not change.
    private var generation = 0L

    internal fun sessionIdentity(): Long? = synchronized(lock) {
        if (keyOrNull() == null) null else generation
    }

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
    internal fun keyOrNull(now: Long = clock()): ByteArray? = synchronized(lock) {
        val current = key ?: return@synchronized null
        if (VaultSessionPolicy.shouldRelock(backgroundAt, now)) {
            // Expiry is a close of the session we just read. Do it under the same lock as open()
            // so a fresh unlock cannot slip in between the check and the drop/wipe.
            closeLocked()
            return@synchronized null
        }
        current
    }

    /** Remembers a freshly unwrapped vault key (setup or unlock). */
    internal fun open(vaultKey: ByteArray) {
        require(vaultKey.size == VaultCrypto.KEY_BYTES) { "A Vault key is 256 bits" }
        synchronized(lock) {
            // A new key replaces the old one in memory. It must not wipe copies a caller has just
            // decrypted under this key: only close()/expiry ends the lifetime of plaintext copies.
            dropKeyLocked()
            generation++
            key = vaultKey.copyOf()
            backgroundAt = 0L
        }
    }

    /**
     * Drops the key and the plaintext copies. Idempotent: a stale or duplicate close (no live key
     * left) is a no-op and must never wipe copies a fresh open() has just made. The lock is held
     * across the wipe so that a close already in flight cannot interleave with a new unlock.
     */
    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        if (key == null) return
        dropKeyLocked()
        // The key is already gone, so a failure to delete files cannot leave a usable Vault.
        try {
            openCopyWipe?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun dropKeyLocked() {
        key?.fill(0)
        key = null
        backgroundAt = 0L
    }

    /** The app went to the background: start the grace period if a key is held. */
    fun onBackground(now: Long = clock()) = synchronized(lock) {
        if (key != null) backgroundAt = now
    }

    /** Validate the elapsed background grace BEFORE clearing its timestamp. */
    fun onForeground(now: Long = clock()) {
        keyOrNull(now)
        synchronized(lock) { backgroundAt = 0L }
    }
}
