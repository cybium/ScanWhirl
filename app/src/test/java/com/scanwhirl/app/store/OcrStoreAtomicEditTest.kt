package com.scanwhirl.app.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrStoreAtomicEditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(context)
    private val store = OcrStore(repo.docsDir)
    private val id = "atomic_ocr_${System.nanoTime()}"
    private val dir = File(repo.docsDir, id).apply { mkdirs() }
    private val key = "page_000.jpg"
    private val other = "page_001.jpg"
    private val recognized = "कुल राशि\t1250\r\n😀 e\u0301"

    @After fun cleanup() { Durable.resetForTests(); dir.deleteRecursively() }

    private fun compare(expected: Map<String, String?>, updates: Map<String, String>) =
        store.updatePagesIfUnchanged(id, expected, updates)

    private fun conflicts(result: OcrStore.UpdateResult) = result.conflictingKeys

    private fun publish(text: String) {
        val source = File(dir, key).apply { writeText("fictional page image") }
        assertEquals(1, OcrStore(repo.docsDir).putRecognizedPages(id,
            listOf(OcrStore.Recognized(key, Sha256.of(source), text, null))).stored)
    }

    @Test fun `recognition between baseline capture and save conflicts without any writes`() {
        store.putTexts(id, mapOf(key to "old", other to "untouched"))
        val baseline = store.readText(id)
        store.invalidate(id, key)
        publish(recognized)
        val before = File(dir, "ocr.txt").readBytes()
        OcrStore.resetWriteCountForTests()
        val result = compare(baseline, mapOf(key to "manual edit", other to "other edit"))
        assertEquals(setOf(key), conflicts(result))
        assertEquals(recognized, store.readText(id)[key])
        assertEquals("untouched", store.readText(id)[other])
        assertArrayEquals(before, File(dir, "ocr.txt").readBytes())
        assertEquals(0, OcrStore.writeCountForTests())
    }

    @Test fun `absent baseline conflicts even when recognition creates empty text`() {
        val baseline = mapOf(key to store.readText(id)[key])
        publish("")
        assertEquals(setOf(key), conflicts(compare(baseline, mapOf(key to "manual"))))
        assertTrue(store.readText(id).containsKey(key))
        assertEquals("", store.readText(id)[key])
    }

    @Test fun `unchanged batch applies preserving unrelated text geometry and pending`() {
        val geometry = OcrPage(200, 300, listOf(OcrLine("line", 1, 2, 100, 20)))
        store.putPage(id, key, "", geometry)
        store.putPage(id, other, "untouched", geometry)
        store.markPending(id)
        val result = compare(mapOf(key to "", "page_002.jpg" to null),
            mapOf(key to recognized, "page_002.jpg" to "manual"))
        assertTrue(conflicts(result).isEmpty())
        assertEquals(mapOf(key to recognized, other to "untouched", "page_002.jpg" to "manual"),
            store.readText(id))
        assertEquals(mapOf(other to geometry), store.readGeo(id))
        assertTrue(store.isPending(id))
    }

    @Test fun `empty expected value is not an absent page`() {
        assertEquals(setOf(key), conflicts(compare(mapOf(key to ""), mapOf(key to "edit"))))
        assertTrue(store.readText(id).isEmpty())
        assertFalse(File(dir, "ocr.txt").exists())
    }

    @Test fun `missing expectation and missing document never write`() {
        assertThrows(IllegalArgumentException::class.java) {
            compare(emptyMap(), mapOf(key to "edit"))
        }
        dir.deleteRecursively()
        assertNull(repo.updateOcrPagesIfUnchanged(id, mapOf(key to null), mapOf(key to "edit")))
        assertThrows(IOException::class.java) {
            compare(mapOf(key to null), mapOf(key to "edit"))
        }
        assertFalse(dir.exists())
    }

    @Test fun `failed atomic save keeps previous text and geometry readable`() {
        val geometry = OcrPage(200, 300, listOf(OcrLine("old", 1, 2, 100, 20)))
        store.putPage(id, key, "old", geometry)
        Durable.beforeFileSync = { if (it.name == "ocr.txt.tmp") throw IOException("fictional full disk") }
        assertThrows(IOException::class.java) {
            repo.updateOcrPagesIfUnchanged(id, mapOf(key to "old"), mapOf(key to "edit"))
        }
        assertEquals(mapOf(key to "old"), store.readText(id))
        assertEquals(mapOf(key to geometry), store.readGeo(id))
    }
}
