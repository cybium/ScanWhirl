package com.scanwhirl.app.store.vault

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Five wrong passcodes pause entry for 30 s; every further wrong passcode doubles the pause.
 *
 * SECURITY_JUDGE 1.3.0 finding 5: a gate tied to one [VaultStore] instance reset the
 * backoff every time the Vault screen was rebuilt. Production stores now share one
 * process-wide gate and persist only the failure count and the pause timestamps in
 * device-local preferences (no secret, no passcode), so reopening or restarting keeps
 * the backoff. The live pause uses the monotonic [SystemClock.elapsedRealtime]; a
 * persisted wall-clock pause is the fallback after a reboot resets elapsed time.
 *
 * The no-preferences constructor keeps the original in-memory behaviour, so pure
 * policy unit tests stay deterministic with an injected [clock].
 */
class VaultPasscodeGate(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxTries: Int = 5,
    private val basePauseMillis: Long = 30_000,
    private val prefs: SharedPreferences? = null,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() }
) {

    companion object {
        internal const val PREFS = "scanwhirl_vault_gate"
        private const val KEY_FAILURES = "failures"
        private const val KEY_PAUSED_UNTIL = "paused_until_wall"
        private const val KEY_WALL_AT = "wall_at_pause"
        private const val KEY_ELAPSED_AT = "elapsed_at_pause"

        @Volatile
        private var instance: VaultPasscodeGate? = null

        /** One gate per process, backed by device-local preferences. */
        @Synchronized
        fun shared(context: Context): VaultPasscodeGate {
            instance?.let { return it }
            val storage = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VaultPasscodeGate(prefs = storage).also { instance = it }
        }

        /** Tests only: drop the shared gate and its persisted failure state. */
        @Synchronized
        internal fun resetSharedForTests(context: Context) {
            instance = null
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    var failures: Int = 0
        private set

    private var pausedUntil: Long = 0
    private var pausedFor: Long = 0L
    private var wallAtPause: Long = 0L
    private var elapsedAtPause: Long = 0L

    init {
        prefs?.let { p ->
            failures = p.getInt(KEY_FAILURES, 0).coerceAtLeast(0)
            pausedUntil = p.getLong(KEY_PAUSED_UNTIL, 0L)
            wallAtPause = p.getLong(KEY_WALL_AT, 0L)
            elapsedAtPause = p.getLong(KEY_ELAPSED_AT, 0L)
            pausedFor = if (wallAtPause > 0L) (pausedUntil - wallAtPause).coerceAtLeast(0L) else 0L
        }
    }

    private fun persist() {
        val p = prefs ?: return
        p.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_PAUSED_UNTIL, pausedUntil)
            .putLong(KEY_WALL_AT, wallAtPause)
            .putLong(KEY_ELAPSED_AT, elapsedAtPause)
            .apply()
    }

    /**
     * Remaining pause. With preferences, the monotonic elapsed clock drives a
     * live pause; the persisted wall-clock deadline is used only when that clock
     * belongs to an earlier boot (or is unavailable). No clock may be consulted
     * in the pure in-memory mode, so injected-clock unit tests stay exact.
     */
    fun pauseMillis(now: Long = clock()): Long {
        if (pausedUntil <= 0L) return 0L
        if (prefs == null) return (pausedUntil - now).coerceAtLeast(0L)
        val uptime = elapsed()
        return if (elapsedAtPause > 0L && uptime >= elapsedAtPause) {
            (pausedFor - (uptime - elapsedAtPause)).coerceAtLeast(0L)
        } else {
            (pausedUntil - now).coerceAtLeast(0L)
        }
    }

    /** How many tries are left before the pause; 0 while paused or after five failures. */
    fun triesLeft(): Int = (maxTries - failures).coerceAtLeast(0)

    /** Records a wrong attempt; returns the pause the user must now wait (0 when not paused). */
    fun onWrong(): Long {
        failures++
        if (failures < maxTries) {
            persist()
            return 0
        }
        val doublings = (failures - maxTries).coerceAtMost(20)
        val pause = basePauseMillis * (1L shl doublings)
        val wall = clock()
        pausedUntil = wall + pause
        if (prefs != null) {
            wallAtPause = wall
            pausedFor = pause
            elapsedAtPause = elapsed()
        }
        persist()
        return pause
    }

    fun onSuccess() {
        failures = 0
        pausedUntil = 0
        pausedFor = 0L
        wallAtPause = 0L
        elapsedAtPause = 0L
        persist()
    }

    fun reset() = onSuccess()
}
