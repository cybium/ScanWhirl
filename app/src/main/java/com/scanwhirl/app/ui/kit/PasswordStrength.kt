package com.scanwhirl.app.ui.kit

/**
 * v2 UI: 05_protect_pdf — the 4-segment strength meter.
 *
 * Deliberately simple and honest: length first, then variety. A 6-character
 * password is the minimum for "Protect PDF"; under 10 it stays Weak (design
 * §4.2), and a long password with symbols reaches 4 segments.
 */
object PasswordStrength {

    const val MIN_LENGTH = 6
    const val WEAK_BELOW = 10

    /** 0..4, matching the four meter segments. */
    fun score(password: String): Int {
        if (password.length < MIN_LENGTH) return 0
        var score = 1
        if (password.length >= WEAK_BELOW) score++
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        if (hasLetter && hasDigit) score++
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (password.length >= 14 || hasSymbol) score++
        return score.coerceAtMost(4)
    }

    /** True for "Weak" copy under 10 characters (design §4.2). */
    fun isWeak(password: String): Boolean = password.isNotEmpty() && password.length < WEAK_BELOW
}
