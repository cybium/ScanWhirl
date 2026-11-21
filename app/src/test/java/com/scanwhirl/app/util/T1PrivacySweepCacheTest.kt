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
    fun `the passport and business card cache dirs are swept at cold start`() {
        val passport = file("t1_passport/photo.jpg")
        val card = file("t1_business_card/card.jpg")

        PrivacySweep.run(app)

        assertFalse("the passport photo must not survive a cold start", passport.exists())
        assertFalse("the business card must not survive a cold start", card.exists())
        assertFalse("the whole dir is gone", File(cache, "t1_passport").exists())
        assertFalse("the whole dir is gone", File(cache, "t1_business_card").exists())
    }
}
