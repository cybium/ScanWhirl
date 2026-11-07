package com.scanwhirl.app.util

/**
 * Visual styling tokens and metadata for document categories.
 * Pure Kotlin — no Android dependencies so it can be tested in pure JVM unit tests.
 */
object CategoryStyle {

    const val COLOR_STUDY: Int = 0xFF38EF7D.toInt()
    const val COLOR_RECEIPTS: Int = 0xFFFF8A4C.toInt()
    const val COLOR_IDS: Int = 0xFF5B9DFF.toInt()
    const val COLOR_FORMS: Int = 0xFFB07CFF.toInt()
    const val COLOR_OTHER: Int = 0xFF94A3B8.toInt()

    fun color(category: String): Int = when (category) {
        CategoryRules.STUDY -> COLOR_STUDY
        CategoryRules.RECEIPTS -> COLOR_RECEIPTS
        CategoryRules.IDS -> COLOR_IDS
        CategoryRules.FORMS -> COLOR_FORMS
        else -> COLOR_OTHER
    }

    fun shortTag(category: String): String = when (category) {
        CategoryRules.STUDY -> "STUDY"
        CategoryRules.RECEIPTS -> "RECEIPT"
        CategoryRules.IDS -> "ID"
        CategoryRules.FORMS -> "FORM"
        else -> "OTHER"
    }

    fun ringIndex(category: String): Int = when (category) {
        CategoryRules.STUDY -> 0
        CategoryRules.RECEIPTS -> 1
        CategoryRules.IDS -> 2
        CategoryRules.FORMS -> 3
        else -> 4
    }

    fun colorForRing(ringIndex: Int): Int = when (ringIndex) {
        0 -> COLOR_STUDY
        1 -> COLOR_RECEIPTS
        2 -> COLOR_IDS
        3 -> COLOR_FORMS
        else -> COLOR_OTHER
    }

    fun tagForRing(ringIndex: Int): String = when (ringIndex) {
        0 -> "STUDY"
        1 -> "RECEIPT"
        2 -> "ID"
        3 -> "FORM"
        else -> "OTHER"
    }
}
