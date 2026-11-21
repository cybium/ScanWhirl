package com.scanwhirl.app.ui.protect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * K1 defects 2 and 7: the visible password rule must state the real Unicode rule, and a failed
 * page/lock operation must show a localized sentence instead of the raw exception message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockFlowLocalizationTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `no failure toast in LockFlow interpolates a raw exception message`() {
        val source = File("src/main/java/com/scanwhirl/app/tools/LockFlow.kt").readText(Charsets.UTF_8)
        assertFalse(
            "a raw exception message must never be shown to the owner",
            source.contains("e.message")
        )
        assertTrue(
            "the lock failures must resolve a localized sentence",
            source.contains("R.string.lock_op_failed")
        )
    }

    @Test
    fun `the English password rule says Unicode is accepted`() {
        val copy = app.getString(R.string.password_charset)
        assertTrue("emoji must be described as allowed: [$copy]", copy.contains("emoji", ignoreCase = true))
        assertTrue("Hindi must be described as allowed: [$copy]", copy.contains("Hindi", ignoreCase = true))
        assertFalse("the old false claim came back: [$copy]", copy.contains("cannot be used"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi")
    fun `the Hindi password rule says Unicode is accepted`() {
        val copy = app.getString(R.string.password_charset)
        assertTrue("emoji must be described as allowed: [$copy]", copy.contains("इमोजी"))
        assertTrue("Hindi must be described as allowed: [$copy]", copy.contains("हिंदी"))
        assertFalse("the old false claim came back: [$copy]", copy.contains("नहीं चलते"))
    }

    @Test
    fun `the new K1 strings are defined in English and Hindi`() {
        val en = keys(File("src/main/res/values/strings_protect.xml")) +
            keys(File("src/main/res/values/strings.xml"))
        val hi = keys(File("src/main/res/values-hi/strings_protect.xml")) +
            keys(File("src/main/res/values-hi/strings.xml"))
        for (key in listOf("lock_op_failed", "import_unlock_damaged", "password_charset")) {
            assertTrue("$key missing from English", key in en)
            assertTrue("$key missing from Hindi", key in hi)
        }
        val hiText = File("src/main/res/values-hi/strings_protect.xml").readText(Charsets.UTF_8) +
            File("src/main/res/values-hi/strings.xml").readText(Charsets.UTF_8)
        for (banned in listOf("वॉल्ट", "पेज", "सेव", "शेयर", "साइज़", "टूल")) {
            assertFalse("glossary term [$banned] is not allowed in new Hindi copy", hiText.contains(banned))
        }
    }

    private fun keys(file: File): Set<String> =
        Regex("<string\\s+name=\"([^\"]+)\"").findAll(file.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
}
