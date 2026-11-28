package com.scanwhirl.app.form1

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.robolectric.Shadows.shadowOf

/**
 * FORM1 async seam for Robolectric. The Form screen decodes/encodes on Dispatchers.IO and
 * publishes the result on Dispatchers.Main. Idling only the main looper is not a completion
 * signal: a worker can finish just after [idle] returns, and its Main continuation then sits
 * on a different executor until the next pump. Queue Main on a test scheduler and pump BOTH
 * the scheduler and the main looper inside one bounded wait, so the tests observe the screen's
 * real completion signal instead of a scheduling coincidence.
 *
 * Install in @Before and reset in @After; Dispatchers.setMain is process-wide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormAsyncIdle {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    fun install() = Dispatchers.setMain(dispatcher)
    fun reset() = Dispatchers.resetMain()

    /** Pump the Main coroutine queue and the Robolectric main looper once. */
    fun idle() {
        // A Dispatchers.IO worker resumes Main asynchronously; one advance is not enough, so
        // callers repeat this inside a bounded deadline rather than assume a single drain.
        scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        idle()
        return condition()
    }
}
