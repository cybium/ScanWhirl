package com.scanwhirl.app.util

/**
 * One hand-written greeting per 2-hour slot. Deterministic (same slot = same
 * line) and aligned to real day phases: morning from 05:00, night from 20:00.
 */
object Greeting {
    fun forHour(hour: Int): String = when (hour) {
        0, 1 -> "🌃 Up late?"
        2, 3 -> "✨ Night owl mode"
        4, 5 -> "🌅 Rise and shine"
        6, 7 -> "☀️ Good morning"
        8, 9 -> "☕ Morning! Ready to scan?"
        10, 11 -> "📄 Good morning"
        12, 13 -> "🌤️ Good afternoon"
        14, 15 -> "🖨️ Afternoon! Let's organize"
        16, 17 -> "🌇 Good afternoon"
        18, 19 -> "🌆 Good evening"
        20, 21 -> "🌙 Good evening"
        else -> "🌙 Late-night scan"
    }
}
