package com.scanwhirl.app.store

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PendingReviewStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folders = mutableListOf<File>()
    private fun stage(count: Int = 1): File = PendingScans.create(context).also { dir ->
        folders.add(dir)
        repeat(count) { File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 100 + it)) }
    }
    private val state = PendingScans.ReviewState("  नाम 😀 e\u0301\tعنوان  ", "Document 9", CategoryRules.STUDY, true)
    private fun snapshot(dir: File) = dir.listFiles()!!.filter { it.isFile }
        .associate { it.name to Sha256.of(it) }

    @After fun cleanup() { Durable.resetForTests(); folders.forEach { it.deleteRecursively() } }

    @Test fun `one and five page folders cold restore exact Unicode title and manual category independently`() {
        val first = stage(1)
        val second = stage(5)
        val other = state.copy(title = "Invoice", category = CategoryRules.IDS)
        PendingScans.queueReviewState(first, state).get(5, TimeUnit.SECONDS)
        PendingScans.queueReviewState(second, other).get(5, TimeUnit.SECONDS)
        assertEquals(state, PendingScans.readReviewState(File(first.path)))
        assertEquals(other, PendingScans.readReviewState(File(second.path)))
        assertEquals(1, PendingScans.pageCount(first))
        assertEquals(5, PendingScans.pageCount(second))
    }

    @Test fun `optional missing malformed unknown version and copied record leave pages recoverable`() {
        val dir = stage(5)
        val before = snapshot(dir)
        val file = File(dir, PendingScans.REVIEW_STATE_FILE)
        assertNull(PendingScans.readReviewState(dir))
        for (bad in listOf("{\"version\":", "{}",
            JSONObject(PendingScans.encodeReviewState(dir, state)).put("version", 2).toString(),
            JSONObject(PendingScans.encodeReviewState(dir, state)).put("category", "bogus").toString(),
            PendingScans.encodeReviewState(stage(), state))) {
            file.writeText(bad)
            assertNull(PendingScans.readReviewState(dir))
            assertEquals(5, PendingScans.pageCount(dir))
            assertTrue(PendingScans.all(context).contains(dir))
            assertEquals(before, snapshot(dir).filterKeys { it != PendingScans.REVIEW_STATE_FILE })
        }
    }

    @Test fun `sync torn temp and rename failures keep prior state and source pages`() {
        val dir = stage(5)
        PendingScans.writeReviewState(dir, state)
        val before = snapshot(dir)
        for (renameFailure in listOf(false, true)) {
            Durable.beforeFileSync = { tmp ->
                if (tmp.name == PendingScans.REVIEW_STATE_FILE + ".tmp") {
                    if (renameFailure) { tmp.delete(); tmp.mkdir() }
                    else { tmp.writeText("torn"); throw IOException("injected sync failure") }
                }
            }
            assertThrows(IOException::class.java) { PendingScans.writeReviewState(dir, state.copy(title = "new")) }
            assertEquals(state, PendingScans.readReviewState(dir))
            assertEquals(before, snapshot(dir))
        }
    }

    @Test fun `simulated process interruption ignores uncommitted temp record`() {
        val dir = stage()
        PendingScans.writeReviewState(dir, state)
        class ProcessDeath : Error()
        Durable.beforeFileSync = { if (it.name.endsWith(".tmp")) { it.writeText("torn"); throw ProcessDeath() } }
        assertThrows(ProcessDeath::class.java) { PendingScans.writeReviewState(dir, state.copy(title = "new")) }
        assertTrue(File(dir, PendingScans.REVIEW_STATE_FILE + ".tmp").exists())
        assertEquals(state, PendingScans.readReviewState(dir))
        Durable.resetForTests()
        PendingScans.writeReviewState(dir, state.copy(title = "retry"))
        assertEquals("retry", PendingScans.readReviewState(dir)!!.title)
    }

    @Test fun `queued older snapshots cannot overwrite newer state across activity instances`() {
        val dir = stage()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Durable.beforeFileSync = {
            if (it.name == PendingScans.REVIEW_STATE_FILE + ".tmp") {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val first = PendingScans.queueReviewState(dir, state)
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val skipped = PendingScans.queueReviewState(dir, state.copy(title = "intermediate"))
            val latest = state.copy(title = "latest", category = CategoryRules.FORMS)
            val last = PendingScans.queueReviewState(dir, latest)
            assertEquals(latest, PendingScans.latestReviewState(dir))
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertFalse(skipped.get(5, TimeUnit.SECONDS))
            assertTrue(last.get(5, TimeUnit.SECONDS))
            assertEquals(latest, PendingScans.readReviewState(dir))
        } finally { release.countDown() }
    }

    @Test fun `late flush never recreates discarded staging or publishes documents`() {
        val dir = stage()
        val docsBefore = ScanRepository(context).listDocuments().map { it.id }
        dir.deleteRecursively()
        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            PendingScans.queueReviewState(dir, state).get(5, TimeUnit.SECONDS)
        }
        assertFalse(dir.exists())
        assertEquals(docsBefore, ScanRepository(context).listDocuments().map { it.id })
    }

    @Test fun `review state leaves identity filters and journal intact and resumed save replays once`() = runBlocking {
        val dir = stage(5)
        File(dir, "filters.txt").writeText("page_000.jpg=${ScanFilter.BLACKWHITE.id}")
        val identity = PendingScans.identity(dir)
        val before = snapshot(dir)
        PendingScans.writeReviewState(dir, state)
        assertEquals(before, snapshot(dir).filterKeys { it != PendingScans.REVIEW_STATE_FILE })
        assertEquals(identity!!.source, PendingScans.identity(dir)!!.source)
        assertEquals(identity.filters, PendingScans.identity(dir)!!.filters)
        val restored = PendingScans.readReviewState(dir)!!
        val repo = ScanRepository(context)
        val uris = PendingScans.pages(dir).map { Uri.fromFile(it) }
        val filters = (0..4).associateWith { if (it == 0) ScanFilter.BLACKWHITE else ScanFilter.ORIGINAL }
        val doc = repo.saveScan(uris, null, restored.title.trim(), pageFilters = filters,
            category = restored.category, reviewDir = dir)
        val journal = File(dir, SaveFormat.JOURNAL).readBytes()
        PendingScans.writeReviewState(dir, restored)
        val replay = repo.saveScan(uris, null, restored.title.trim(), pageFilters = filters,
            category = restored.category, reviewDir = dir)
        assertEquals(doc.id, replay.id)
        assertEquals(restored.title.trim(), replay.name)
        assertEquals(CategoryRules.STUDY, replay.category)
        assertEquals(1, repo.listDocuments().count { it.id == doc.id })
        assertArrayEquals(journal, File(dir, SaveFormat.JOURNAL).readBytes())
    }
}
