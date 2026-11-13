package com.scanwhirl.app.store

/**
 * Deterministic fault seam for tool operations (protect, unlock, merge, extract, compress,
 * organize drafts, ...). It mirrors [SaveFaults]: production code will call [at] with a step
 * name between the states of an operation; tests install a [hook] that throws to simulate
 * ENOSPC, cancellation or, by throwing [SimulatedProcessDeath] (an Error, so every
 * `catch (e: Exception)` cleanup is skipped), a process kill at that exact point.
 *
 * Planned callers: PM-3.1b (lock guard), PM-2.1b (publish) and PM-2.2b (drafts).
 * Nothing in production calls [at] yet (PM-0.2b only adds the seam).
 */
internal object OpFaults {
    @Volatile var hook: (String) -> Unit = {}
    fun at(step: String) = hook(step)
    fun reset() { hook = {} }
}
