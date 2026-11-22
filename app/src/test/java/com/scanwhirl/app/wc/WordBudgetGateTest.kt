package com.scanwhirl.app.wc

import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lane WC, enforcing mode: the ratchet for the owner's "at most 40 visible
 * app-authored words per screen at 100% font (EN)" rule.
 *
 * [BASELINE] is the CURRENT measured count per screen (see
 * build/reports/word_budget.md, produced by [WordBudgetReportTest]). A screen
 * passes while it is within max(40, its baseline count); a NEW screen must be
 * <= 40. The lead lowers a baseline entry after the owning lane removes text,
 * so no screen may creep back up and every screen converges on 40.
 *
 * Every screen is measured from a fresh Robolectric fixture (fictional files,
 * English, 100% font, 360x800dp) by the same [WcHarness] the report uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class WordBudgetGateTest {

    private lateinit var harness: WcHarness

    @Before
    fun setUp() {
        harness = WcHarness().also { it.setUp() }
    }

    @After
    fun tearDown() {
        harness.tearDown()
    }

    private fun check(part: Int) {
        val mine = harness.specs().filterIndexed { i, _ -> i % PARTS == part }
        for (spec in mine) {
            val row = harness.measure(spec)
            synchronized(MEASURED) { MEASURED += row.screen }
            if (row.error != null) {
                fail("${row.screen}: could not be measured (${row.error}) - a silently skipped screen is not a pass")
            }
            val baseline = BASELINE[row.screen]
            val limit = if (baseline == null) HARD_LIMIT else maxOf(HARD_LIMIT, baseline)
            assertTrue(
                "${row.screen} [${row.state}]: ${row.words} visible words > $limit " +
                    "(hard limit $HARD_LIMIT, baseline ${baseline ?: "NEW"}). " +
                    "Top strings: ${row.top.joinToString(" | ")}",
                row.words <= limit
            )
        }
    }

    @Test(timeout = 30_000)
    fun gateGroup0() = check(0)

    @Test(timeout = 30_000)
    fun gateGroup1() = check(1)

    @Test(timeout = 30_000)
    fun gateGroup2() = check(2)

    companion object {
        const val HARD_LIMIT = 40
        const val PARTS = 3
        private val MEASURED = HashSet<String>()

        /**
         * Current counts from build/reports/word_budget.md (one number per
         * screen). Lower a value only when that screen's owner lane removes
         * text; never raise one. New screens default to the 40-word limit.
         */
        val BASELINE: Map<String, Int> = mapOf(
            "Backup" to 66,
            "OCR editor" to 62,
            "Pro" to 50,
            "Settings" to 49,
            "Vault (unlocked)" to 44,
            "Result - tool form" to 40,
            "Shell Tools" to 40,
            "Tool setup - unlock" to 40,
            "Form photo (loaded)" to 38,
            "Shell Me" to 38,
            "Tool setup - form_ready" to 38,
            "Shell Library" to 31,
            "Tool setup - merge" to 31,
            "Tool setup - compress" to 26,
            "Scan review" to 25,
            "Document actions sheet" to 24,
            "Result - tool compress" to 22,
            "Tool setup - to_excel" to 22,
            "Tool setup - vault" to 21,
            "Vault (not set up)" to 21,
            "Tool setup - pdf_to_images" to 20,
            "Tool setup - id_card" to 18,
            "Tool setup - add_text" to 17,
            "Tool setup - link_index" to 17,
            "Tool setup - long_image" to 17,
            "Tool setup - page_numbers" to 17,
            "Tool setup - passport" to 17,
            "Tool setup - pdfa" to 17,
            "Tool setup - remove_metadata" to 17,
            "Tool setup - to_powerpoint" to 17,
            "Tool setup - draw" to 16,
            "Tool setup - import_pdf" to 16,
            "Tool setup - insert_pages" to 16,
            "Tool setup - redact" to 16,
            "Tool setup - searchable_pdf" to 16,
            "Tool setup - watermark" to 16,
            "Trash" to 16,
            "Tool setup - folders" to 15,
            "Tool setup - organize_pages" to 15,
            "Tool setup - read_text" to 15,
            "Tool setup - resize" to 15,
            "Tool setup - scan" to 15,
            "Tool setup - split" to 15,
            "Tool setup - extract" to 14,
            "Tool setup - protect" to 14,
            "Tool setup - qr_code" to 14,
            "Document viewer" to 12,
            "Result - export form" to 11,
            "Result - export compress" to 10,
            "Tool setup - zip" to 10,
            "PDF reader" to 7,
            "Stamp editor" to 0,
            "Tool setup - batch_tools" to 0,
            "Tool setup - highlight" to 0,
            "Tool setup - sign" to 0,
            "Tool setup - stamp" to 0,
            "Tool setup - to_word" to 0
        )

        @JvmStatic
        @AfterClass
        fun allBaselineScreensWereMeasured() {
            val missing = BASELINE.keys - MEASURED
            assertTrue(
                "These baselined screens were not measured this run (a removed screen needs a reviewed baseline change): $missing",
                missing.isEmpty()
            )
        }
    }
}
