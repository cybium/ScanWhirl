package com.scanwhirl.app.util

/**
 * The document categories the app can store (N2/N3/N4 in the test26 plan):
 * Study, Receipts, IDs, Forms, Other — plus **Unsorted**, the state of a scan
 * nobody has looked at yet (new scans with no confident match land there
 * instead of pretending to be "Other").
 *
 * [classify] guesses from the document name first (as always), then from OCR
 * text when the name says nothing. A guess is honest: name keywords win
 * outright; OCR must produce **≥ 2 keyword hits with a clear winner** or the
 * answer is [UNSORTED]. The stored values below are English on purpose (they live
 * in `meta.txt`) and never change; every screen shows a translated name through
 * [CategoryLabels.display].
 *
 * `meta.txt` compatibility: line 3 holds the category; only values accepted
 * by [isValid] are ever written, and old documents missing/invalid on line 3
 * still read as [OTHER] (`ScanRepository.loadDocument`).
 */
object CategoryRules {

    const val STUDY = "Study"
    const val RECEIPTS = "Receipts"
    const val IDS = "IDs"
    const val FORMS = "Forms"
    const val OTHER = "Other"

    /** Stored value for "nobody has sorted this yet" (never a display label). */
    const val UNSORTED = "Unsorted"

    /** Picker order (historically also the globe's ring order). */
    val ALL: List<String> = listOf(STUDY, RECEIPTS, IDS, FORMS, OTHER)

    /** Every value that may legally appear on meta.txt line 3. */
    val ALL_WITH_UNSORTED: List<String> = ALL + UNSORTED

    private val RECEIPT_WORDS = Regex(
        """\b(receipts?|invoices?|bills?|billing|payments?|paystubs?|purchases?|orders?|refunds?|shopping)\b""",
        RegexOption.IGNORE_CASE
    )
    private val FORM_WORDS = Regex(
        """\b(forms?|applications?|registrations?|worksheets?|paperwork|application)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ID_WORDS = Regex(
        """\b(ids?|passports?|licen[cs]es?|visas?|cards?|aadhaar|pan|ssn|birth\s*certificates?|identity|membership)\b""",
        RegexOption.IGNORE_CASE
    )
    private val STUDY_WORDS = Regex(
        """\b(stud(y|ies)|notes?|exams?|tests?|assignments?|lectures?|textbooks?|syllabus|revision|chapters?|homework|classes|course|semester|physics|maths?|chemistry|biology)\b""",
        RegexOption.IGNORE_CASE
    )

    /** OCR keyword families; each hit is counted, then the winner must be clear. */
    private val OCR_RECEIPTS = rx("""total|subtotal|gst|invoice|amount|qty|paid""")
    private val OCR_IDS = rx(
        """date\s+of\s+birth|dob|aadhaar|uid|passport|licen[cs]e|valid\s+till|father'?s\s+name"""
    )
    private val OCR_FORMS = rx("""applicant|signature|please\s+fill|declaration|form""")
    private val OCR_STUDY = rx("""chapter|exercise|theorem|question|marks|syllabus|unit""")

    private fun rx(pattern: String) = Regex("""\b(?:$pattern)\b""", RegexOption.IGNORE_CASE)

    /** All six stored values are valid; nothing else may be written. */
    fun isValid(category: String): Boolean = category in ALL_WITH_UNSORTED

    /**
     * The stored value itself, for compatibility and for values this build does not know.
     * User-visible text goes through [CategoryLabels.display] instead.
     */
    fun label(category: String): String = category

    /**
     * Best-effort category from the document name, then from [ocrText] when
     * the name matched nothing; defaults to [UNSORTED] (was [OTHER] — N3:
     * "Other" is now only a deliberate user choice or an old document's
     * fallback).
     */
    fun classify(name: String, ocrText: String? = null): String = when {
        RECEIPT_WORDS.containsMatchIn(name) -> RECEIPTS
        FORM_WORDS.containsMatchIn(name) -> FORMS
        ID_WORDS.containsMatchIn(name) -> IDS
        STUDY_WORDS.containsMatchIn(name) -> STUDY
        else -> classifyOcr(ocrText) ?: UNSORTED
    }

    /**
     * Counts keyword hits per category in OCR text. Returns the category only
     * when it has **≥ 2 hits and strictly more than every rival** (a tie is no
     * winner); otherwise null.
     */
    private fun classifyOcr(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val hits = listOf(
            RECEIPTS to OCR_RECEIPTS.findAll(text).count(),
            IDS to OCR_IDS.findAll(text).count(),
            FORMS to OCR_FORMS.findAll(text).count(),
            STUDY to OCR_STUDY.findAll(text).count()
        )
        val sorted = hits.sortedByDescending { it.second }
        val (winner, count) = sorted[0]
        val runnerUp = sorted[1].second
        return if (count >= 2 && count > runnerUp) winner else null
    }
}
