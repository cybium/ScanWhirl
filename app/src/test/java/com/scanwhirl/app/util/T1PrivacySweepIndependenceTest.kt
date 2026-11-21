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
 * T1: one unremovable entry must not abort the cold-start sweep and leave the other plaintext
 * copies on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class T1PrivacySweepIndependenceTest {

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

    private fun file(relative: String): File = File(cache, relative).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(64) { 7 })
    }

    @Test
    fun `one failing entry does not stop the other cache dirs from being swept`() {
        listOf("vault_open", "pdfa", "t1_passport", "t1_business_card", "exports")
            .forEach { file("$it/page.jpg") }
        // Capture caches are age-gated, so make their entries old enough to be eligible.
        val old = System.currentTimeMillis() - PrivacySweep.CAPTURE_CACHE_MAX_AGE_MS - 60_000L
        listOf("t1_passport", "t1_business_card").forEach {
            File(cache, "$it/page.jpg").setLastModified(old)
        }

        val real = PrivacySweep.deleteDirectory
        PrivacySweep.deleteDirectory = { dir ->
            if (dir.name == "pdfa") throw IllegalStateException("simulated failure")
            real(dir)
        }

        PrivacySweep.run(app)

        assertTrue("the failing entry is left for another launch", File(cache, "pdfa").exists())
        listOf("vault_open", "exports").forEach { name ->
            assertFalse("sweep stopped early: $name", File(cache, name).exists())
        }
        // Capture dirs stay (age-gated); only their stale entries are reclaimed.
        listOf("t1_passport", "t1_business_card").forEach { name ->
            assertFalse("sweep stopped early: $name", File(cache, "$name/page.jpg").exists())
        }
    }
}
