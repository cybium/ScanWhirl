package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security audit (M-3): the legacy PIN is stored as a salted PBKDF2 hash, old bare-SHA-256
 * values still verify and are upgraded in place, and the PIN itself never appears in prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultPinHashTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun stored(): String =
        app.getSharedPreferences("scanwhirl_prefs", 0).getString("vault_pin_sha256", "").orEmpty()

    @Test
    fun `the stored value is a salted PBKDF2 hash, never the PIN`() {
        Prefs.setVaultPin(app, "1234")
        val value = stored()
        assertTrue(value, value.startsWith("pbkdf2$"))
        assertTrue("the PIN is not in the value", !value.contains("1234"))
        val salt = value.split('$')[2]
        Prefs.setVaultPin(app, "1234")
        assertNotEquals("a fresh salt every time", salt, stored().split('$')[2])
        assertTrue(Prefs.verifyVaultPin(app, "1234"))
        assertFalse(Prefs.verifyVaultPin(app, "4321"))
    }

    @Test
    fun `a legacy bare SHA-256 value still verifies and is upgraded on success`() {
        val legacy = VaultPinHash.legacySha256("2580")
        app.getSharedPreferences("scanwhirl_prefs", 0).edit()
            .putString("vault_pin_sha256", legacy).commit()

        assertFalse("a wrong PIN does not upgrade anything", Prefs.verifyVaultPin(app, "1111"))
        assertEquals("the legacy value is untouched after a wrong PIN", legacy, stored())

        assertTrue(Prefs.verifyVaultPin(app, "2580"))
        assertTrue("upgraded in place", stored().startsWith("pbkdf2$"))
        assertTrue("still the same PIN", Prefs.verifyVaultPin(app, "2580"))
    }

    @Test
    fun `a malformed stored value never verifies`() {
        listOf("", "pbkdf2\$abc\$x\$y", "pbkdf2\$0\$AAAA\$AAAA", "garbage").forEach { bad ->
            app.getSharedPreferences("scanwhirl_prefs", 0).edit()
                .putString("vault_pin_sha256", bad).commit()
            assertFalse("accepted: $bad", Prefs.verifyVaultPin(app, "1234"))
        }
    }

    @Test
    fun `only a four-digit PIN can be set`() {
        listOf("123", "12345", "12a4", "").forEach { bad ->
            kotlin.runCatching { Prefs.setVaultPin(app, bad) }.also {
                assertTrue("accepted: $bad", it.isFailure)
            }
        }
        assertFalse(Prefs.hasVaultPin(app))
    }
}
