package com.scanwhirl.app.ux130.l3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3-05 — localized failures and visible long-running PDF/A work.
 *
 * RED on base: no lane failure strings and raw exception text reaches the UI
 * at the four audited sites. GREEN once each site uses one localized cause map
 * and PDF/A owns a progress sheet on every terminal path.
 */
class L3_05_LocalizedFailuresTest {

    @Test
    fun `EN and HI carry the three failure causes with parity`() {
        val en = L3Resources.en()
        val hi = L3Resources.hi()
        assertEquals(
            "lane string names match",
            en.keys.filter { !it.contains('[') }.toSet(),
            hi.keys.filter { !it.contains('[') }.toSet()
        )
        assertEquals("Could not read this PDF. Choose another file.", en["ux130_l3_failure_unreadable"])
        assertEquals("Could not save the copy. Free some space and try again.", en["ux130_l3_failure_no_space"])
        assertEquals("Could not create the copy. Try again.", en["ux130_l3_failure_generic"])
        for (key in listOf("ux130_l3_failure_unreadable", "ux130_l3_failure_no_space", "ux130_l3_failure_generic")) {
            val hiText = hi[key].orEmpty()
            assertTrue("natural Hindi needed for $key: $hiText", hiText.isNotBlank())
            assertFalse("no English loanword in $key", hiText.contains("पीडीएफ़ फ़ाइल") && hiText.contains("इरर"))
        }
        assertTrue("Hindi read failure names another file", hi["ux130_l3_failure_unreadable"].orEmpty().contains("दूसरी फ़ाइल"))
        assertTrue("Hindi space failure asks to free space", hi["ux130_l3_failure_no_space"].orEmpty().contains("जगह"))
        assertTrue("Hindi generic asks to try again", hi["ux130_l3_failure_generic"].orEmpty().contains("प्रयास"))
    }

    @Test
    fun `no audited site interpolates a raw exception message`() {
        for (path in listOf(
            "src/main/java/com/scanwhirl/app/ui/MergePdfActivity.kt",
            "src/main/java/com/scanwhirl/app/ui/tools/BatchActivity.kt",
            "src/main/java/com/scanwhirl/app/ui/tools/PassportActivity.kt",
            "src/main/java/com/scanwhirl/app/tools/PdfAFlow.kt"
        )) {
            val src = java.io.File(path).readText()
            assertFalse("$path still shows raw exception text", src.contains("e.message"))
            assertTrue("$path does not use the localized cause map", src.contains("L3FailureCopy.message"))
        }
    }

    @Test
    fun `PDF A shows progress immediately and dismisses on every terminal path`() {
        val flow = java.io.File("src/main/java/com/scanwhirl/app/tools/PdfAFlow.kt").readText()
        assertTrue("progress must open before work", flow.contains("SwProgress.show"))
        assertTrue("progress must close on success, cancel and error", flow.contains("finally"))
        assertTrue("cancellation still closes progress", flow.contains("CancellationException"))
    }
}
