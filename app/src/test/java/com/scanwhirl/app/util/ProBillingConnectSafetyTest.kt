package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C2 item 6: the single billing connection's queue must never strand a caller. Closing the
 * client (and a startConnection that throws) delivers false to every waiter, and a setup
 * callback from an older attempt cannot settle a newer attempt's waiters.
 *
 * RED on base 282630d1: ConnectQueue has no settle/currentAttempt and close() drops waiters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProBillingConnectSafetyTest {

    @Test
    fun `settling false delivers false to every queued connect callback`() {
        val queue = ConnectQueue()
        val seen = mutableListOf<Boolean>()
        assertTrue("the first caller starts the connection", queue.add { seen += it })
        assertFalse("the second caller queues", queue.add { seen += it })
        assertFalse("the third caller queues", queue.add { seen += it })
        assertTrue("nobody is called before setup finishes", seen.isEmpty())

        queue.settle(false)

        assertEquals("all three waiters hear the failure", listOf(false, false, false), seen)
    }

    @Test
    fun `a stale setup callback cannot settle a newer attempt`() {
        val queue = ConnectQueue()
        val first = mutableListOf<Boolean>()
        assertTrue(queue.add { first += it })
        queue.add { first += it }
        val firstAttempt = queue.currentAttempt()
        queue.settle(true, firstAttempt)
        assertEquals(listOf(true, true), first)

        val second = mutableListOf<Boolean>()
        assertTrue("a new attempt starts after the first settles", queue.add { second += it })
        queue.add { second += it }
        val secondAttempt = queue.currentAttempt()
        assertFalse("the attempt id actually changes", firstAttempt == secondAttempt)

        // The old BillingClient setup callback arrives late and calls settle(oldId).
        queue.settle(false, firstAttempt)
        assertTrue("the stale callback never touches the new attempt's waiters", second.isEmpty())

        queue.settle(true, secondAttempt)
        assertEquals("only the current attempt's own callback settles it", listOf(true, true), second)
    }

    @Test
    fun `a late duplicate callback for a finished attempt is a no-op`() {
        val queue = ConnectQueue()
        val seen = mutableListOf<Boolean>()
        assertTrue(queue.add { seen += it })
        val attempt = queue.currentAttempt()
        queue.settle(true, attempt)
        queue.settle(false, attempt) // duplicate delivery from the same old setup
        assertEquals("one waiter, delivered once", listOf(true), seen)
    }
}
