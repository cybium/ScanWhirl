package com.scanwhirl.app.ui.tools.linkindex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** LX: the trial copy is PRO_PLAN item 3 verbatim, with no literal "{Play price}" token. */
class LinkIndexStringsTest {

    private fun text(dir: String): String =
        File("src/main/res/$dir/strings_lx.xml").readText(Charsets.UTF_8)

    private fun value(dir: String, key: String): String {
        val xml = text(dir)
        val match = Regex("<string\\s+name=\"$key\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml) ?: error("$key is missing from $dir/strings_lx.xml")
        return match.groupValues[1].trim().replace("\\'", "'")
    }

    @Test
    fun `no visible Link index string contains a brace`() {
        for (dir in listOf("values", "values-hi")) {
            val values = Regex("<string[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                .findAll(text(dir)).map { it.groupValues[1] }.toList()
            assertTrue("$dir has strings", values.isNotEmpty())
            val braces = values.filter { it.contains('{') || it.contains('}') }
            assertTrue("literal template token left in $dir: $braces", braces.isEmpty())
        }
    }

    @Test
    fun `the English trial copy is word for word from PRO_PLAN item 3`() {
        assertEquals("Pro · 1 free use", value("values", "lx_pro_badge"))
        assertEquals("Try free", value("values", "lx_try_free"))
        assertEquals("Not now", value("values", "lx_not_now"))
        assertEquals(
            "Your first use includes a full-quality save, free and without a watermark. " +
                "Further uses of this tool need Pro (%1\$s, one payment). No automatic charge.",
            value("values", "lx_trial_msg")
        )
        assertTrue(value("values", "lx_trial_msg").contains("%1\$s"))
    }

    @Test
    fun `the no-price copy omits the price slot entirely`() {
        val en = value("values", "lx_trial_msg_no_price")
        val hi = value("values-hi", "lx_trial_msg_no_price")
        assertTrue(en.contains("Further uses of this tool need Pro (one payment)."))
        assertFalse("English price slot leaked", en.contains('%'))
        assertFalse("Hindi price slot leaked", hi.contains('%'))
        assertTrue("Hindi pair exists", hi.isNotBlank())
        assertTrue("Hindi price message has a slot", value("values-hi", "lx_trial_msg").contains("%1\$s"))
    }

    @Test
    fun `copy diet rows for LX are applied`() {
        assertEquals("Select contents", value("values", "lx_choose_pages"))
        assertEquals("Match title", value("values", "lx_calibrate"))
        assertEquals("Match a title to its PDF page", value("values", "lx_calibrate_hint"))
        assertEquals("Confirm suggested links", value("values", "lx_suggestions"))
        assertEquals("Create copy", value("values", "lx_create"))
        assertEquals("%1\$d links · %2\$d bookmarks", value("values", "lx_result"))
        assertEquals("Original kept", value("values", "lx_original_kept"))
        assertEquals("Read this page's text first", value("values", "lx_no_geometry"))
        assertEquals("Try once free", value("values", "lx_trial_title"))
        assertTrue("selected count is a plural", text("values").contains("<plurals name=\"lx_pages_selected\""))
        assertTrue("skipped count is a plural", text("values").contains("<plurals name=\"lx_skipped\""))
    }

    @Test
    fun `formatting the price produces no brace`() {
        val formatted = String.format(
            java.util.Locale.ROOT,
            value("values", "lx_trial_msg"),
            "₹299"
        )
        assertTrue(formatted.contains("₹299"))
        assertFalse(formatted.contains("{"))
        assertTrue(formatted.contains("No automatic charge."))
    }
}
