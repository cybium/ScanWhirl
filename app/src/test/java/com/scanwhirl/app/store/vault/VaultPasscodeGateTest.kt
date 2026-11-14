package com.scanwhirl.app.store.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five wrong passcodes pause entry for 30 s; every further wrong passcode doubles it.
 */
class VaultPasscodeGateTest {

    private var now = 0L

    private fun gate() = VaultPasscodeGate(clock = { now })

    @Test
    fun `four wrong tries do not pause, the fifth pauses 30 seconds`() {
        val gate = gate()
        repeat(4) { assertEquals(0L, gate.onWrong()) }
        assertEquals(0L, gate.pauseMillis(now))
        assertEquals(30_000L, gate.onWrong())
        assertEquals(5, gate.failures)
    }

    @Test
    fun `a paused gate refuses and then allows entry again`() {
        val gate = gate()
        repeat(5) { gate.onWrong() }
        assertEquals(30_000L, gate.pauseMillis(now))
        now = 29_999
        assertTrue(gate.pauseMillis(now) > 0)
        now = 30_000
        assertEquals(0L, gate.pauseMillis(now))
    }

    @Test
    fun `each further wrong passcode doubles the pause`() {
        val gate = gate()
        repeat(5) { gate.onWrong() }
        now = 30_000
        assertEquals(60_000L, gate.onWrong())
        now += 60_000
        assertEquals(120_000L, gate.onWrong())
        now += 120_000
        assertEquals(240_000L, gate.onWrong())
    }

    @Test
    fun `a right passcode clears the failures`() {
        val gate = gate()
        repeat(6) { gate.onWrong() }
        gate.onSuccess()
        assertEquals(0, gate.failures)
        assertEquals(0L, gate.pauseMillis(now))
        assertEquals(0L, gate.onWrong())
    }
}
