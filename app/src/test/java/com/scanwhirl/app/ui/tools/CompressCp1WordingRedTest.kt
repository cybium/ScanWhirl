package com.scanwhirl.app.ui.tools

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.StatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CP1 RED set, written before the implementation. These assertions use only strings and APIs that
 * already exist on the base SHA, so a RED run gives named failing assertions instead of a compile
 * error. They pin the honest-copy decisions for Compress (defects 2, 8, 10 and lead decision (a)).
 */
class CompressCp1DecimalRedTest {

    @Test
    fun `displayed sizes are decimal KB and MB`() {
        assertEquals("500 B", StatusLine.formatBytes(500L))
        assertEquals("1 KB", StatusLine.formatBytes(1_000L))
        assertEquals("500 KB", StatusLine.formatBytes(500_000L))
        // A 512,000-byte file is 512 KB here and also fits a 512 KiB portal limit.
        assertEquals("512 KB", StatusLine.formatBytes(512_000L))
        assertEquals("1.0 MB", StatusLine.formatBytes(1_000_000L))
        assertEquals("1.5 MB", StatusLine.formatBytes(1_500_000L))
    }
}

/** English copy: the grown warning is generic, the hint tells the truth, estimates say "about". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xhdpi")
class CompressCp1EnglishWordingRedTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the grown result no longer blames text vector PDFs`() {
        val warning = app.getString(R.string.compress_grew_warning)
        assertTrue(
            "no text/vector claim for every level compress: $warning",
            !warning.contains("text/vector", ignoreCase = true) &&
                !warning.contains("vector", ignoreCase = true)
        )
        assertTrue(
            "the copy says keep the original: $warning",
            warning.contains("larger than the original", ignoreCase = true) &&
                warning.contains("Keep the original", ignoreCase = true)
        )
    }

    @Test
    fun `the target hint says a bare number means KB`() {
        assertTrue(
            "the helper text tells the truth about bare numbers",
            app.getString(R.string.compress_target_help).contains("Numbers alone mean KB")
        )
        assertTrue(
            "the field hint agrees",
            app.getString(R.string.resize_target_hint).contains("Numbers alone mean KB")
        )
    }

    @Test
    fun `the estimate is labelled about`() {
        assertTrue(
            "an estimate must not sound measured",
            app.getString(R.string.compress_est_item, "Balanced", "200 KB").contains(" about ")
        )
    }
}

/** Hindi copy: same three promises in everyday Hindi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressCp1HindiWordingRedTest {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the Hindi grown copy is honest too`() {
        val warning = app.getString(R.string.compress_grew_warning)
        assertTrue("no text/vector claim in Hindi: $warning", !warning.contains("लकीर", ignoreCase = true))
        assertTrue("the original is named: $warning", warning.contains("मूल"))
    }

    @Test
    fun `the Hindi hint says a bare number means KB`() {
        assertTrue(
            "the Hindi helper tells the truth about bare numbers",
            app.getString(R.string.compress_target_help).contains("अकेले अंक")
        )
        assertTrue(
            "the Hindi field hint agrees",
            app.getString(R.string.resize_target_hint).contains("अकेले अंक")
        )
    }

    @Test
    fun `the Hindi estimate is labelled about`() {
        assertTrue(
            "लगभग marks an estimate",
            app.getString(R.string.compress_est_item, "बीच का", "200 KB").contains("लगभग")
        )
    }
}
