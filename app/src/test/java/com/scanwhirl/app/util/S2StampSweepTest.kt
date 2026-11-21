package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * S2 item 1: the Stamp editor's saved-state files (signature + redaction marks) are plaintext
 * copies of private work left in `cacheDir/stamp_editor` after a process death. Like the capture
 * caches, they must survive a quick process-death restore but be reclaimed once abandoned (24 h).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class S2StampSweepTest {

    private lateinit var app: Application
    private lateinit var cache: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        cache = app.cacheDir
        cache.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        PrivacySweep.resetForTests()
        cache.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun stateFile(ageMs: Long): File {
        val file = File(cache, "stamp_editor/state_doc1_abcd1234.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(128) { 9 })
        }
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    @Test
    fun `a fresh stamp editor state survives a cold start so process-death restore still works`() {
        val fresh = stateFile(60_000L)
        PrivacySweep.run(app)
        assertTrue(
            "a save-state written seconds before process death must still be restorable",
            fresh.exists()
        )
    }

    @Test
    fun `an abandoned stamp editor state is reclaimed after the capture-cache age gate`() {
        val stale = stateFile(PrivacySweep.CAPTURE_CACHE_MAX_AGE_MS + 60_000L)
        PrivacySweep.run(app)
        assertFalse(
            "a stamp editor state older than a day must not survive a cold start",
            stale.exists()
        )
    }
}
