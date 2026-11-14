package com.scanwhirl.app.store.vault

/**
 * Five wrong passcodes pause entry for 30 s; every further wrong passcode doubles the pause.
 * Time is injectable so a unit test can cross the pause without sleeping.
 */
class VaultPasscodeGate(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxTries: Int = 5,
    private val basePauseMillis: Long = 30_000
) {

    var failures: Int = 0
        private set

    private var pausedUntil: Long = 0

    fun pauseMillis(now: Long = clock()): Long = (pausedUntil - now).coerceAtLeast(0)

    /** Records a wrong attempt; returns the pause the user must now wait (0 when not paused). */
    fun onWrong(): Long {
        failures++
        if (failures < maxTries) return 0
        val doublings = (failures - maxTries).coerceAtMost(20)
        val pause = basePauseMillis * (1L shl doublings)
        pausedUntil = clock() + pause
        return pause
    }

    fun onSuccess() {
        failures = 0
        pausedUntil = 0
    }

    fun reset() = onSuccess()
}
