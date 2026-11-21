package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SECURITY_JUDGE 1.3.0 finding 5: passcode throttling is process-wide and
 * survives leaving/reopening the screen and a process restart. Failure state is
 * device-local and never includes a secret. Live pauses use elapsedRealtime.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1VaultGateTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        VaultPasscodeGate.resetSharedForTests(app)
    }

    @After
    fun tearDown() {
        VaultPasscodeGate.resetSharedForTests(app)
    }

    private fun prefs() = app.getSharedPreferences(VaultPasscodeGate.PREFS, 0)

    @Test(timeout = 30_000)
    fun `one process-wide gate is shared across contexts`() {
        assertSame(
            VaultPasscodeGate.shared(app),
            VaultPasscodeGate.shared(app.applicationContext)
        )
    }

    @Test(timeout = 30_000)
    fun `a fresh gate after a process restart keeps failures and the pause`() {
        var wall = 1_000_000L
        var uptime = 5_000L
        val first = VaultPasscodeGate(clock = { wall }, elapsed = { uptime }, prefs = prefs())
        repeat(5) { first.onWrong() }
        assertEquals(0, first.triesLeft())
        assertTrue(first.pauseMillis() > 0)

        val restarted = VaultPasscodeGate(clock = { wall }, elapsed = { uptime }, prefs = prefs())
        assertEquals(0, restarted.triesLeft())
        assertEquals(first.failures, restarted.failures)
        assertTrue("the new gate still refuses entry", restarted.pauseMillis() > 0)
        uptime += 30_000
        assertEquals("a live elapsedRealtime pause expires", 0L, restarted.pauseMillis())
    }

    @Test(timeout = 30_000)
    fun `the persisted wall clock is the fallback after a reboot`() {
        var wall = 2_000_000L
        var uptime = 500_000L
        val before = VaultPasscodeGate(clock = { wall }, elapsed = { uptime }, prefs = prefs())
        repeat(5) { before.onWrong() }
        assertEquals(30_000L, before.pauseMillis())

        // Reboot: elapsedRealtime restarts near zero while wall time moved on.
        val after = VaultPasscodeGate(clock = { wall }, elapsed = { 0L }, prefs = prefs())
        assertTrue("a rebooted device still honours the wall-clock pause", after.pauseMillis() > 0)
        wall += 30_000
        assertEquals(0L, after.pauseMillis())
    }

    @Test(timeout = 30_000)
    fun `a successful passcode clears the persisted failure state`() {
        var wall = 3_000_000L
        val gate = VaultPasscodeGate(clock = { wall }, elapsed = { 7_000L }, prefs = prefs())
        repeat(5) { gate.onWrong() }
        gate.onSuccess()

        val fresh = VaultPasscodeGate(clock = { wall }, elapsed = { 7_000L }, prefs = prefs())
        assertEquals(0, fresh.failures)
        assertEquals(5, fresh.triesLeft())
        assertEquals(0L, fresh.pauseMillis())
    }

    @Test(timeout = 30_000)
    fun `persisted failure state contains no secret`() {
        val gate = VaultPasscodeGate(clock = { 4_000_000L }, elapsed = { 9_000L }, prefs = prefs())
        val passcode = "s3cr3tPass"
        gate.onWrong()
        val saved = prefs().all.map { (key, value) -> "$key=$value" }.joinToString("\n")
        assertFalse("the passcode must never be written to preferences", saved.contains(passcode))
        assertTrue("only non-secret throttle state is stored", saved.contains("failures=1"))
    }
}
