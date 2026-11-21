package com.scanwhirl.app.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lane P1 defect 5: PageRanges.parse used to throw IllegalArgumentException
 * with hard-coded English sentences that ToolSetupActivity printed verbatim in
 * the Hindi UI. The error must now carry a @StringRes and format arguments, and
 * every display site must render it with getString.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class P1PageRangesTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun invalid(input: String, count: Int): Throwable =
        runCatching { PageRanges.parse(input, count) }.exceptionOrNull()
            ?: throw AssertionError("bad page range must be rejected: '$input'")

    /** Reflection by field name keeps this test compiling against the unfixed base. */
    private fun messageRes(error: Throwable): Int {
        val field = error.javaClass.getDeclaredField("messageRes")
        field.isAccessible = true
        return field.getInt(error)
    }

    @Test fun `a bad page range carries a localized string resource and no English prose`() {
        val error = invalid("oops", 5)
        val name = app.resources.getResourceEntryName(messageRes(error))

        assertTrue("must point at a new localized string, was $name", name.startsWith("p1_page_range_"))
        val en = File("src/main/res/values/strings_p1.xml").readText()
        val hi = File("src/main/res/values-hi/strings_p1.xml").readText()
        assertTrue("English entry missing: $name", en.contains("name=\"$name\""))
        assertTrue("Hindi entry missing: $name", hi.contains("name=\"$name\""))
        assertFalse("raw English prose leaked: ${error.message}", (error.message ?: "").contains("Can't read"))
    }

    @Test fun `every rejected page range is typed`() {
        listOf("oops" to 5, "" to 5, "9" to 5, "0" to 5, "3-1" to 5).forEach { (input, count) ->
            val name = app.resources.getResourceEntryName(messageRes(invalid(input, count)))
            assertTrue("'$input' must use a localized string, was $name", name.startsWith("p1_page_range_"))
        }
    }

    @Test fun `the setup screen never prints raw exception messages`() {
        val src = File("src/main/java/com/scanwhirl/app/ui/tools/ToolSetupActivity.kt").readText()
        assertFalse("range preview printed raw Java text", src.contains("preview.text = e.message"))
        assertFalse("range failure printed raw Java text", src.contains("fail(e.message"))
        assertTrue("typed range errors must be rendered with getString", src.contains("getString(e.messageRes"))
        assertTrue("tool failures must map through a localized reason",
            src.contains("getString(R.string.tools_setup_failed, setupFailureReason(e))"))
        assertEquals("only one raw-message fallback may remain",
            0, Regex("getString\\(R\\.string\\.tools_setup_failed, e\\.message").findAll(src).count())
    }
}
