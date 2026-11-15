package com.scanwhirl.app.util

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Security audit (clipboard): secrets are marked sensitive and cleared after a minute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardPrivacyTest {

    private lateinit var app: Application
    private lateinit var clipboard: ClipboardManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
    }

    @After
    fun tearDown() {
        clipboard.clearPrimaryClip()
    }

    @Test
    fun `a recovery code is marked sensitive on Android 13 and later`() {
        ClipboardPrivacy.copy(app, "Recovery code", "ABCD EFGH IJKL MNOP QRST UVWX")
        val clip = clipboard.primaryClip
        assertTrue("a clip is set", clip != null && clip.itemCount == 1)
        assertEquals(
            "ABCD EFGH IJKL MNOP QRST UVWX",
            clip!!.getItemAt(0).coerceToText(app).toString()
        )
        assertEquals(
            "EXTRA_IS_SENSITIVE keeps it out of the clipboard preview",
            true,
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)
        )
    }

    @Test
    fun `a non-sensitive copy is not marked`() {
        ClipboardPrivacy.copy(app, "Document name", "Annual Report", sensitive = false)
        val extras = clipboard.primaryClip?.description?.extras
        assertFalse(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    @Test
    fun `clearing only removes the clip while it still holds the secret`() {
        ClipboardPrivacy.copy(app, "Recovery code", "SECRET-CODE")
        ClipboardPrivacy.clearIfUnchanged(app, "SECRET-CODE")
        assertTrue("the secret clip is gone", clipboard.primaryClip == null)

        ClipboardPrivacy.copy(app, "Recovery code", "SECRET-CODE")
        ClipboardPrivacy.copy(app, "User copy", "something else", sensitive = false)
        ClipboardPrivacy.clearIfUnchanged(app, "SECRET-CODE")
        assertEquals(
            "the user's own clipboard content is kept",
            "something else",
            clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString()
        )
    }

    @Test
    fun `the clear delay is one minute`() {
        assertEquals(60_000L, ClipboardPrivacy.CLEAR_AFTER_MS)
    }
}
