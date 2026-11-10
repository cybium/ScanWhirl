package com.scanwhirl.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-22a: Verifies that [ProGate.resolveProState] never grants Pro in a
 * production build from a persisted test override (the original bug).
 *
 * All branches are exercised as a pure function — no Android context needed.
 */
class ProGateTest {

    // ---- Test-build behaviour (testBuild = true) ----------------------

    @Test
    fun `test build override 1 forces Free`() {
        assertFalse(ProGate.resolveProState(testBuild = true, override = 1, purchased = false))
        assertFalse(ProGate.resolveProState(testBuild = true, override = 1, purchased = true))
    }

    @Test
    fun `test build override 2 forces Pro`() {
        assertTrue(ProGate.resolveProState(testBuild = true, override = 2, purchased = false))
        assertTrue(ProGate.resolveProState(testBuild = true, override = 2, purchased = true))
    }

    @Test
    fun `test build default override 0 is Pro regardless of purchased`() {
        assertTrue(ProGate.resolveProState(testBuild = true, override = 0, purchased = false))
        assertTrue(ProGate.resolveProState(testBuild = true, override = 0, purchased = true))
    }

    @Test
    fun `test build any unknown override value defaults to Pro`() {
        assertTrue(ProGate.resolveProState(testBuild = true, override = 99, purchased = false))
        assertTrue(ProGate.resolveProState(testBuild = true, override = -1, purchased = false))
    }

    // ---- Production behaviour (testBuild = false) ---------------------

    @Test
    fun `production build unpurchased is never Pro regardless of override`() {
        // Core safety: persisted override=2 from a test session must NOT grant Pro
        assertFalse(ProGate.resolveProState(testBuild = false, override = 0, purchased = false))
        assertFalse(ProGate.resolveProState(testBuild = false, override = 1, purchased = false))
        assertFalse(ProGate.resolveProState(testBuild = false, override = 2, purchased = false))
        assertFalse(ProGate.resolveProState(testBuild = false, override = 99, purchased = false))
    }

    @Test
    fun `production build purchased is Pro regardless of override`() {
        assertTrue(ProGate.resolveProState(testBuild = false, override = 0, purchased = true))
        assertTrue(ProGate.resolveProState(testBuild = false, override = 1, purchased = true))
        assertTrue(ProGate.resolveProState(testBuild = false, override = 2, purchased = true))
    }
}
