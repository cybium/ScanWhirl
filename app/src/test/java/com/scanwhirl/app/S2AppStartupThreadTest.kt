package com.scanwhirl.app

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S2 item 2: the cold-start privacy sweep is disk I/O and must run on the existing IO startup
 * path, not on the main thread in Application.onCreate. A thread probe records where it ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class S2AppStartupThreadTest {

    @Test
    fun `the cold-start privacy sweep runs off the main thread`() = runBlocking {
        val app: ScanWhirlApp = ApplicationProvider.getApplicationContext()
        var sweepThread: String? = null
        app.runStartupSweep { sweepThread = it }
        assertNotNull("the startup sweep must actually run", sweepThread)
        assertNotEquals(
            "the privacy sweep must not do disk I/O on the main thread",
            Looper.getMainLooper().thread.name,
            sweepThread
        )
    }
}
