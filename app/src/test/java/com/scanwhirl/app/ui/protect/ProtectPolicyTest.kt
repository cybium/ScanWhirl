package com.scanwhirl.app.ui.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Protect PDF sheet rules (`70` §4.2, mockup `05_protect_pdf`) and the Unlock sheet pacing
 * (mockup `09_unlock_sheet`). Pure logic, so no Android runtime is needed.
 */
class ProtectPolicyTest {

    // ---------------- password strength ----------------

    @Test
    fun `the meter shows a minimum of six and a weak warning below ten`() {
        assertEquals(PasswordStrength.EMPTY, ProtectPassword.of(""))
        assertEquals(PasswordStrength.TOO_SHORT, ProtectPassword.of("12345"))
        assertEquals(PasswordStrength.WEAK, ProtectPassword.of("123456"))
        assertEquals(PasswordStrength.WEAK, ProtectPassword.of("123456789"))
        assertEquals(PasswordStrength.GOOD, ProtectPassword.of("1234567890"))
        assertEquals(PasswordStrength.STRONG, ProtectPassword.of("12345678901234"))

        assertFalse("under 6 characters cannot be protected", ProtectPassword.accepts("12345"))
        assertTrue(ProtectPassword.accepts("123456"))
    }

    @Test
    fun `the 12-character mockup password is strong with three segments`() {
        val password = "correcthorse" // 12 characters
        assertEquals(12, ProtectPassword.length(password))
        assertEquals(PasswordStrength.GOOD, ProtectPassword.of(password))
        assertEquals(3, ProtectPassword.of(password).filledSegments)
        assertTrue(ProtectPassword.accepts(password))
    }

    @Test
    fun `an emoji counts as one character and Unicode is accepted`() {
        assertEquals(1, ProtectPassword.length("😀"))
        assertEquals(PasswordStrength.EMPTY, ProtectPassword.of(""))
        assertEquals(PasswordStrength.TOO_SHORT, ProtectPassword.of("😀😀😀😀😀"))
        assertTrue(ProtectPassword.accepts("😀😀😀😀😀😀"))
        assertTrue(ProtectPassword.accepts("पासवर्ड"))
        assertFalse("a lone surrogate is not text", ProtectPassword.accepts("abc\uD800def"))
    }

    @Test
    fun `the confirmation must match exactly`() {
        assertTrue(ProtectPassword.matches("hunter2", "hunter2"))
        assertFalse(ProtectPassword.matches("hunter2", "hunter3"))
        assertFalse("empty confirmation is not a match", ProtectPassword.matches("hunter2", ""))
    }

    // ---------------- unlock pacing ----------------

    @Test
    fun `five wrong tries start a thirty second pause that doubles`() {
        var now = 1_000L
        val cooldown = TryCooldown(clock = { now })

        repeat(4) { assertEquals("tries 1-4 do not pause", 0L, cooldown.registerFailure()) }
        assertTrue("still allowed before the fifth", cooldown.canTry())
        assertEquals(1, cooldown.triesLeft)

        assertEquals("the fifth wrong password pauses 30 s", 30_000L, cooldown.registerFailure())
        assertFalse("Open is disabled during the pause", cooldown.canTry())
        assertEquals(30_000L, cooldown.remainingPauseMs())
        assertEquals("a fresh set of tries after the pause", 5, cooldown.triesLeft)

        now += 29_999L
        assertFalse(cooldown.canTry())
        now += 1L
        assertTrue("the pause ends after 30 s", cooldown.canTry())

        repeat(4) { cooldown.registerFailure() }
        assertEquals("the next pause doubles", 60_000L, cooldown.registerFailure())
        assertEquals(60_000L, cooldown.remainingPauseMs())
    }

    @Test
    fun `the tries line announces the real next pause, including after it doubles`() {
        var now = 1_000L
        val cooldown = TryCooldown(clock = { now })
        assertEquals(30, cooldown.nextPauseSeconds())
        repeat(5) { cooldown.registerFailure() }
        assertEquals("the next cycle starts at a 60-second pause", 60, cooldown.nextPauseSeconds())
        now += 60_000L
        assertEquals(60, cooldown.nextPauseSeconds())
        cooldown.reset()
        assertEquals("a right password restores the 30-second pause", 30, cooldown.nextPauseSeconds())
    }

    @Test
    fun `the right password resets the tries and the pause`() {
        var now = 0L
        val cooldown = TryCooldown(clock = { now })
        repeat(5) { cooldown.registerFailure() }
        assertFalse(cooldown.canTry())

        cooldown.reset()
        assertEquals(5, cooldown.triesLeft)
        assertTrue(cooldown.canTry())
        assertEquals(0L, cooldown.remainingPauseMs())

        repeat(4) { cooldown.registerFailure() }
        assertEquals("a reset also resets the doubling", 30_000L, cooldown.registerFailure())
    }
}
