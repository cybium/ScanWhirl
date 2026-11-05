package com.scanwhirl.app.util

/** Short, hand-written greetings (not machine-translated) by time of day. */
object Greeting {
    fun forHour(hour: Int): String {
        val pool = when (hour) {
            in 5..11 -> listOf(
                "☀️ Good morning",
                "🌅 Rise and shine",
                "☕ Morning! Ready to scan?"
            )
            in 12..16 -> listOf(
                "🌤️ Good afternoon",
                "📄 Good afternoon",
                "🖨️ Afternoon! Let's organize"
            )
            in 17..20 -> listOf(
                "🌆 Good evening",
                "🌙 Good evening",
                "📎 Evening! Time to tidy up"
            )
            else -> listOf(
                "🌃 Up late?",
                "🌙 Late-night scan",
                "✨ Night owl mode"
            )
        }
        return pool.random()
    }
}
