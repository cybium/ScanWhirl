package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T1: the new plaintext capture caches are app-owned and must be reclaimed at cold start, just
 * like the Vault and OCR copies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class T1PrivacySweepCacheTest {

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
        writeBytes(ByteArray(2048) { 7 })
    }

    @Test
    fun `abandoned passport and business card cache files are swept at cold start`() {
        val old = System.currentTimeMillis() - PrivacySweep.CAPTURE_CACHE_MAX_AGE_MS - 60_000L
        val passport = file("t1_passport/photo.jpg")
        val card = file("t1_business_card/card.jpg")
        listOf(passport, card).forEach { it.setLastModified(old) }

        PrivacySweep.run(app)

        assertFalse("an abandoned passport photo must not survive a cold start", passport.exists())
        assertFalse("an abandoned business card must not survive a cold start", card.exists())
    }
}
