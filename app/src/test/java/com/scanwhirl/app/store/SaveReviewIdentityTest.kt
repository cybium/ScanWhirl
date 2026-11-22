package com.scanwhirl.app.store
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * WP-05 recovery repairs R1 (replay must not accept a changed review) and R2 (the
 * reaper must never erase complete, recoverable document data).
 *
 * Same honesty rules as [SaveTransactionTest]: these are deterministic JVM simulations
 * of process death (an Error that skips cleanup). They do not prove real process kill,
 * `fsync` or power-loss durability on a device; Android directory fsync stays best effort.
 *
 * Every fixture is a real JPEG and every PDF is a real generated PDF.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SaveReviewIdentityTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }
    private val dayMs = 24L * 60 * 60 * 1000

    @Before @After fun reset() {
        SaveFaults.reset()
        Durable.resetForTests()
        ScanRepository.resetTestSeams()
    }

    // ---------------------------------------------------------------- helpers

    private class Staged(val dir: File, val files: List<File>) {
        val uris get() = files.map { Uri.fromFile(it) }
    }

    private var counter = 0

    private fun stage(count: Int): Staged {
        val dir = File(File(app.filesDir, "pending_scans"), "pending_${System.nanoTime()}_${counter++}").apply { mkdirs() }
        val files = (0 until count).map { i ->
            File(dir, "page_%03d.jpg".format(i)).apply { writeBytes(ImportFixtures.image(width = 120 + i)) }
        }
        return Staged(dir, files)
    }

    private fun save(
        s: Staged,
        name: String = "Doc",
        filters: Map<Int, ScanFilter>? = null
    ): ScanDocument = runBlocking { repo.saveScan(s.uris, null, name, pageFilters = filters, reviewDir = s.dir) }

    private fun crashAt(step: String) {
        SaveFaults.hook = { if (it == step) throw SimulatedProcessDeath(it) }
    }

    private fun assertCrashes(block: () -> Unit) {
        try { block(); fail("expected a simulated process death") } catch (_: SimulatedProcessDeath) { }
    }

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Sha256.of(it) }

    private fun docDirs() = docsDir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

    private fun journal(s: Staged) = SaveFormat.loadJournal(s.dir)!!

    /** A review whose save published, then the process died before the staging was acknowledged. */
    private fun committedUnacknowledged(pages: Int = 3, filters: Map<Int, ScanFilter>? = null): Pair<Staged, ScanDocument> {
        val s = stage(pages)
        crashAt("after_publish")
        assertCrashes { save(s, filters = filters) }
        SaveFaults.reset()
        return s to repo.listDocuments().single()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out")
    }

    /** What a resumed edit looks like to saveScan: the replay result or the refusal. */
    private fun replay(s: Staged, filters: Map<Int, ScanFilter>? = null): Result<ScanDocument> =
        runCatching { save(s, filters = filters) }

    // ------------------------------------------------------------------
    // R1 reproductions — written against the pre-repair API so they can be run
    // on fa4c298 (must FAIL there) and on the repaired tree (must PASS).
    // ------------------------------------------------------------------

    @Test fun `R1 modified page content is never reported as already saved`() {
        val (s, first) = committedUnacknowledged(3)
        val committedBefore = snapshot(first.pdfFile.parentFile!!)
        // Same page count, same names, different bytes: an edit made after the crash.
        s.files[1].writeBytes(ImportFixtures.image(width = 301))

        val result = replay(s)
        assertTrue(
            "a changed review must not come back as the already-saved document",
            result.isFailure || result.getOrNull()!!.id != first.id
        )
        assertEquals("the earlier committed document is preserved byte-for-byte", committedBefore, snapshot(first.pdfFile.parentFile!!))
        assertNull("and it is not offered as the saved copy of the changed review", repo.committedDocumentFor(s.dir))
    }

    @Test fun `R1 reordered pages are never reported as already saved`() {
        val (s, first) = committedUnacknowledged(3)
        val committedBefore = snapshot(first.pdfFile.parentFile!!)
        val a = s.files[0].readBytes()
        val c = s.files[2].readBytes()
        s.files[0].writeBytes(c)
        s.files[2].writeBytes(a)

        val result = replay(s)
        assertTrue(result.isFailure || result.getOrNull()!!.id != first.id)
        assertEquals(committedBefore, snapshot(first.pdfFile.parentFile!!))
        assertNull(repo.committedDocumentFor(s.dir))
    }

    @Test fun `R1 changed filter state is never reported as already saved`() {
        val (s, first) = committedUnacknowledged(2)
        val committedBefore = snapshot(first.pdfFile.parentFile!!)

        val result = replay(s, filters = mapOf(0 to ScanFilter.BLACKWHITE, 1 to ScanFilter.ORIGINAL))
        assertTrue(result.isFailure || result.getOrNull()!!.id != first.id)
        assertEquals(committedBefore, snapshot(first.pdfFile.parentFile!!))
    }

    // ------------------------------------------------------------------
    // R2 reproduction
    // ------------------------------------------------------------------

    @Test fun `R2 a complete prepared document survives the reaper at 25 hours and replays to exactly one document`() {
        val s = stage(3)
        crashAt("before_publish") // pages, verified PDF, txn.txt, meta.txt all present; marker still there
        assertCrashes { save(s) }
        SaveFaults.reset()
        val hidden = docDirs().single()
        val dir = File(docsDir, hidden)
        val before = snapshot(dir)
        val started = SaveFormat.ownerOf(dir)!!.startedAt

        runBlocking { repo.reapAbandonedSaves(now = started + 25 * 60 * 60 * 1000L) }

        assertTrue("complete prepared output must not be erased because its marker is old", dir.isDirectory)
        assertEquals("and must be untouched", before, snapshot(dir))

        val doc = save(s)
        assertEquals(hidden, doc.id)
        assertEquals(listOf(hidden), docDirs())
        assertEquals(1, repo.listDocuments().size)
    }

    // ------------------------------------------------------------------
    // R1 — behaviour after the repair
    // ------------------------------------------------------------------

    private val bwFirst = mapOf(0 to ScanFilter.BLACKWHITE, 1 to ScanFilter.ORIGINAL)

    private fun assertConflict(s: Staged, reason: SavedReviewConflictException.Reason, docId: String,
                               filters: Map<Int, ScanFilter>? = null) {
        val journalBefore = journal(s).let { it.token to it.target }
        val dirsBefore = docDirs()
        val stagingBefore = snapshot(s.dir)
        try {
            save(s, filters = filters)
            fail("a changed review must be refused explicitly")
        } catch (e: SavedReviewConflictException) {
            assertEquals(docId, e.documentId)
            assertEquals(reason, e.reason)
        }
        assertEquals("refusal changes no identity", journalBefore, journal(s).let { it.token to it.target })
        assertEquals("refusal creates and deletes nothing", dirsBefore, docDirs())
        assertEquals("refusal never touches the review staging", stagingBefore, snapshot(s.dir))
    }

    @Test fun `R1 an unchanged interrupted review replays idempotently to exactly one document`() {
        val (s, first) = committedUnacknowledged(3, filters = bwFirst + (2 to ScanFilter.ORIGINAL))
        val dirBefore = snapshot(first.pdfFile.parentFile!!)
        val sameFilters = bwFirst + (2 to ScanFilter.ORIGINAL)

        assertEquals(first.id, replay(s, sameFilters).getOrThrow().id)
        assertEquals(first.id, replay(s, sameFilters).getOrThrow().id)
        assertEquals(listOf(first.id), docDirs())
        // Replay regenerates nothing and rewrites nothing; the only addition is the post-commit
        // OCR queue marker the crashed save never reached.
        assertEquals(dirBefore, snapshot(first.pdfFile.parentFile!!).filterKeys { it != "ocr_pending" })
        val txn = SaveFormat.readTxn(first.pdfFile.parentFile!!)!!
        assertNotNull("the identity is durable in the provenance record", txn.sourceSha256)
        assertNotNull(txn.filtersSha256)
    }

    @Test fun `R1 changed content is refused explicitly and the committed document is preserved`() {
        val (s, first) = committedUnacknowledged(3)
        val before = snapshot(first.pdfFile.parentFile!!)
        s.files[1].writeBytes(ImportFixtures.image(width = 301))
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id)
        assertEquals(before, snapshot(first.pdfFile.parentFile!!))
    }

    @Test fun `R1 reordering is refused explicitly`() {
        val (s, first) = committedUnacknowledged(3)
        val a = s.files[0].readBytes(); val c = s.files[2].readBytes()
        s.files[0].writeBytes(c); s.files[2].writeBytes(a)
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id)
    }

    @Test fun `R1 a replaced page with identical size but different bytes is refused`() {
        val (s, first) = committedUnacknowledged(2)
        val original = s.files[0].readBytes()
        val other = ImportFixtures.image(width = 120, height = 81) // different pixels, same page count/name
        assertNotEquals(original.toList(), other.toList())
        s.files[0].writeBytes(other)
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id)
    }

    @Test fun `R1 changed per-page or global filters are refused explicitly`() {
        val (s, first) = committedUnacknowledged(2, filters = bwFirst)
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id, filters = null)
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id,
            filters = mapOf(0 to ScanFilter.ENHANCE, 1 to ScanFilter.ORIGINAL))
        // Restoring exactly the saved state makes it a plain replay again.
        assertEquals(first.id, replay(s, bwFirst).getOrThrow().id)
    }

    @Test fun `R1 SAVE_AS_NEW is an explicit choice with a new persisted identity and the earlier document untouched`() {
        val (s, first) = committedUnacknowledged(3)
        val firstBefore = snapshot(first.pdfFile.parentFile!!)
        val oldJournal = journal(s).let { it.token to it.target }
        s.files[1].writeBytes(ImportFixtures.image(width = 301))

        val second = runBlocking {
            repo.saveScan(s.uris, null, "Edited", reviewDir = s.dir, ifSavedCopyDiffers = SavedCopyPolicy.SAVE_AS_NEW)
        }
        assertNotEquals(first.id, second.id)
        assertEquals(setOf(first.id, second.id), docDirs().toSet())
        assertEquals("earlier document byte-identical", firstBefore, snapshot(first.pdfFile.parentFile!!))
        assertEquals("new content is in the new document", 301, ImageTools.imageSize(second.pageFiles[1].readBytes())!!.first)
        PdfVerifier.verify(second.pdfFile, expectedImages = second.pageFiles)
        val newJournal = journal(s)
        assertNotEquals("new token", oldJournal.first, newJournal.token)
        assertEquals("journal points at the new document", second.id, newJournal.target)
        assertEquals(newJournal.token, SaveFormat.readTxn(second.pdfFile.parentFile!!)!!.token)

        // The new identity is itself replay safe: unchanged review → same new document, no third.
        assertEquals(second.id, replay(s).getOrThrow().id)
        assertEquals(2, docDirs().size)
        assertEquals(second.id, repo.committedDocumentFor(s.dir)?.id)
    }

    @Test fun `R1 crash while building the explicit new document leaves the earlier one intact and replays safely`() {
        val (s, first) = committedUnacknowledged(2)
        val firstBefore = snapshot(first.pdfFile.parentFile!!)
        s.files[0].writeBytes(ImportFixtures.image(width = 222))

        for (step in listOf("page:0", "before_publish", "after_publish")) {
            crashAt(step)
            assertCrashes {
                runBlocking {
                    repo.saveScan(s.uris, null, "Edited", reviewDir = s.dir, ifSavedCopyDiffers = SavedCopyPolicy.SAVE_AS_NEW)
                }
            }
            SaveFaults.reset()
            assertEquals("$step: earlier document intact", firstBefore, snapshot(first.pdfFile.parentFile!!))
            // Plain (ASK) replay continues the NEW transaction: no conflict, never a duplicate.
            val doc = replay(s).getOrThrow()
            assertNotEquals(first.id, doc.id)
            assertEquals("$step: exactly the earlier + one new document", 2, repo.listDocuments().size)
            assertEquals(222, ImageTools.imageSize(doc.pageFiles[0].readBytes())!!.first)
            // reset for next iteration: drop the new doc but keep the earlier one
            repo.deleteDocument(doc.id)
            File(s.dir, SaveFormat.JOURNAL).delete()
            SaveFormat.persistJournal(s.dir, SaveFormat.Journal(SaveFormat.readTxn(first.pdfFile.parentFile!!)!!.token, first.id))
        }
    }

    @Test fun `R1 title and category never rewrite or duplicate the committed document`() {
        val s = stage(2)
        crashAt("after_publish")
        assertCrashes { runBlocking { repo.saveScan(s.uris, null, "Original title", category = CategoryRules.STUDY, reviewDir = s.dir) } }
        SaveFaults.reset()
        val first = repo.listDocuments().single()
        val metaBefore = File(first.pdfFile.parentFile, "meta.txt").readBytes()

        val again = runBlocking {
            repo.saveScan(s.uris, null, "A different title", category = CategoryRules.RECEIPTS, reviewDir = s.dir)
        }
        assertEquals(first.id, again.id)
        assertEquals("Original title", again.name)
        assertEquals(CategoryRules.STUDY, again.category)
        assertEquals(listOf(first.id), docDirs())
        assertEquals("meta.txt untouched", metaBefore.toList(), File(first.pdfFile.parentFile, "meta.txt").readBytes().toList())

        // A rename made after the save (before the staging was acknowledged) also wins over a resumed default title.
        repo.renameDocument(first.id, "Renamed by owner")
        assertEquals("Renamed by owner", runBlocking { repo.saveScan(s.uris, null, "Document", reviewDir = s.dir) }.name)
        assertEquals(1, docDirs().size)
    }

    @Test fun `R1 post-save OCR and pdf regeneration do not break unchanged replay but a changed review is still refused`() {
        val (s, first) = committedUnacknowledged(2)
        val layered = File(first.pdfFile.parentFile, "regen.tmp")
        PdfGenerator.imagesToPdf(first.pageFiles, layered, mapOf(0 to OcrPage(120, 80, listOf(OcrLine("Hello", 5, 5, 40, 20)))))
        layered.copyTo(first.pdfFile, overwrite = true); layered.delete()
        File(first.pdfFile.parentFile, "ocr.txt").writeText("Hello")
        File(first.pdfFile.parentFile, "text_layer.flag").writeText("1")
        assertNotEquals(SaveFormat.readTxn(first.pdfFile.parentFile!!)!!.pdfSha256, Sha256.of(first.pdfFile))

        assertEquals(first.id, replay(s).getOrThrow().id)
        assertEquals(first.id, repo.committedDocumentFor(s.dir)?.id)
        s.files[0].writeBytes(ImportFixtures.image(width = 140))
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id)
        assertNull(repo.committedDocumentFor(s.dir))
    }

    @Test fun `R1 a provenance record without a review identity can never be proven to match`() {
        val (s, first) = committedUnacknowledged(2)
        val txnFile = File(first.pdfFile.parentFile, SaveFormat.TXN)
        txnFile.writeText(txnFile.readLines().filterNot { it.startsWith("sourceSha256=") || it.startsWith("filtersSha256=") }.joinToString("\n") + "\n")
        assertNotNull(SaveFormat.readTxn(first.pdfFile.parentFile!!))
        assertNull(repo.committedDocumentFor(s.dir))
        assertConflict(s, SavedReviewConflictException.Reason.REVIEW_CHANGED, first.id)
    }

    @Test fun `R1 committedDocumentFor honours the persisted staging filters and order`() {
        val (s, first) = committedUnacknowledged(2, filters = bwFirst)
        // Cold resume reads filters from the staging, exactly as the review screen does.
        assertNull("filters.txt absent => the resumed review would show Original", repo.committedDocumentFor(s.dir))
        File(s.dir, "filters.txt").writeText("page_000.jpg=bw\npage_001.jpg=original")
        assertEquals(first.id, repo.committedDocumentFor(s.dir)?.id)
        File(s.dir, "filters.txt").writeText("page_000.jpg=original\npage_001.jpg=bw")
        assertNull(repo.committedDocumentFor(s.dir))
    }

    @Test fun `R1 review screen refuses a changed resumed review and lets the owner choose a new document`() {
        val (s, first) = committedUnacknowledged(2)
        val firstBefore = snapshot(first.pdfFile.parentFile!!)
        s.files[1].writeBytes(ImportFixtures.image(width = 188))
        val intent = ScanReviewActivity.intent(app, s.dir.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            var shadow: org.robolectric.shadows.ShadowActivity? = null
            scenario.onActivity { activity ->
                shadow = shadowOf(activity)
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSavePdf).performClick()
            }
            // v2 UI: 09_unlock_sheet — the save conflict is a SwSheet; find its
            // buttons by id while the progress dialog is still up.
            var dialog: android.app.Dialog? = null
            await {
                dialog = ShadowDialog.getLatestDialog()
                dialog?.isShowing == true &&
                    dialog?.findViewById<android.widget.TextView>(com.scanwhirl.app.R.id.swSheetPrimary)
                        ?.text?.toString() ==
                    app.getString(com.scanwhirl.app.R.string.review_conflict_save_new)
            }
            assertEquals(
                app.getString(com.scanwhirl.app.R.string.review_conflict_save_new),
                dialog!!.findViewById<android.widget.TextView>(com.scanwhirl.app.R.id.swSheetPrimary)
                    .text.toString()
            )
            assertEquals(
                app.getString(com.scanwhirl.app.R.string.review_conflict_open),
                dialog!!.findViewById<android.widget.TextView>(com.scanwhirl.app.R.id.swSheetSecondary)
                    .text.toString()
            )
            assertEquals("nothing saved, replaced or deleted yet", listOf(first.id), docDirs())
            assertTrue(s.dir.isDirectory)

            // "Open earlier document" opens it and keeps the review.
            dialog!!.findViewById<android.view.View>(com.scanwhirl.app.R.id.swSheetSecondary)
                .performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val opened = shadow!!.nextStartedActivity
            assertEquals(DocumentViewerActivity::class.java.name, opened.component?.className)
            assertEquals(first.id, opened.getStringExtra("doc_id"))
            assertTrue("staging kept", s.dir.isDirectory)
            assertEquals(listOf(first.id), docDirs())

            // Save the changed review as a new document, explicitly.
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSavePdf).performClick()
            }
            await {
                dialog = ShadowDialog.getLatestDialog()
                dialog?.isShowing == true &&
                    dialog?.findViewById<android.widget.TextView>(com.scanwhirl.app.R.id.swSheetPrimary)
                        ?.text?.toString() ==
                    app.getString(com.scanwhirl.app.R.string.review_conflict_save_new)
            }
            dialog!!.findViewById<android.view.View>(com.scanwhirl.app.R.id.swSheetPrimary)
                .performClick()
            shadowOf(Looper.getMainLooper()).idle()
            await { repo.listDocuments().size == 2 }
        }
        assertEquals("earlier document untouched", firstBefore, snapshot(first.pdfFile.parentFile!!))
        val added = repo.listDocuments().single { it.id != first.id }
        assertEquals(188, ImageTools.imageSize(added.pageFiles[1].readBytes())!!.first)
    }

    // ------------------------------------------------------------------
    // R2 — behaviour after the repair
    // ------------------------------------------------------------------

    private fun reapAged(hidden: String): Int {
        val started = SaveFormat.ownerOf(File(docsDir, hidden))!!.startedAt
        return runBlocking { repo.reapAbandonedSaves(now = started + 25 * 60 * 60 * 1000L) }
    }

    @Test fun `R2 every complete prepared stage survives the reaper at 25 hours and replays to exactly one document`() {
        for (step in listOf("meta", "verified", "before_publish")) {
            docsDir.deleteRecursively()
            val s = stage(3)
            crashAt(step)
            assertCrashes { save(s) }
            SaveFaults.reset()
            val hidden = docDirs().single()
            val dir = File(docsDir, hidden)
            File(dir, "sentinel.bin").writeText("proves the prepared directory itself is published, not rebuilt")
            val before = snapshot(dir)
            val markerBefore = File(dir, ".incomplete").readText()

            assertEquals("$step: nothing removed", 0, reapAged(hidden))
            assertEquals("$step: untouched", before, snapshot(dir))
            assertEquals("$step: marker never removed by the reaper", markerBefore, File(dir, ".incomplete").readText())
            assertEquals("$step: still invisible", 0, repo.listDocuments().size)

            val doc = save(s)
            assertEquals("$step: the same directory is published", hidden, doc.id)
            assertTrue("$step: published, not rebuilt", File(dir, "sentinel.bin").exists())
            assertEquals(listOf(hidden), docDirs())
            assertEquals(1, repo.listDocuments().size)
            PdfVerifier.verify(doc.pdfFile, expectedImages = doc.pageFiles)
            assertEquals("$step: PDF byte-identical to the prepared one", before["scan.pdf"], Sha256.of(doc.pdfFile))
        }
    }

    @Test fun `R2 txn without metadata is preserved by the reaper and rebuilt in place on replay`() {
        val s = stage(2)
        crashAt("txn")
        assertCrashes { save(s) }
        SaveFaults.reset()
        val hidden = docDirs().single()
        assertTrue(File(File(docsDir, hidden), "txn.txt").exists() && !File(File(docsDir, hidden), "meta.txt").exists())
        assertEquals(0, reapAged(hidden))
        assertTrue(File(docsDir, hidden).isDirectory)
        val doc = save(s)
        assertEquals(hidden, doc.id)
        assertEquals(listOf(hidden), docDirs())
    }

    @Test fun `R2 genuinely partial owned output remains cleanable`() {
        for (step in listOf("marked", "page:0", "page:1", "pdf")) {
            docsDir.deleteRecursively()
            val s = stage(3)
            val stagedBefore = snapshot(s.dir).filterKeys { it.endsWith(".jpg") }
            crashAt(step)
            assertCrashes { save(s) }
            SaveFaults.reset()
            val hidden = docDirs().single()
            val started = SaveFormat.ownerOf(File(docsDir, hidden))!!.startedAt

            assertEquals("$step: too young", 0, runBlocking { repo.reapAbandonedSaves(now = started + 23 * 60 * 60 * 1000L) })
            assertTrue(File(docsDir, hidden).exists())
            assertEquals("$step: aged partial output is reaped", 1, reapAged(hidden))
            assertFalse(File(docsDir, hidden).exists())
            assertEquals("$step: review staging untouched", stagedBefore, snapshot(s.dir).filterKeys { it.endsWith(".jpg") })
            assertEquals("$step: the review still saves", 1, listOf(save(s)).size)
            assertEquals(1, repo.listDocuments().size)
        }
    }

    @Test fun `R2 a lone marker tmp reservation is still reaped when aged`() {
        val token = SaveFormat.newToken()
        val dir = File(docsDir, "0a0b0c0d0e0f1011").apply { mkdir() }
        File(dir, ".incomplete.tmp").writeText("scanwhirl-incomplete-v1\ntoken=$token\nstarted=1\n")
        assertEquals(1, runBlocking { repo.reapAbandonedSaves(now = 1 + ScanRepository.ABANDONED_SAVE_AGE_MS) })
        assertFalse(dir.exists())
    }

    @Test fun `R2 a marker that reappears on a complete document hides it, survives the reaper and replays to the same directory`() {
        // Power-loss-shaped state: the unlink of .incomplete was lost, everything else is on disk.
        // (Simulated by recreating the marker; this does NOT prove real power-loss behaviour.)
        val s = stage(2)
        val doc = save(s)
        val dir = doc.pdfFile.parentFile!!
        File(dir, "sentinel.bin").writeText("kept")
        SaveFormat.createMarker(dir, journal(s).token, 1L)
        assertEquals("hidden again", 0, repo.listDocuments().size)
        val before = snapshot(dir)

        assertEquals(0, runBlocking { repo.reapAbandonedSaves(now = 1 + 25 * 60 * 60 * 1000L) })
        assertEquals(before, snapshot(dir))

        val back = save(s)
        assertEquals(doc.id, back.id)
        assertTrue(File(dir, "sentinel.bin").exists())
        assertEquals(1, repo.listDocuments().size)
        assertEquals(listOf(doc.id), docDirs())
    }

    @Test fun `R2 a complete prepared document of a superseded review is preserved and the change saves to a new target`() {
        val s = stage(2)
        crashAt("before_publish")
        assertCrashes { save(s) }
        SaveFaults.reset()
        val hidden = docDirs().single()
        val hiddenBefore = snapshot(File(docsDir, hidden))
        s.files[0].writeBytes(ImportFixtures.image(width = 177))

        val doc = save(s)
        assertNotEquals("new target persisted", hidden, doc.id)
        assertEquals(doc.id, journal(s).target)
        assertEquals("complete prepared output untouched and still hidden", hiddenBefore, snapshot(File(docsDir, hidden)))
        assertEquals(listOf(doc.id), repo.listDocuments().map { it.id })
        assertEquals(177, ImageTools.imageSize(doc.pageFiles[0].readBytes())!!.first)
        // ...and even at 25 hours the reaper still keeps it.
        assertEquals(0, reapAged(hidden))
        assertTrue(File(docsDir, hidden).isDirectory)
    }

    @Test fun `R2 foreign and unverifiable directories are never touched by the reaper or by replay`() {
        val s = stage(2)
        val journal = SaveFormat.Journal(SaveFormat.newToken(), "00112233445566ff")
        SaveFormat.persistJournal(s.dir, journal)
        // Another transaction's complete-looking hidden output squatting on our target id.
        val foreign = File(docsDir, journal.target).apply { mkdir() }
        SaveFormat.createMarker(foreign, SaveFormat.newToken(), 1L)
        File(foreign, "scan.pdf").writeText("not ours")
        File(foreign, "txn.txt").writeText("scanwhirl-txn-v1\ntoken=${SaveFormat.newToken()}\n")
        // A directory whose marker we cannot parse: ownership is unprovable.
        val garbage = File(docsDir, "00112233445566aa").apply { mkdir() }
        File(garbage, ".incomplete").writeText("garbage that is not a marker")
        File(garbage, "x.jpg").writeText("x")
        val foreignBefore = snapshot(foreign); val garbageBefore = snapshot(garbage)

        assertEquals(0, runBlocking { repo.reapAbandonedSaves(now = 1 + 100 * ScanRepository.ABANDONED_SAVE_AGE_MS) })
        assertEquals(foreignBefore, snapshot(foreign))
        assertEquals(garbageBefore, snapshot(garbage))

        // Replay: a marker with somebody else's token is never judged, deleted or unmarked.
        val doc = save(s)
        assertNotEquals(journal.target, doc.id)
        assertEquals("foreign marker never removed", foreignBefore, snapshot(foreign))
        assertEquals(garbageBefore, snapshot(garbage))
    }
}
