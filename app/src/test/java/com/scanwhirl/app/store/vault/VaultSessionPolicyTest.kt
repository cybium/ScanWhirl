package com.scanwhirl.app.store.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §4.1: the Vault relocks after a 2-minute grace period in the background. */
class VaultSessionPolicyTest {

    @Test
    fun `relocks at two minutes and not a millisecond before`() {
        val left = 10_000L
        assertFalse(VaultSessionPolicy.shouldRelock(left, left + 119_999))
        assertTrue(VaultSessionPolicy.shouldRelock(left, left + 120_000))
        assertTrue(VaultSessionPolicy.shouldRelock(left, left + 500_000))
    }

    @Test
    fun `never relocks before the app has been backgrounded`() {
        assertFalse(VaultSessionPolicy.shouldRelock(0, Long.MAX_VALUE))
        assertFalse(VaultSessionPolicy.shouldRelock(0, 0))
    }
}
