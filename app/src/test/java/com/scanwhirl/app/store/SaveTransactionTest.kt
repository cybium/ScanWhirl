package com.scanwhirl.app.store

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.MainActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WP-05 core: transactional, crash-safe new-scan save.
 *
 * What these tests are, honestly: deterministic JVM simulations. A simulated process
 * death ([SimulatedProcessDeath], an Error) skips every cleanup handler exactly like a
 * kill would, so the on-disk state left behind is the state a killed process leaves.
 * They are NOT proof of real process death, power loss or `fsync` semantics on an
 * Android device — those remain device-only acceptance gates.
 *
 * All pages are real JPEGs and every PDF is a real generated PDF.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SaveTransactionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }

    @Before @After fun reset() {
        SaveFaults.reset()
        Durable.resetForTests()
        ScanRepository.resetTestSeams()
    }

    // ---------------------------------------------------------------- helpers

    private class Staged(val dir: File, val files: List<File>) {
        val uris get() = files.map { Uri.fromFile(it) }
        val bytes get() = files.map { it.readBytes() }
    }

    private var stagingCounter = 0

    private fun stage(count: Int, parent: File = File(app.filesDir, "pending_scans")): Staged {
        val dir = File(parent, "pending_${System.nanoTime()}_${stagingCounter++}").apply { mkdirs() }
        val files = (0 until count).map { i ->
            File(dir, "page_%03d.jpg".format(i + 1)).apply { writeBytes(ImportFixtures.image(width = 120 + i)) }
        }
        return Staged(dir, files)
    }

    private fun save(staged: Staged, name: String = "Doc"): ScanDocument =
        runBlocking { repo.saveScan(staged.uris, null, name, reviewDir = staged.dir) }

    private fun crashAt(step: String) {
        SaveFaults.hook = { if (it == step) throw SimulatedProcessDeath(it) }
    }

    private fun failAt(step: String, error: () -> Exception) {
        SaveFaults.hook = { if (it == step) throw error() }
    }

    private fun assertCrashes(block: () -> Unit) {
        try {
            block()
            fail("expected a simulated process death")
        } catch (_: SimulatedProcessDeath) {
        }
    }

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Sha256.of(it) }

    private fun stagedSnapshot(s: Staged) = snapshot(s.dir).filterKeys { it.endsWith(".jpg") }

    private fun docDirs() = docsDir.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()

    private fun assertNothingVisible() = assertEquals(emptyList<ScanDocument>(), repo.listDocuments())

    private fun journal(s: Staged) = SaveFormat.loadJournal(s.dir)!!

    private fun assertPublishedAndVerified(doc: ScanDocument, expectedPages: Int) {
        assertEquals(expectedPages, doc.pageCount)
        assertFalse("no marker on a published document", File(doc.pdfFile.parentFile, ".incomplete").exists())
        PdfVerifier.verify(doc.pdfFile, expectedImages = doc.pageFiles)
        doc.pageFiles.forEachIndexed { i, f ->
            assertEquals("page_%03d.jpg".format(i), f.name)
            assertEquals("page $i order", 120 + i, ImageTools.imageSize(f.readBytes())!!.first)
            ImportFixtures.assertJpeg(f.readBytes()).recycle()
        }
    }

    // ---------------------------------------------------------------- success

    @Test fun `success with 1 5 and 30 pages publishes one verified document with correct order`() {
        for (n in listOf(1, 5, 30)) {
            docsDir.deleteRecursively()
            val staged = stage(n)
            val before = stagedSnapshot(staged)
            val doc = save(staged, "Doc $n")
            assertPublishedAndVerified(doc, n)
            assertEquals(listOf(doc.id), docDirs())
            assertEquals(listOf(doc.id), repo.listDocuments().map { it.id })
            // Page bytes of an ordinary JPEG stay identical; staging is untouched by the save.
            assertEquals(staged.files.map { Sha256.of(it) }, doc.pageFiles.map { Sha256.of(it) })
            assertEquals(before, stagedSnapshot(staged))
            // Provenance written before the commit, and the identity is the journalled one.
            val txn = SaveFormat.readTxn(doc.pdfFile.parentFile!!)!!
            assertEquals(journal(staged).token, txn.token)
            assertEquals(journal(staged).target, doc.id)
            assertEquals(n, txn.pages)
            // OCR/indexing is queued only after publication.
            assertTrue(repo.ocrStore.isPending(doc.id))
        }
    }

    @Test fun `new document ids are 64-bit while legacy ids still load`() {
        val doc = save(stage(1))
        assertEquals(16, doc.id.length)
        val legacy = repo.let { r -> runBlocking { r.createDocument("Legacy", listOf(ImportFixtures.image())) } }
        assertEquals(8, legacy.id.length)
        assertEquals(2, repo.listDocuments().size)
    }

    // ------------------------------------------------- interruption at every step

    @Test fun `process death at any step never shows a document and replay yields exactly one`() {
        val steps = listOf(
            "journaled", "reserved", "marked", "page:0", "page:2", "pdf", "txn", "meta", "verified", "before_publish"
        )
        for (step in steps) {
            docsDir.deleteRecursively()
            val staged = stage(3)
            val before = stagedSnapshot(staged)

            crashAt(step)
            assertCrashes { save(staged) }
            SaveFaults.reset()

            assertNothingVisible()
            assertEquals("$step: staging pages must survive", before, stagedSnapshot(staged))
            assertNotNull("$step: identity persisted before anything else", SaveFormat.loadJournal(staged.dir))
            val originalTarget = journal(staged).target

            val doc = save(staged)
            assertPublishedAndVerified(doc, 3)
            assertEquals("$step: one visible document", 1, repo.listDocuments().size)
            assertEquals("$step: replay uses the journalled identity", journal(staged).target, doc.id)
            if (step == "reserved") {
                // An empty directory without a marker is not provably ours: it is left alone and a
                // new target is persisted once.
                assertNotEquals(originalTarget, doc.id)
                assertTrue(File(docsDir, originalTarget).list()!!.isEmpty())
            } else {
                assertEquals("$step: same target reused", originalTarget, doc.id)
                assertEquals("$step: no leftovers or duplicates", listOf(doc.id), docDirs())
            }
            assertEquals(before, stagedSnapshot(staged))
        }
    }

    @Test fun `abandoned marker-tmp reservation is recognised as ours and reused`() {
        val staged = stage(2)
        val journal = SaveFormat.Journal(SaveFormat.newToken(), "abcdef0123456789")
        SaveFormat.persistJournal(staged.dir, journal)
        // The state a kill leaves between writing .incomplete.tmp and renaming it.
        val dir = File(docsDir, journal.target).apply { mkdir() }
        File(dir, ".incomplete.tmp").writeText("scanwhirl-incomplete-v1\ntoken=${journal.token}\nstarted=1\n")
        val doc = save(staged)
        assertEquals(journal.target, doc.id)
        assertEquals(listOf(journal.target), docDirs())
        assertPublishedAndVerified(doc, 2)
    }

    // ------------------------------------------------------------ publish edge

    @Test fun `failure immediately before publish leaves nothing visible and no owned leftovers`() {
        val staged = stage(2)
        val before = stagedSnapshot(staged)
        failAt("before_publish") { IOException("boom") }
        try { save(staged); fail() } catch (e: IOException) { assertEquals("boom", e.message) }
        SaveFaults.reset()
        assertNothingVisible()
        assertEquals("owned hidden directory removed", emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
        val retry = save(staged)
        assertEquals(journal(staged).target, retry.id)
        assertPublishedAndVerified(retry, 2)
    }

    @Test fun `death right after publish keeps the committed document and replay returns the same one`() {
        val staged = stage(3)
        val before = stagedSnapshot(staged)
        crashAt("after_publish")
        assertCrashes { save(staged) }
        SaveFaults.reset()

        val committed = repo.listDocuments().single()
        assertPublishedAndVerified(committed, 3)
        assertEquals(before, stagedSnapshot(staged)) // staging not erased before acknowledgement
        assertEquals(committed.id, repo.committedDocumentFor(staged.dir)?.id)
        val pdfHash = Sha256.of(committed.pdfFile)

        val replayed = save(staged)
        assertEquals(committed.id, replayed.id)
        assertEquals(listOf(committed.id), docDirs())
        assertEquals("replay does not regenerate anything", pdfHash, Sha256.of(replayed.pdfFile))
    }

    @Test fun `an ordinary exception after publish never deletes the committed document`() {
        val staged = stage(1)
        failAt("after_publish") { IllegalStateException("late failure") }
        try { save(staged); fail() } catch (_: IllegalStateException) { }
        SaveFaults.reset()
        assertPublishedAndVerified(repo.listDocuments().single(), 1)
    }

    @Test fun `replaying the same token repeatedly produces exactly one document`() {
        val staged = stage(2)
        val first = save(staged)
        val second = save(staged)
        val third = save(staged)
        assertEquals(first.id, second.id)
        assertEquals(first.id, third.id)
        assertEquals(1, docDirs().size)
        assertEquals(Sha256.of(first.pdfFile), Sha256.of(third.pdfFile))
    }

    @Test fun `replay still recognises the document after indexing regenerated its pdf`() {
        val staged = stage(2)
        val first = save(staged)
        // Post-save indexing legitimately rewrites scan.pdf (e.g. adds a text layer).
        val pages = first.pageFiles
        val layered = File(first.pdfFile.parentFile, "regen.tmp")
        com.scanwhirl.app.util.PdfGenerator.imagesToPdf(
            pages, layered,
            mapOf(0 to com.scanwhirl.app.model.OcrPage(120, 80, listOf(com.scanwhirl.app.model.OcrLine("Hello", 5, 5, 40, 20))))
        )
        layered.copyTo(first.pdfFile, overwrite = true); layered.delete()
        assertNotEquals(SaveFormat.readTxn(first.pdfFile.parentFile!!)!!.pdfSha256, Sha256.of(first.pdfFile))
        assertEquals(first.id, save(staged).id)
        assertEquals(1, docDirs().size)
    }

    // -------------------------------------------------- never return a foreign doc

    @Test fun `a document with a different token is never returned as a successful save`() {
        val staged = stage(2)
        val j = SaveFormat.Journal(SaveFormat.newToken(), "0123456789abcdef")
        SaveFormat.persistJournal(staged.dir, j)
        // Someone else's committed document squatting on our target id.
        val other = stage(2)
        val otherDoc = save(other)
        val squat = File(docsDir, j.target)
        otherDoc.pdfFile.parentFile!!.copyRecursively(squat)
        val squatBefore = snapshot(squat)

        val doc = save(staged)
        assertNotEquals(j.target, doc.id)
        assertEquals("collision persisted once", doc.id, journal(staged).target)
        assertEquals("foreign directory untouched", squatBefore, snapshot(squat))
        assertPublishedAndVerified(doc, 2)
    }

    @Test fun `our token with tampered page content is not accepted as the committed document`() {
        val staged = stage(2)
        val first = save(staged)
        val victim = first.pageFiles[1]
        victim.writeBytes(ImportFixtures.image(width = 300))
        val tamperedBefore = snapshot(first.pdfFile.parentFile!!)
        assertNull(repo.committedDocumentFor(staged.dir))
        val second = save(staged)
        assertNotEquals(first.id, second.id)
        assertEquals(tamperedBefore, snapshot(first.pdfFile.parentFile!!))
        assertPublishedAndVerified(second, 2)
    }

    @Test fun `existing EMPTY target directory is never replaced or deleted`() {
        val staged = stage(2)
        val j = SaveFormat.Journal(SaveFormat.newToken(), "feedfacefeedface")
        SaveFormat.persistJournal(staged.dir, j)
        val empty = File(docsDir, j.target).apply { mkdir() }
        val doc = save(staged)
        assertNotEquals(j.target, doc.id)
        assertTrue("empty directory still there", empty.isDirectory)
        assertEquals("and still empty", 0, empty.list()!!.size)
        assertEquals(doc.id, journal(staged).target)
        assertPublishedAndVerified(doc, 2)
    }

    @Test fun `existing populated legacy target directory stays byte-identical`() {
        val legacy = runBlocking { repo.createDocument("Legacy", listOf(ImportFixtures.image(), ImportFixtures.image())) }
        val legacyDir = legacy.pdfFile.parentFile!!
        val before = snapshot(legacyDir)
        val staged = stage(2)
        SaveFormat.persistJournal(staged.dir, SaveFormat.Journal(SaveFormat.newToken(), legacy.id))
        val doc = save(staged)
        assertNotEquals(legacy.id, doc.id)
        assertEquals(before, snapshot(legacyDir))
        assertEquals(2, repo.listDocuments().size)
    }

    @Test fun `collision fallback is itself replay safe`() {
        val staged = stage(2)
        val j = SaveFormat.Journal(SaveFormat.newToken(), "c0ffeec0ffeec0ff")
        SaveFormat.persistJournal(staged.dir, j)
        File(docsDir, j.target).mkdir()
        crashAt("after_publish")
        assertCrashes { save(staged) }
        SaveFaults.reset()
        val newTarget = journal(staged).target
        assertNotEquals(j.target, newTarget)
        val replay = save(staged)
        assertEquals(newTarget, replay.id)
        assertEquals(setOf(j.target, newTarget), docDirs().toSet())
        assertEquals(1, repo.listDocuments().size)
    }

    // ------------------------------------------------- PDF integrity gates

    private fun corruptAt(step: String, change: (File) -> Unit) {
        SaveFaults.hook = { s ->
            if (s == step) {
                val dir = docsDir.listFiles { f -> f.isDirectory }!!.single()
                change(File(dir, "scan.pdf"))
            }
        }
    }

    private fun assertRejectedBeforePublish(staged: Staged, before: Map<String, String>, message: String? = null) {
        try {
            save(staged)
            fail("a damaged PDF must never be published")
        } catch (e: IOException) {
            if (message != null) assertTrue(e.message, e.message!!.contains(message))
        }
        SaveFaults.reset()
        assertNothingVisible()
        assertEquals(emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
    }

    @Test fun `absent pdf blocks publication`() {
        val staged = stage(2); val before = stagedSnapshot(staged)
        corruptAt("meta") { it.delete() }
        assertRejectedBeforePublish(staged, before, "PDF is missing")
    }

    @Test fun `zero byte pdf blocks publication`() {
        val staged = stage(2); val before = stagedSnapshot(staged)
        corruptAt("meta") { it.writeBytes(ByteArray(0)) }
        assertRejectedBeforePublish(staged, before)
    }

    @Test fun `truncated pdf blocks publication`() {
        val staged = stage(3); val before = stagedSnapshot(staged)
        corruptAt("meta") { it.writeBytes(it.readBytes().copyOf(it.length().toInt() - 40)) }
        assertRejectedBeforePublish(staged, before)
    }

    @Test fun `page count mismatched pdf blocks publication`() {
        val staged = stage(3); val before = stagedSnapshot(staged)
        val shorter = File(app.cacheDir, "two.pdf")
        com.scanwhirl.app.util.PdfGenerator.imagesToPdf(staged.files.take(2), shorter)
        corruptAt("meta") { shorter.copyTo(it, overwrite = true) }
        assertRejectedBeforePublish(staged, before, "Page count mismatch")
    }

    @Test fun `malformed xref page tree and stream lengths block publication`() {
        fun mutateAscii(find: String, replace: String): (File) -> Unit = { pdf ->
            val text = pdf.readText(Charsets.ISO_8859_1)
            assertTrue(find in text)
            pdf.writeBytes(text.replaceFirst(find, replace).toByteArray(Charsets.ISO_8859_1))
        }
        val cases = listOf(
            "page tree count" to mutateAscii("/Count 3", "/Count 2"),
            "kids cardinality" to mutateAscii("9 0 R]", "     ]"),
            "content/image stream length" to { pdf: File ->
                val text = pdf.readText(Charsets.ISO_8859_1)
                val len = Regex("/DCTDecode /Length (\\d+) >>").find(text)!!.groupValues[1]
                mutateAscii("/DCTDecode /Length $len >>", "/DCTDecode /Length ${len.toInt() - 1} >>")(pdf)
            },
            "xref offset" to { pdf: File ->
                val text = pdf.readText(Charsets.ISO_8859_1)
                val offset = Regex("startxref\\n(\\d+)\\n").find(text)!!.groupValues[1]
                mutateAscii("startxref\n$offset\n", "startxref\n${(offset.toInt() - 3).toString().padStart(offset.length, '0')}\n")(pdf)
            }
        )
        for ((label, change) in cases) {
            docsDir.deleteRecursively()
            val staged = stage(3); val before = stagedSnapshot(staged)
            corruptAt("meta", change)
            try { save(staged); fail("$label must not publish") } catch (_: IOException) { }
            SaveFaults.reset()
            assertNothingVisible()
            assertEquals(label, before, stagedSnapshot(staged))
        }
    }

    @Test fun `pdf generation failure is never swallowed`() {
        val staged = stage(2); val before = stagedSnapshot(staged)
        Durable.beforeFileSync = { if (it.name == "scan.pdf.tmp") throw IOException("pdf sync failed") }
        try { save(staged); fail() } catch (e: Durable.SyncException) { assertTrue(e.message!!.contains("pdf sync failed")) }
        Durable.resetForTests()
        assertNothingVisible()
        assertEquals(emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
    }

    // ------------------------------------------ durability and storage failures

    @Test fun `a failing required file sync fails the transaction for every file kind`() {
        for (name in listOf("page_000.jpg", "page_001.jpg", "scan.pdf.tmp", "txn.txt.tmp", "meta.txt.tmp", ".incomplete.tmp")) {
            docsDir.deleteRecursively()
            val staged = stage(2); val before = stagedSnapshot(staged)
            Durable.beforeFileSync = { if (it.name == name) throw IOException("fsync EIO on $name") }
            try { save(staged); fail("$name sync failure must fail the save") } catch (e: Durable.SyncException) {
                assertTrue(e.message, e.message!!.contains(name))
            }
            Durable.resetForTests()
            assertNothingVisible()
            assertEquals(name, emptyList<String>(), docDirs())
            assertEquals(before, stagedSnapshot(staged))
        }
    }

    @Test fun `best effort directory sync failure never fails a save`() {
        for (syncer in listOf<(File) -> Boolean>({ throw IOException("no dir fsync") }, { false })) {
            docsDir.deleteRecursively()
            Durable.dirSyncer = syncer
            val doc = save(stage(2))
            assertPublishedAndVerified(doc, 2)
        }
    }

    @Test fun `injected ENOSPC while writing a page surfaces as an IOException and cleans up`() {
        val staged = stage(3); val before = stagedSnapshot(staged)
        failAt("page:1") { IOException("No space left on device (ENOSPC)") }
        try { save(staged); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("ENOSPC")) }
        SaveFaults.reset()
        assertNothingVisible()
        assertEquals(emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
        assertPublishedAndVerified(save(staged), 3) // space returned: the same review saves fine
    }

    @Test fun `ENOSPC during an fsync is a failed transaction not a corrupt document`() {
        val staged = stage(2)
        Durable.beforeFileSync = { if (it.name == "page_001.jpg") throw IOException("No space left on device") }
        try { save(staged); fail() } catch (_: Durable.SyncException) { }
        Durable.resetForTests()
        assertNothingVisible()
        assertEquals(emptyList<String>(), docDirs())
    }

    @Test fun `advisory free space check fails early without creating anything`() {
        val staged = stage(2); val before = stagedSnapshot(staged)
        ScanRepository.usableSpace = { 1024L }
        try { save(staged); fail() } catch (e: IOException) { assertTrue(e.message, e.message!!.contains("Not enough storage")) }
        assertEquals(emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
    }

    @Test fun `a bad page reports its number and removes only the owned directory`() {
        val existing = save(stage(1), "Existing")
        val existingBefore = snapshot(existing.pdfFile.parentFile!!)
        val staged = stage(3)
        staged.files[1].writeBytes(byteArrayOf(1, 2, 3))
        val before = stagedSnapshot(staged)
        try { save(staged); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message, e.message!!.startsWith("Page 2: ")) }
        assertEquals(listOf(existing.id), docDirs())
        assertEquals(existingBefore, snapshot(existing.pdfFile.parentFile!!))
        assertEquals(before, stagedSnapshot(staged))
    }

    // ---------------------------------------------------------- cancellation

    @Test fun `cancellation propagates cleans up and preserves the source pages`() = runBlocking {
        val staged = stage(3); val before = stagedSnapshot(staged)
        lateinit var job: Job
        SaveFaults.hook = { if (it == "page:0") job.cancel() }
        job = launch(Dispatchers.Default) { repo.saveScan(staged.uris, null, "Doc", reviewDir = staged.dir) }
        job.join()
        SaveFaults.reset()
        assertTrue("surfaced as cancellation, not a failure", job.isCancelled)
        assertNothingVisible()
        assertEquals(emptyList<String>(), docDirs())
        assertEquals(before, stagedSnapshot(staged))
        assertPublishedAndVerified(save(staged), 3)
    }

    @Test fun `cancellation after publish keeps the document and replay finds it`() = runBlocking {
        val staged = stage(2)
        lateinit var job: Job
        SaveFaults.hook = { if (it == "after_publish") job.cancel() }
        job = launch(Dispatchers.Default) { repo.saveScan(staged.uris, null, "Doc", reviewDir = staged.dir) }
        job.join()
        SaveFaults.reset()
        assertTrue(job.isCancelled)
        val committed = repo.listDocuments().single()
        assertEquals(committed.id, save(staged).id)
        assertEquals(1, docDirs().size)
    }

    // -------------------------------------------------------- cleanup safety

    @Test fun `reaper removes only aged owned incomplete directories`() = runBlocking {
        val legacy = repo.createDocument("Legacy", listOf(ImportFixtures.image()))
        val published = save(stage(1), "Published")
        val unknown = File(docsDir, "not-ours").apply { mkdir() }
        File(unknown, "file.txt").writeText("someone else's")
        val empty = File(docsDir, "emptyorphan").apply { mkdir() }
        val protectedBefore = listOf(legacy.pdfFile.parentFile!!, published.pdfFile.parentFile!!, unknown)
            .map { it to snapshot(it) }

        val staged = stage(2); val stagedBefore = stagedSnapshot(staged)
        crashAt("page:0")
        assertCrashes { save(staged) }
        SaveFaults.reset()
        val hidden = docDirs().first { it !in setOf(legacy.id, published.id, "not-ours", "emptyorphan") }
        val started = SaveFormat.ownerOf(File(docsDir, hidden))!!.startedAt

        assertEquals("too young", 0, repo.reapAbandonedSaves(now = started + 1000))
        assertTrue(File(docsDir, hidden).exists())

        assertEquals("aged and owned", 1, repo.reapAbandonedSaves(now = started + ScanRepository.ABANDONED_SAVE_AGE_MS + 1))
        assertFalse(File(docsDir, hidden).exists())
        assertTrue(empty.isDirectory)
        protectedBefore.forEach { (dir, snap) -> assertEquals(snap, snapshot(dir)) }
        assertEquals("review staging is never swept", stagedBefore, stagedSnapshot(staged))
        // The interrupted review still saves afterwards.
        assertPublishedAndVerified(save(staged), 2)
    }

    @Test fun `cleanup cannot run against an active save`() = runBlocking {
        val staged = stage(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SaveFaults.hook = { if (it == "page:0") { entered.countDown(); release.await(20, TimeUnit.SECONDS) } }
        val saving = async(Dispatchers.Default) { repo.saveScan(staged.uris, null, "Doc", reviewDir = staged.dir) }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reaping = async(Dispatchers.Default) { repo.reapAbandonedSaves(minAgeMs = 0) }
        delay(400)
        assertFalse("reaper must wait for the active transaction", reaping.isCompleted)
        assertTrue("active transaction directory intact", docDirs().size == 1)
        release.countDown()
        val doc = saving.await()
        assertEquals(0, reaping.await())
        assertPublishedAndVerified(doc, 2)
    }

    // ------------------------------------------------------ visibility rule

    @Test fun `every reader hides a directory while its marker exists even after meta is written`() {
        val staged = stage(2)
        crashAt("before_publish") // meta.txt, pdf, pages, txn all present; marker still there
        assertCrashes { save(staged, "Document 5") }
        SaveFaults.reset()
        val id = docDirs().single()
        val dir = File(docsDir, id)
        assertTrue(File(dir, "meta.txt").exists() && File(dir, "scan.pdf").exists())
        assertNull(repo.getDocument(id))
        assertNothingVisible()
        assertEquals("Document 1", repo.nextDocumentName()) // its name is not reserved by a hidden dir
        repo.ocrStore.markPending(id)
        assertFalse("indexing must not queue an unpublished directory", File(dir, "ocr_pending").exists())
        // Crash between mkdir and marker: empty directory, never visible.
        File(docsDir, "reservedonly").mkdir()
        assertNothingVisible()
    }

    @Test fun `OCR is marked pending only after publish and never creates a directory`() {
        val staged = stage(1)
        crashAt("before_publish")
        assertCrashes { save(staged) }
        SaveFaults.reset()
        assertFalse(File(docsDir, docDirs().single()).resolve("ocr_pending").exists())
        val doc = save(staged)
        assertTrue(repo.ocrStore.isPending(doc.id))
        repo.ocrStore.markPending("ffffffffffffffff")
        assertFalse(File(docsDir, "ffffffffffffffff").exists())
    }

    // -------------------------------------------------------------- metadata

    @Test fun `metadata mutators are atomic and preserve createdAt category unknown lines and exports`() {
        val doc = save(stage(1), "Original name")
        val meta = File(doc.pdfFile.parentFile, "meta.txt")
        val createdAt = meta.readLines()[1]
        meta.writeText(meta.readText().trimEnd('\n') + "\nfuture-key=keep me\n")
        repo.addExport(doc.id, Uri.parse("content://media/external/file/42"))
        repo.setCategory(doc.id, com.scanwhirl.app.util.CategoryRules.FORMS)
        repo.renameDocument(doc.id, "Renamed")
        repo.addExport(doc.id, Uri.parse("content://media/external/file/43"))

        val lines = meta.readLines()
        assertEquals("Renamed", lines[0])
        assertEquals("createdAt untouched", createdAt, lines[1])
        assertEquals(com.scanwhirl.app.util.CategoryRules.FORMS, lines[2])
        assertTrue("unknown line preserved", "future-key=keep me" in lines)
        assertEquals(
            listOf(Uri.parse("content://media/external/file/42"), Uri.parse("content://media/external/file/43")),
            repo.exportsOf(doc.id)
        )
        assertFalse(File(doc.pdfFile.parentFile, "meta.txt.tmp").exists())
        assertEquals("Renamed", repo.getDocument(doc.id)!!.name)
    }

    @Test fun `a failed metadata write leaves the previous meta byte-identical`() {
        val doc = save(stage(1), "Keep me")
        val meta = File(doc.pdfFile.parentFile, "meta.txt")
        val before = meta.readBytes()
        Durable.beforeFileSync = { throw IOException("fsync failed") }
        for (mutate in listOf<() -> Unit>(
            { repo.renameDocument(doc.id, "New") },
            { repo.setCategory(doc.id, com.scanwhirl.app.util.CategoryRules.FORMS) },
            { repo.addExport(doc.id, Uri.parse("content://x/1")) }
        )) {
            try { mutate(); fail() } catch (_: Durable.SyncException) { }
            assertArrayEquals(before, meta.readBytes())
            assertFalse(File(meta.parentFile, "meta.txt.tmp").exists())
        }
        Durable.resetForTests()
        assertEquals("Keep me", repo.getDocument(doc.id)!!.name)
    }

    @Test fun `existing documents stay byte-identical through saves crashes replays and reaping`() = runBlocking {
        val legacyA = repo.createDocument("Legacy A", listOf(ImportFixtures.image(), ImportFixtures.image(width = 90)))
        val legacyB = repo.createDocument("Legacy B", listOf(ImportFixtures.image(width = 70)))
        val dirs = listOf(legacyA, legacyB).map { it.pdfFile.parentFile!! }
        val before = dirs.map { snapshot(it) }
        val staged = stage(3)
        crashAt("pdf"); assertCrashes { save(staged) }; SaveFaults.reset()
        crashAt("after_publish"); assertCrashes { save(staged) }; SaveFaults.reset()
        save(staged)
        repo.reapAbandonedSaves(minAgeMs = 0)
        assertEquals(before, dirs.map { snapshot(it) })
        assertEquals(3, repo.listDocuments().size)
    }

    // --------------------------------------------- staging, legacy, and the UI

    @Test fun `durable staging lives outside cache and outside documents and legacy sessions are still listed`() {
        val durable = PendingScans.create(app)
        assertEquals(PendingScans.root(app), durable.parentFile)
        assertFalse(durable.path.startsWith(app.cacheDir.path))
        assertFalse(durable.path.startsWith(docsDir.path))
        assertNotEquals("creation is exclusive", durable, PendingScans.create(app))
        val staged = stage(2, parent = app.cacheDir) // legacy location: cacheDir/pending_*
        File(durable, "page_001.jpg").writeBytes(ImportFixtures.image())
        val all = PendingScans.all(app)
        assertTrue(all.contains(staged.dir))
        assertTrue(all.contains(durable))
        // A legacy session still saves, in place, without moving or deleting its pages.
        val before = stagedSnapshot(staged)
        assertPublishedAndVerified(save(staged), 2)
        assertEquals(before, stagedSnapshot(staged))
        assertTrue(staged.dir.isDirectory)
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

    @Test fun `launcher finds a legacy unfinished scan and offers resume`() {
        val staged = stage(2, parent = app.cacheDir)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val found = ScanLauncher(activity, repo, activity) {}.findUnfinished()!!
                assertEquals(staged.dir, found.dir)
                assertEquals(2, found.pageCount)
                assertNull(found.committed)
            }
        }
        assertTrue("nothing was deleted", staged.dir.isDirectory)
    }

    @Test fun `committed but unacknowledged review offers to open the saved document and cleans up only after opening`() {
        val staged = stage(2)
        crashAt("after_publish")
        assertCrashes { save(staged) }
        SaveFaults.reset()
        val committed = repo.listDocuments().single()

        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            var activityShadow: org.robolectric.shadows.ShadowActivity? = null
            scenario.onActivity { activity ->
                activityShadow = shadowOf(activity)
                assertEquals(committed.id, ScanLauncher(activity, repo, activity) {}.findUnfinished()!!.committed?.id)
                ScanLauncher(activity, repo, activity) {}.offerUnfinishedScan()
            }
            var dialog: AlertDialog? = null
            await { dialog = ShadowDialog.getLatestDialog() as? AlertDialog; dialog?.isShowing == true }
            assertTrue("staging kept until the user acts", staged.dir.isDirectory)
            assertEquals(
                app.getString(com.scanwhirl.app.R.string.resume_saved_open),
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).text.toString()
            )
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle() // AlertController delivers clicks through its handler
            val opened = activityShadow!!.nextStartedActivity
            assertEquals(DocumentViewerActivity::class.java.name, opened.component?.className)
            assertEquals(committed.id, opened.getStringExtra("doc_id"))
        }
        assertFalse("leftover staging removed after the verified document was opened", staged.dir.exists())
        assertEquals(listOf(committed.id), docDirs())
    }

    // ------------------------------------------------- review screen (end to end)

    @Test fun `review screen keeps every staged page and shows the page error when the save fails`() {
        val staged = stage(2)
        staged.files[1].writeBytes(byteArrayOf(9, 9, 9))
        val before = stagedSnapshot(staged)
        val intent = com.scanwhirl.app.ui.ScanReviewActivity.intent(app, staged.dir.path, null, "Doc")
        ActivityScenario.launch<com.scanwhirl.app.ui.ScanReviewActivity>(intent).use { scenario ->
            var shadow: org.robolectric.shadows.ShadowActivity? = null
            scenario.onActivity { activity ->
                shadow = shadowOf(activity)
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSavePdf).performClick()
            }
            await { org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.contains("Page 2") == true }
            assertNull("no document screen opened", shadow!!.nextStartedActivity)
            assertEquals("staging intact after a failed save", before, stagedSnapshot(staged))
            assertNothingVisible()
            assertEquals(emptyList<String>(), docDirs())
            // The user can fix the page and save again from the same review (same identity).
            staged.files[1].writeBytes(ImportFixtures.image(width = 121))
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSavePdf).performClick()
            }
            await { repo.listDocuments().size == 1 }
        }
        assertPublishedAndVerified(repo.listDocuments().single(), 2)
    }

    @Test fun `review screen resumed after a committed save opens that document and creates no duplicate`() {
        val staged = stage(2)
        crashAt("after_publish")
        assertCrashes { save(staged) }
        SaveFaults.reset()
        val committed = repo.listDocuments().single()
        val intent = com.scanwhirl.app.ui.ScanReviewActivity.intent(app, staged.dir.path, null, "Doc")
        ActivityScenario.launch<com.scanwhirl.app.ui.ScanReviewActivity>(intent).use { scenario ->
            var shadow: org.robolectric.shadows.ShadowActivity? = null
            scenario.onActivity { activity ->
                shadow = shadowOf(activity)
                activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnSavePdf).performClick()
            }
            var opened: Intent? = null
            await {
                opened = shadow!!.nextStartedActivity?.takeIf { it.component?.className == DocumentViewerActivity::class.java.name }
                opened != null
            }
            assertEquals(committed.id, opened!!.getStringExtra("doc_id"))
        }
        assertEquals(listOf(committed.id), docDirs())
        assertFalse("staging released only after the verified document was acknowledged", staged.dir.exists())
    }
}
