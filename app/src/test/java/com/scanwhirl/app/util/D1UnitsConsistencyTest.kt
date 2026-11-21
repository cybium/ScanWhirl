package com.scanwhirl.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D1 consistency follow-up (written first, against base 3800db3e, as named failing assertions):
 *  - user-visible sizes use the one decimal [SizeUnits.format] helper (KB = 1000, MB = 1,000,000);
 *  - user-visible dates are formatted with the APP language locale, not the phone locale.
 *
 * The generated-name / exported-timestamp formats are pinned too: those must stay ASCII and stable.
 */
class D1UnitsConsistencyTest {

    private fun source(path: String) = File("src/main/java/com/scanwhirl/app", path).readText()

    @Test
    fun `the Library size label uses the shared decimal formatter and keeps non-breaking spaces`() {
        val src = source("ui/shell/LibraryScreen.kt")
        assertTrue("Library delegates to SizeUnits.format", src.contains("SizeUnits.format"))
        assertFalse("no binary MB divisor", src.contains("bytes / 1048576f"))
        assertFalse("no binary KB divisor", src.contains("bytes / 1024"))
        assertTrue(
            "the number stays with its unit (NBSP), as on the other screens",
            src.contains("replace(' ', '\\u00A0')")
        )
    }

    @Test
    fun `user-visible date sites format with the app language locale`() {
        listOf(
            "ui/shell/LibraryScreen.kt",
            "ui/ScanListAdapter.kt",
            "ui/vault/VaultActivity.kt",
            "ui/qr/QrActivity.kt"
        ).forEach { path ->
            val src = source(path)
            assertTrue("$path resolves the app locale", src.contains("AppLocale.of("))
            assertFalse(
                "$path still formats a user-visible date with the phone locale",
                src.contains("SimpleDateFormat(\"d MMM\", Locale.getDefault())") ||
                    src.contains("SimpleDateFormat(\"d MMM, HH:mm\", Locale.getDefault())")
            )
        }
    }

    @Test
    fun `output names and metadata timestamps keep their stable ASCII formats`() {
        assertTrue("backup ZIP stamp stays Locale.US", source("store/backup/FullBackup.kt").contains("Locale.US"))
        assertTrue("PDF metadata stays Locale.ROOT", source("util/PdfA.kt").contains("Locale.ROOT"))
        assertTrue(
            "Vault backup filename stamp stays Locale.US",
            source("ui/vault/VaultActivity.kt").contains("SimpleDateFormat(\"yyyyMMdd\", Locale.US)")
        )
    }
}
