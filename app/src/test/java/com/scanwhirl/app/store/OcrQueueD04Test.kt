package com.scanwhirl.app.store

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.model.OcrPage
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrQueueD04Test {
    private val repo = ScanRepository(ApplicationProvider.getApplicationContext())

    private fun doc(id: String): String {
        File(repo.docsDir, id).apply {
            mkdirs()
            File(this, "meta.txt").writeText("Fictional $id\n1\nOther\n")
            File(this, "scan.pdf").writeBytes(byteArrayOf(1))
            File(this, "page_000.jpg").writeBytes(byteArrayOf(1, 2, 3))
        }
        repo.ocrStore.markPending(id)
        return id
    }

    @After fun reset() = Durable.resetForTests()

    @Test fun `failed final persistence is not a completed pass and retries without spending recognition budget`() = runBlocking {
        val id = doc("persist")
        val completed = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val indexer = OcrIndexer(repo, scope, autoRun = false,
                recognize = { OcrEngine.OcrResult("fictional", OcrPage(0, 0, emptyList())) },
                afterIndexed = { completed.incrementAndGet() })
            repeat(4) {
                Durable.beforeFileSync = { file ->
                    if (file.name == "ocr.txt.tmp") throw IOException("injected")
                }
                val failure = runCatching { indexer.index(id) }.exceptionOrNull()
                assertNotNull("a failed durable commit must not return a success report", failure)
                assertTrue(repo.ocrStore.isPending(id))
                assertEquals(0, completed.get())
                assertFalse(indexer.retryExhausted(id))
            }
            Durable.resetForTests()
            assertEquals(1, indexer.index(id).pagesWithText)
            assertFalse(repo.ocrStore.isPending(id))
            assertEquals(1, completed.get())
        } finally { scope.cancel() }
    }

    @Test fun `enqueue during completion is processed again when the first pass is superseded`() = runBlocking {
        val id = doc("superseded")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            val indexer = OcrIndexer(repo, scope, recognize = {
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    release.await()
                }
                OcrEngine.OcrResult("new image", OcrPage(0, 0, emptyList()))
            })
            indexer.enqueue(id)
            withTimeout(5000) { started.await() }
            File(repo.docsDir, "$id/page_000.jpg").writeBytes(byteArrayOf(4, 5, 6))
            indexer.enqueue(id)
            release.complete(Unit)
            withTimeout(5000) {
                while (repo.ocrStore.isPending(id)) delay(10)
            }
            assertEquals(2, calls.get())
            assertEquals("new image", repo.ocrDoc(id)["page_000.jpg"])
        } finally { scope.cancel() }
    }

    @Test fun `mid batch storage failures never consume the page image retry budget`() = runBlocking {
        val id = doc("midbatch")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        try {
            val indexer = OcrIndexer(repo, scope, autoRun = false, batchPages = 1,
                recognize = {
                    calls.incrementAndGet()
                    OcrEngine.OcrResult("fictional", OcrPage(0, 0, emptyList()))
                })
            repeat(4) {
                Durable.beforeFileSync = { file ->
                    if (file.name == "ocr_geo.txt.tmp") throw IOException("injected")
                }
                assertNotNull(runCatching { indexer.index(id) }.exceptionOrNull())
                assertTrue(repo.ocrStore.isPending(id))
                assertFalse(indexer.retryExhausted(id))
            }
            Durable.resetForTests()
            assertEquals(1, indexer.index(id).pagesWithText)
            assertEquals(5, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun `cancelled pass persists its partial batch and resumes only the missing page`() = runBlocking {
        val id = doc("cancelpass")
        File(repo.docsDir, "$id/page_001.jpg").writeBytes(byteArrayOf(2))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val indexer = OcrIndexer(repo, scope, autoRun = false, recognize = {
                if (it.name == "page_001.jpg") throw CancellationException("caller cancelled")
                OcrEngine.OcrResult("first", OcrPage(0, 0, emptyList()))
            })
            assertTrue(runCatching { indexer.index(id) }.exceptionOrNull() is CancellationException)
            assertTrue(repo.ocrStore.isPending(id))
            assertEquals(mapOf("page_000.jpg" to "first"), repo.ocrDoc(id))
            val calls = AtomicInteger()
            val resumed = OcrIndexer(repo, scope, autoRun = false, recognize = {
                calls.incrementAndGet()
                OcrEngine.OcrResult("second", OcrPage(0, 0, emptyList()))
            })
            assertEquals(2, resumed.index(id).pagesWithText)
            assertEquals(1, calls.get())
            assertFalse(repo.ocrStore.isPending(id))
        } finally { scope.cancel() }
    }

    @Test fun `many enqueue callers and cancelled callers race completion without lost or duplicate recognition`() = runBlocking {
        val ids = (0 until 64).map { doc("stress$it") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        try {
            val indexer = OcrIndexer(repo, scope, recognize = {
                counts.computeIfAbsent(it.parentFile!!.name) { AtomicInteger() }.incrementAndGet()
                delay(1)
                OcrEngine.OcrResult("fictional", OcrPage(0, 0, emptyList()))
            })
            withContext(Dispatchers.Default) {
                (0 until 16).map { worker ->
                    launch {
                        repeat(8) { round ->
                            ids.drop(worker % 4).forEach { id ->
                                indexer.enqueue(id)
                                if (round % 2 == 0) {
                                    launch { indexer.enqueue(id) }.cancelAndJoin()
                                }
                            }
                        }
                    }
                }.joinAll()
            }
            withTimeout(15000) {
                while (ids.any { repo.ocrStore.isPending(it) }) delay(10)
            }
            ids.forEach {
                assertEquals("exactly one recognition for $it", 1, counts[it]?.get())
                assertEquals("fictional", repo.ocrDoc(it)["page_000.jpg"])
            }
        } finally { scope.cancel() }
    }
}
