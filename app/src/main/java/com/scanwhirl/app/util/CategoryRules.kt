package com.scanwhirl.app.util

/**
 * The five document categories that drive the globe home's rings
 * (Study, Receipts, IDs, Forms, Other).
 *
 * [classify] is a keyword guess applied when a scan is created; the user can
 * override it any time from the editor's "Categorize" menu. Guessing is
 * honest: it only looks at the document name, never at page content.
 */
object CategoryRules {

    const val STUDY = "Study"
    const val RECEIPTS = "Receipts"
    const val IDS = "IDs"
    const val FORMS = "Forms"
    const val OTHER = "Other"

    /** Ring order: innermost ring first. */
    val ALL: List<String> = listOf(STUDY, RECEIPTS, IDS, FORMS, OTHER)

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

    fun isValid(category: String): Boolean = category in ALL

    /** Best-effort category from the document name; defaults to [OTHER]. */
    fun classify(name: String): String = when {
        RECEIPT_WORDS.containsMatchIn(name) -> RECEIPTS
        FORM_WORDS.containsMatchIn(name) -> FORMS
        ID_WORDS.containsMatchIn(name) -> IDS
        STUDY_WORDS.containsMatchIn(name) -> STUDY
        else -> OTHER
    }
}
