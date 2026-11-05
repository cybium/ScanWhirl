package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun `each two hour slot is deterministic`() {
        assertEquals("🌙 Late-night scan", Greeting.forHour(0))
        assertEquals("🌃 Up late?", Greeting.forHour(3))
        assertEquals("✨ Night owl mode", Greeting.forHour(5))
        assertEquals("🌅 Rise and shine", Greeting.forHour(7))
        assertEquals("☕ Morning! Ready to scan?", Greeting.forHour(9))
        assertEquals("☀️ Good morning", Greeting.forHour(11))
        assertEquals("📄 Good afternoon", Greeting.forHour(12))
        assertEquals("🌤️ Good afternoon", Greeting.forHour(15))
        assertEquals("🖨️ Afternoon! Let's organize", Greeting.forHour(17))
        assertEquals("🌆 Good evening", Greeting.forHour(19))
        assertEquals("🌙 Good evening", Greeting.forHour(21))
        assertEquals("📎 Evening! Time to tidy up", Greeting.forHour(23))
    }

    @Test
    fun `same slot always returns the same text`() {
        assertEquals(Greeting.forHour(8), Greeting.forHour(8))
        assertEquals(Greeting.forHour(14), Greeting.forHour(14))
    }
}
