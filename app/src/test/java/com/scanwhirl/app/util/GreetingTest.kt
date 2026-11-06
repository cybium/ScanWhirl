package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {

    @Test
    fun `greetings follow real day phases`() {
        // Late night starts at 20:00 and runs to 04:59.
        assertEquals("🌙 Good evening", Greeting.forHour(20))
        assertEquals("🌙 Late-night scan", Greeting.forHour(22))
        assertEquals("🌙 Late-night scan", Greeting.forHour(23))
        assertEquals("🌙 Up late?", Greeting.forHour(1))
        assertEquals("✨ Night owl mode", Greeting.forHour(2))

        // Morning starts at 05:00.
        assertEquals("🌅 Rise and shine", Greeting.forHour(5))
        assertEquals("☀️ Good morning", Greeting.forHour(7))
        assertEquals("☕ Morning! Ready to scan?", Greeting.forHour(9))
        assertEquals("⚡ Good morning", Greeting.forHour(11))

        // Afternoon 12:00-16:59.
        assertEquals("☀️ Good afternoon", Greeting.forHour(12))
        assertEquals("🚀 Afternoon! Let's organize", Greeting.forHour(14))
        assertEquals("🌤️ Good afternoon", Greeting.forHour(16))

        // Evening 18:00-19:59.
        assertEquals("🌆 Good evening", Greeting.forHour(18))
    }

    @Test
    fun `10 51 pm is night, not evening tidy-up`() {
        assertEquals("🌙 Late-night scan", Greeting.forHour(22))
    }

    @Test
    fun `greetings start with a clean emoji`() {
        for (hour in 0..23) {
            val text = Greeting.forHour(hour)
            assertTrue("greeting should lead with an emoji: $text", text.isNotEmpty() && text.first().code > 0x2500)
        }
    }
}
