package com.scanwhirl.app.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrPartialEditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(context)
    private val id = "ocr_partial_${System.nanoTime()}"
    private val dir = File(repo.docsDir, id).apply { mkdirs() }
    private val store get() = OcrStore(repo.docsDir)
    private fun key(i: Int) = "page_%03d.jpg".format(i)
    private fun geo(text: String) = OcrPage(200, 300, listOf(OcrLine(text, 1, 2, 100, 20)))
    private fun seed() {
        repeat(3) { store.putPage(id, key(it), "page $it\n\tनमस्ते", geo("line $it")) }
        store.markPending(id)
    }

    @After fun cleanup() { Durable.resetForTests(); dir.deleteRecursively() }

    @Test fun `repository middle edit preserves other text keys and geometry and pending`() {
        seed()
        val text = store.readText(id)
        val geometry = store.readGeo(id)
        val updated = repo.updateOcrPage(id, key(1), "corrected")!!
        assertEquals(text + (key(1) to "corrected"), updated)
        assertEquals(geometry - key(1), store.readGeo(id))
        assertTrue(store.isPending(id))
    }

    @Test fun `repeated empty Unicode and batch edits preserve exact logical text`() {
        seed()
        val original = store.readText(id)
        for (text in listOf("", "\t\r\n😀 e\u0301 日本語 العربية", "again")) {
            assertEquals(original + (key(1) to text), repo.updateOcrPages(id, mapOf(key(1) to text)))
        }
        assertEquals(3, repo.updateOcrPages(id, mapOf(key(0) to "a", key(2) to "c"))!!.size)
        assertTrue(store.readGeo(id).isEmpty())
    }

    @Test fun `independent stores concurrently merge edits and indexed pages without losing keys`() {
        seed()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0..2).map { worker -> executor.submit {
                val independent = store
                start.await()
                repeat(15) { n ->
                    if (worker == 2) independent.putPage(id, key(n + 3), "indexed $n", geo("indexed $n"))
                    else independent.updateTexts(id, mapOf(key(worker) to "edit $worker $n"))
                }
            } }
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(18, store.readText(id).size)
            assertEquals("edit 0 14", store.readText(id)[key(0)])
            assertEquals("edit 1 14", store.readText(id)[key(1)])
            assertEquals(16, store.readGeo(id).size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `failed sync torn temp and cancellation retain earlier readable text and geometry`() {
        seed()
        val texts = store.readText(id)
        val geometry = store.readGeo(id)
        val bytes = File(dir, "ocr.txt").readBytes()
        for (failure in listOf(IOException("simulated ENOSPC"), CancellationException("cancelled"))) {
            Durable.beforeFileSync = { file ->
                if (file.name == "ocr.txt.tmp") {
                    file.writeText("torn")
                    throw failure
                }
            }
            try { repo.updateOcrPage(id, key(1), "must not commit"); fail("false success") }
            catch (e: Exception) { assertTrue(e is IOException || e is CancellationException) }
            assertArrayEquals(bytes, File(dir, "ocr.txt").readBytes())
            assertEquals(texts, store.readText(id))
            assertEquals(geometry, store.readGeo(id))
            assertFalse(File(dir, "ocr.txt.tmp").exists())
        }
    }

    @Test fun `geometry write failure and blocked temp do not replace text`() {
        seed()
        val original = store.readText(id)
        Durable.beforeFileSync = { if (it.name == "ocr_geo.txt.tmp") throw IOException("geometry sync") }
        assertThrows(IOException::class.java) { repo.updateOcrPage(id, key(1), "bad") }
        Durable.resetForTests()
        File(dir, "ocr.txt.tmp").mkdir()
        assertThrows(IOException::class.java) { repo.updateOcrPage(id, key(1), "bad") }
        assertEquals(original, store.readText(id))
    }

    @Test fun `rename failure never falls back to truncating the readable target`() {
        seed()
        val original = File(dir, "ocr.txt").readBytes()
        Durable.beforeFileSync = { tmp ->
            if (tmp.name == "ocr.txt.tmp") {
                // The already-open descriptor remains valid; renaming a directory
                // over an existing file must fail on the actual host filesystem.
                assertTrue(tmp.delete())
                assertTrue(tmp.mkdir())
            }
        }
        assertThrows(IOException::class.java) { store.updateTexts(id, mapOf(key(1) to "bad")) }
        assertArrayEquals(original, File(dir, "ocr.txt").readBytes())
    }

    @Test fun `failed index text commit retains old text and geometry pair`() {
        seed()
        val texts = store.readText(id)
        val geometry = store.readGeo(id)
        Durable.beforeFileSync = { if (it.name == "ocr.txt.tmp") throw IOException("text sync") }
        assertThrows(IOException::class.java) { store.putPage(id, key(1), "new OCR", geo("new OCR")) }
        assertEquals(texts, store.readText(id))
        assertEquals(geometry, store.readGeo(id))
    }

    @Test fun `legacy geometry binds before edit and only edited page becomes stale`() {
        seed()
        // Strip the optional digest to simulate an existing v1 geometry file.
        val file = File(dir, "ocr_geo.txt")
        file.writeText(file.readLines().mapIndexed { index, line ->
            if (index == 0) line else {
                val parts = line.split('\t')
                val decoded = String(android.util.Base64.decode(parts[1], 0))
                parts[0] + "\t" + android.util.Base64.encodeToString(
                    decoded.substringBefore("#text-sha256").toByteArray(), android.util.Base64.NO_WRAP)
            }
        }.joinToString("\n"))
        val original = store.readGeo(id)
        repo.updateOcrPage(id, key(1), "user correction")
        assertEquals(original - key(1), store.readGeo(id))
    }

    @Test fun `sparse OCR survives delete and move remapping`() {
        store.putPage(id, key(2), "last", geo("last"))
        store.afterDelete(id, 0)
        assertEquals(mapOf(key(1) to "last"), store.readText(id))
        store.afterMove(id, 1, 0)
        assertEquals(mapOf(key(0) to "last"), store.readText(id))
        assertEquals(mapOf(key(0) to geo("last")), store.readGeo(id))
    }

    @Test fun `recognizer cannot overwrite a concurrent correction or resurrect deleted page`() {
        val source = File(dir, key(0)).apply { writeText("synthetic image") }
        val hash = Sha256.of(source)
        repo.updateOcrPage(id, key(0), "manual")
        assertTrue(store.putRecognizedPage(id, key(0), hash, "automatic", geo("automatic")))
        assertEquals("manual", store.readText(id)[key(0)])
        source.writeText("rotated")
        assertFalse(store.putRecognizedPage(id, key(0), hash, "automatic", null))
        dir.deleteRecursively()
        assertFalse(store.putRecognizedPage(id, key(0), hash, "automatic", null))
        assertFalse(dir.exists())
    }

    @Test fun `malformed existing OCR is not replaced with a partial map`() {
        val file = File(dir, "ocr.txt").apply { writeText("#scanwhirl-ocr-v2\ninvalid") }
        assertThrows(IOException::class.java) { repo.updateOcrPage(id, key(1), "correction") }
        assertEquals("#scanwhirl-ocr-v2\ninvalid", file.readText())
    }
}
