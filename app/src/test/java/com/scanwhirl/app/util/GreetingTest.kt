package com.scanwhirl.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {

    @Test
    fun `morning pool`() {
        assertTrue(
            Greeting.forHour(8) in setOf(
                "☀️ Good morning", "🌅 Rise and shine", "☕ Morning! Ready to scan?"
            )
        )
    }

    @Test
    fun `afternoon pool`() {
        assertTrue(
            Greeting.forHour(14) in setOf(
                "🌤️ Good afternoon", "📄 Good afternoon", "🖨️ Afternoon! Let's organize"
            )
        )
    }

    @Test
    fun `evening pool`() {
        assertTrue(
            Greeting.forHour(19) in setOf(
                "🌆 Good evening", "🌙 Good evening", "📎 Evening! Time to tidy up"
            )
        )
    }

    @Test
    fun `late night pool`() {
        assertTrue(
            Greeting.forHour(2) in setOf(
                "🌃 Up late?", "🌙 Late-night scan", "✨ Night owl mode"
            )
        )
    }
}
