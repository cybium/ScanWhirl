package com.scanwhirl.app.ux130.v2r

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V2R R2: the two owned literal surfaces must be semantic tokens before any Paper work starts. */
class R2ThemeTokenTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/scanwhirl/app/ui/$relative")
        assertTrue("missing $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `owned surfaces use colour tokens instead of literal ARGB`() {
        for (relative in listOf("kit/SwSheet.kt", "shell/ProScreen.kt")) {
            val text = source(relative)
            val literals = Regex("0xFF[0-9A-Fa-f]{6,8}").findAll(text).map { it.value }.toList()
            assertFalse("$relative still paints a literal surface: $literals", literals.isNotEmpty())
        }
        assertTrue(
            "the sheet uses rv_surface",
            source("kit/SwSheet.kt").contains("R.color.rv_surface")
        )
        assertTrue(
            "the pinned offer uses rv_bg",
            source("shell/ProScreen.kt").contains("R.color.rv_bg")
        )
    }
}
