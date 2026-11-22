package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.V2tResultReceiptStore
import com.scanwhirl.app.store.OpFaults
import com.scanwhirl.app.ui.ExportResultActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
open class RsReviewSafetyTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun settle(activity: androidx.activity.ComponentActivity) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.lifecycleScope.coroutineContext[Job]!!.children.none { it.isActive }) return
            assertTrue("result IO completed", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    @Test(timeout = 30_000)
    fun staleProducerIdentityNeverDeletesReplacement() {
        val id = DocFixtures.saved(repo, app, 1)
        val identity = ToolResultActivity.captureOutputIdentityBlocking(app, listOf(id), emptyList())
        val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(id),
            beforeBytes = 1, compressGrew = true, outputIdentity = identity)
        val pdf = requireNotNull(repo.getDocument(id)).pdfFile
        pdf.appendBytes(byteArrayOf(9, 8, 7))
        val replacement = pdf.readBytes()
        val c = Robolectric.buildActivity(ToolResultActivity::class.java, intent)
        try {
            c.setup()
            settle(c.get())
            assertArrayEquals(replacement, pdf.readBytes())
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun missingProducerIdentityNeverDeletesOutput() {
        val id = DocFixtures.saved(repo, app, 1)
        val c = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, "compress", docIds = listOf(id),
                beforeBytes = 1, compressGrew = true).apply { removeExtra("l3_output_identity") })
        try {
            c.setup()
            settle(c.get())
            assertNotNull(repo.getDocument(id))
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun rejectionIsDurableBeforeAnyPublication() = runBlocking {
        val id = DocFixtures.saved(repo, app, 1)
        // A tiny source PDF makes any valid generated PDF larger, without relying on JPEG noise.
        requireNotNull(repo.getDocument(id)).pdfFile.writeBytes(byteArrayOf(1))
        val beforeIds = repo.docsDir.list()!!.toSet()
        val result = repo.compressScanForResult(id, 100, null)
        assertNull(result)
        assertEquals(beforeIds, repo.docsDir.list()!!.toSet())
        val receipt = requireNotNull(V2tResultReceiptStore(app).load())
        assertEquals("NOT_SMALLER", receipt.outcomeClass)
        assertTrue(receipt.docIds.isEmpty())
        assertTrue(receipt.files.isEmpty())
        assertEquals(id, receipt.receiptSourceId)
    }

    @Test(timeout = 30_000)
    fun deathImmediatelyAfterRejectionCannotPublishLargerCopy() = runBlocking {
        val id = DocFixtures.saved(repo, app, 1)
        requireNotNull(repo.getDocument(id)).pdfFile.writeBytes(byteArrayOf(1))
        val beforeIds = repo.docsDir.list()!!.toSet()
        class Death : Error()
        OpFaults.hook = { if (it == "compress:rejected") throw Death() }
        try {
            try {
                repo.compressScanForResult(id, 100, null)
                fail("expected simulated process death")
            } catch (_: Death) { }
            assertEquals(beforeIds, repo.docsDir.list()!!.toSet())
            val receipt = requireNotNull(V2tResultReceiptStore(app).load())
            assertEquals("NOT_SMALLER", receipt.outcomeClass)
            assertTrue(receipt.docIds.isEmpty())
        } finally { OpFaults.reset() }
    }

    @Test(timeout = 30_000)
    fun explicitImageCannotBypassLockedDocumentPolicy() {
        val id = DocFixtures.locked(repo, app, pages = 1)
        val file = RsFixtures.jpeg("explicit", 100, 200, app)
        val c = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, "stamp", docIds = listOf(id),
                compareOutcome = "Marked", compareAfterImage = file.path))
        try {
            c.setup()
            val view = c.get().findViewById<com.scanwhirl.app.ui.kit.CompareView>(R.id.ccCompare)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(view.displayedBitmap())
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun exportNeverDeletesAliasedSourceAndIgnoresWrongSizeExtras() {
        val file = File(app.cacheDir, "rs_source.jpg").apply { writeBytes(ByteArray(1234)) }
        val c = Robolectric.buildActivity(ExportResultActivity::class.java,
            ExportResultActivity.intent(app, file.path, file.path, "image/jpeg", "Source",
                100_000, 999_999, 10, 10, null, null, beforePreviewPath = file.path))
        try {
            c.setup()
            settle(c.get())
            assertTrue(file.exists())
            val hero = c.get().findViewById<TextView>(R.id.resultStats).text.toString()
            assertTrue(hero, hero.contains(com.scanwhirl.app.util.SizeUnits.format(1234)))
            assertFalse(hero, hero.contains("999"))
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun exportStalePathReplacementSurvivesCreation() {
        val file = File(app.cacheDir, "rs_stale.jpg").apply { writeBytes(ByteArray(2000)) }
        val intent = ExportResultActivity.intent(app, file.path, null, "image/jpeg",
            "UserDocument", 1000, 2000, 10, 10, null, null, toolId = "resize")
        val replacement = ByteArray(3000) { 7 }
        file.writeBytes(replacement)
        val c = Robolectric.buildActivity(ExportResultActivity::class.java, intent)
        try {
            c.setup()
            settle(c.get())
            assertArrayEquals(replacement, file.readBytes())
            val hero = c.get().findViewById<TextView>(R.id.resultStats).text.toString()
            assertTrue(hero, hero.contains(com.scanwhirl.app.util.SizeUnits.format(3000)))
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun doneFinishesRejectedExport() {
        val c = Robolectric.buildActivity(ExportResultActivity::class.java,
            ExportResultActivity.intent(app, "", null, "image/jpeg", "Source",
                100, 200, 10, 10, null, null, outcome = "NOT_SMALLER", toolId = "resize"))
        try {
            c.setup()
            c.get().findViewById<View>(R.id.btnOpenResult).performClick()
            assertTrue(c.get().isFinishing)
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun arbitraryToolCannotOptIntoStackedComparison() {
        val c = Robolectric.buildActivity(ToolResultActivity::class.java,
            ToolResultActivity.intent(app, "duplicate", compareOutcome = "Copied",
                compareBeforeImage = "/not/owned.jpg", compareAfterImage = "/not/owned.jpg"))
        try {
            c.setup()
            assertEquals(View.GONE, c.get().findViewById<View>(R.id.ccCompare).visibility)
        } finally { c.destroy() }
    }

    @Test(timeout = 30_000)
    fun everyResultStateHasAtMostFortyAppWords() {
        val id = DocFixtures.saved(repo, app, 1, "UserDocument")
        for (tool in listOf("compress", "stamp", "protect", "duplicate")) {
            for (state in listOf("smaller", "missed", "rejected")) {
                val rejected = state == "rejected"
                val c = Robolectric.buildActivity(ToolResultActivity::class.java,
                    ToolResultActivity.intent(app, tool, docIds = if (rejected) emptyList() else listOf(id),
                        beforeBytes = 1_000_000, compressGrew = rejected,
                        targetBytes = if (state == "missed") 1L else null,
                        compressTargetMissed = state == "missed", receiptSourceId = id))
                try {
                    c.setup()
                    for (expanded in listOf(false, true)) {
                        if (expanded) c.get().findViewById<View>(R.id.toolResultMore)?.performClick()
                        val words = mutableListOf<String>()
                        fun walk(v: View) {
                            if (v.visibility != View.VISIBLE) return
                            if (v is TextView) words += v.text.toString().split(Regex("\\s+"))
                                .filter { it.any(Char::isLetter) && !it.contains("UserDocument") }
                            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                        }
                        walk(c.get().findViewById(android.R.id.content))
                        assertTrue("$tool/$state/$expanded: $words", words.size <= 40)
                    }
                } finally { c.destroy() }
            }
        }
    }

    @Test(timeout = 30_000)
    fun exportStatesHaveAtMostFortyAppWords() {
        val file = RsFixtures.jpeg("words", 100, 200, app)
        for (rejected in listOf(false, true)) {
            val c = Robolectric.buildActivity(ExportResultActivity::class.java,
                ExportResultActivity.intent(app, if (rejected) "" else file.path, null,
                    "image/jpeg", "UserDocument", 1_000_000, 999_999,
                    100, 200, null, null, toolId = "resize",
                    outcome = if (rejected) "NOT_SMALLER" else null))
            try {
                c.setup()
                for (expanded in listOf(false, true)) {
                    if (expanded && !rejected) c.get().findViewById<View>(R.id.btnMoreResult).performClick()
                    val words = mutableListOf<String>()
                    fun walk(v: View) {
                        if (v.visibility != View.VISIBLE) return
                        if (v is TextView) words += v.text.toString().split(Regex("\\s+"))
                            .filter { it.any(Char::isLetter) && !it.contains("UserDocument") }
                        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                    }
                    walk(c.get().findViewById(android.R.id.content))
                    assertTrue("export/$rejected/$expanded: $words", words.size <= 40)
                }
            } finally { c.destroy() }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class RsReviewSafetyHiTest : RsReviewSafetyTest()
