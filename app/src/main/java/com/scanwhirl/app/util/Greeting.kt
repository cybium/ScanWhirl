package com.scanwhirl.app.util

/**
 * One hand-written greeting per 2-hour slot. Deterministic: the same hour
 * always shows the same line (no random dialogue changes within a phase).
 */
object Greeting {
    fun forHour(hour: Int): String = when (hour) {
        0, 1 -> "🌙 Late-night scan"
        2, 3 -> "🌃 Up late?"
        4, 5 -> "✨ Night owl mode"
        6, 7 -> "🌅 Rise and shine"
        8, 9 -> "☕ Morning! Ready to scan?"
        10, 11 -> "☀️ Good morning"
        12, 13 -> "📄 Good afternoon"
        14, 15 -> "🌤️ Good afternoon"
        16, 17 -> "🖨️ Afternoon! Let's organize"
        18, 19 -> "🌆 Good evening"
        20, 21 -> "🌙 Good evening"
        else -> "📎 Evening! Time to tidy up"
    }
}
