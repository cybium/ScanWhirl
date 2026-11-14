package com.scanwhirl.app.ui.protect

/**
 * The password rules of the Protect PDF sheet (`05_protect_pdf`): a minimum of 6 characters and a
 * "Weak" warning below 10. The meter has four segments; a 12-character password shows three, which
 * is what the mockup draws.
 *
 * The PDF itself accepts any Unicode; this is advice, not a filter — [isValid] is the only hard
 * rule and matches `PdfProtection.isEncodable` plus the minimum length.
 */
enum class PasswordStrength {
    EMPTY, TOO_SHORT, WEAK, GOOD, STRONG;

    val filledSegments: Int
        get() = when (this) {
            EMPTY -> 0
            TOO_SHORT -> 1
            WEAK -> 2
            GOOD -> 3
            STRONG -> 4
        }

    /** True below the minimum length: the sheet must not offer Protect yet. */
    val isValid: Boolean
        get() = this != EMPTY && this != TOO_SHORT

    /** The line under the meter is a warning, not praise. */
    val isWarning: Boolean
        get() = this == TOO_SHORT || this == WEAK
}

object ProtectPassword {

    const val MIN_LENGTH = 6
    const val WEAK_BELOW = 10
    const val GOOD_BELOW = 14

    /** Counts user-perceived characters, so one emoji is one character, not two UTF-16 units. */
    fun length(password: String): Int = password.codePointCount(0, password.length)

    fun of(password: String): PasswordStrength {
        val length = length(password)
        return when {
            length == 0 -> PasswordStrength.EMPTY
            length < MIN_LENGTH -> PasswordStrength.TOO_SHORT
            length < WEAK_BELOW -> PasswordStrength.WEAK
            length < GOOD_BELOW -> PasswordStrength.GOOD
            else -> PasswordStrength.STRONG
        }
    }

    /** True when the Protect button may act: long enough and well-formed text. */
    fun accepts(password: String): Boolean =
        of(password).isValid && com.scanwhirl.app.util.PdfProtection.isEncodable(password)

    /** Typing it again must match exactly (the mockup's second field shows a check when it does). */
    fun matches(password: String, confirmation: String): Boolean =
        confirmation.isNotEmpty() && password == confirmation
}

/**
 * Wrong-password pacing for the Unlock sheet (`09_unlock_sheet`): five wrong tries, then a pause
 * that starts at 30 seconds and doubles each time it is reached again. A success resets everything.
 *
 * The clock is injected so tests can move time without sleeping.
 */
class TryCooldown(
    private val maxTries: Int = 5,
    private val firstPauseMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    var triesLeft: Int = maxTries
        private set

    private var pauseUntil: Long = 0L
    private var pauseMs: Long = firstPauseMs

    /** False while the sheet's Open button must stay disabled. */
    fun canTry(now: Long = clock()): Boolean = now >= pauseUntil

    fun remainingPauseMs(now: Long = clock()): Long = (pauseUntil - now).coerceAtLeast(0L)

    /**
     * Records one wrong password. Returns the pause that just started in milliseconds, or 0 when
     * the user may try again straight away.
     */
    fun registerFailure(now: Long = clock()): Long {
        triesLeft -= 1
        if (triesLeft > 0) return 0L
        val pause = pauseMs
        pauseUntil = now + pause
        pauseMs *= 2
        triesLeft = maxTries
        return pause
    }

    /** A right password. */
    fun reset() {
        triesLeft = maxTries
        pauseUntil = 0L
        pauseMs = firstPauseMs
    }
}
