package com.scanwhirl.app.store

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrStoreTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val root = File(context.filesDir, "ocrstore_test").apply { mkdirs() }

    private fun freshId() = "doc_${System.nanoTime()}"

    private fun storeFor(id: String) = OcrStore(root)

    @Test
    fun `readText on missing doc returns empty`() {
        assertEquals(0, storeFor(freshId()).readText("nope").size)
    }

    // WP-04 regression: OcrStore previously used java.util.Base64 (API 26+),
    // which crashed on Android 7/8 (API 24/25). It now uses android.util.Base64.
    @Test
    fun `putTexts round-trips non-ASCII text including Hindi and symbols`() {
        val id = freshId()
        val store = storeFor(id)
        val hindiText = "नमस्ते दुनिया — ₹100 · © 2026"
        val multiScript = "日本語テスト\nكلمة عربية\nΕλληνικά"
        store.putTexts(id, mapOf(
            "page_000.jpg" to hindiText,
            "page_001.jpg" to multiScript
        ))
        val read = store.readText(id)
        assertEquals(hindiText, read["page_000.jpg"])
        assertEquals(multiScript, read["page_001.jpg"])
    }

    @Test
    fun `putTexts then readText round-trips`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf("page_000.jpg" to "hello\nworld", "page_001.jpg" to "second page"))
        val read = store.readText(id)
        assertEquals(2, read.size)
        assertEquals("hello\nworld", read["page_000.jpg"])
        assertEquals("second page", read["page_001.jpg"])
    }

    @Test
    fun `readText handles legacy no-header format`() {
        val id = freshId()
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, "ocr.txt").writeText("page_000.jpg\tfirst line only")
        val store = storeFor(id)
        assertEquals("first line only", store.readText(id)["page_000.jpg"])
    }

    @Test
    fun `readText preserves tabs and symbols inside encoded text`() {
        val id = freshId()
        val store = storeFor(id)
        val multi = "First line\nSecond line\n\nTabbed\tvalue & symbols"
        store.putTexts(id, mapOf("page_000.jpg" to multi, "page_001.jpg" to "Plain page"))
        assertEquals(multi, store.readText(id)["page_000.jpg"])
        assertEquals("Plain page", store.readText(id)["page_001.jpg"])
    }

    @Test
    fun `putPage round-trips text and geometry`() {
        val id = freshId()
        val store = storeFor(id)
        val page = OcrPage(320, 240, listOf(
            OcrLine("Hello world", 10, 20, 100, 40),
            OcrLine("Second line", 10, 50, 90, 70)
        ))
        store.putPage(id, "page_000.jpg", "Hello world\nSecond line", page)

        assertEquals("Hello world\nSecond line", store.readText(id)["page_000.jpg"])
        val geo = store.readGeo(id)["page_000.jpg"]
        assertNotNull(geo)
        assertEquals(320, geo!!.width)
        assertEquals(240, geo.height)
        assertEquals(2, geo.lines.size)
        assertEquals("Hello world", geo.lines[0].text)
        assertEquals(10, geo.lines[0].left)
        assertEquals(20, geo.lines[0].top)
    }

    @Test
    fun `putPage with null page removes geometry but keeps text`() {
        val id = freshId()
        val store = storeFor(id)
        val page = OcrPage(100, 100, listOf(OcrLine("text", 0, 0, 10, 10)))
        store.putPage(id, "page_000.jpg", "some text", page)
        store.putPage(id, "page_000.jpg", "updated text", null)

        assertEquals("updated text", store.readText(id)["page_000.jpg"])
        assertEquals(0, store.readGeo(id).size)
    }

    @Test
    fun `readGeo on missing doc returns empty`() {
        assertEquals(0, storeFor(freshId()).readGeo("nope").size)
    }

    @Test
    fun `afterDelete shifts remaining keys down`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf(
            "page_000.jpg" to "first",
            "page_001.jpg" to "second",
            "page_002.jpg" to "third"
        ))
        store.putPage(id, "page_000.jpg", "first", OcrPage(100, 100, listOf(OcrLine("first", 0, 0, 10, 10))))
        store.putPage(id, "page_001.jpg", "second", OcrPage(100, 100, listOf(OcrLine("second", 0, 0, 10, 10))))
        store.putPage(id, "page_002.jpg", "third", OcrPage(100, 100, listOf(OcrLine("third", 0, 0, 10, 10))))

        store.afterDelete(id, 1) // delete page_001

        val text = store.readText(id)
        assertEquals(2, text.size)
        assertEquals("first", text["page_000.jpg"])
        assertEquals("third", text["page_001.jpg"]) // shifted down
        assertNull(text["page_002.jpg"])

        val geo = store.readGeo(id)
        assertEquals(2, geo.size)
        assertEquals("first", geo["page_000.jpg"]!!.lines.first().text)
        assertEquals("third", geo["page_001.jpg"]!!.lines.first().text)
    }

    @Test
    fun `afterDelete last page just removes its key`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf("page_000.jpg" to "only"))

        store.afterDelete(id, 0)

        assertEquals(0, store.readText(id).size)
        assertEquals(0, store.readGeo(id).size)
    }

    @Test
    fun `afterMove reorders keys moving down`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf(
            "page_000.jpg" to "first",
            "page_001.jpg" to "second",
            "page_002.jpg" to "third"
        ))

        store.afterMove(id, 0, 2) // move first to position 2

        val text = store.readText(id)
        assertEquals("second", text["page_000.jpg"])
        assertEquals("third", text["page_001.jpg"])
        assertEquals("first", text["page_002.jpg"])
    }

    @Test
    fun `afterMove reorders keys moving up`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf(
            "page_000.jpg" to "first",
            "page_001.jpg" to "second",
            "page_002.jpg" to "third"
        ))

        store.afterMove(id, 2, 0) // move third to position 0

        val text = store.readText(id)
        assertEquals("third", text["page_000.jpg"])
        assertEquals("first", text["page_001.jpg"])
        assertEquals("second", text["page_002.jpg"])
    }

    @Test
    fun `invalidate removes key and marks pending`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf("page_000.jpg" to "text", "page_001.jpg" to "more"))

        store.invalidate(id, "page_000.jpg")

        assertEquals(1, store.readText(id).size)
        assertEquals("more", store.readText(id)["page_001.jpg"])
        assertTrue(store.isPending(id))
    }

    @Test
    fun `markPending isPending clearPending cycle`() {
        val id = freshId()
        val store = storeFor(id)
        assertFalse(store.isPending(id))
        store.markPending(id)
        assertTrue(store.isPending(id))
        store.clearPending(id)
        assertFalse(store.isPending(id))
    }

    @Test
    fun `hasText false for empty true for nonempty`() {
        val id = freshId()
        val store = storeFor(id)
        assertFalse(store.hasText(id))
        store.putTexts(id, mapOf("page_000.jpg" to "text"))
        assertTrue(store.hasText(id))
    }

    @Test
    fun `afterDelete then readText is consistent with putTexts`() {
        val id = freshId()
        val store = storeFor(id)
        store.putTexts(id, mapOf(
            "page_000.jpg" to "a",
            "page_001.jpg" to "b",
            "page_002.jpg" to "c"
        ))
        store.afterDelete(id, 1)
        // Re-adding should produce clean keys
        store.putTexts(id, mapOf("page_000.jpg" to "a", "page_001.jpg" to "c"))
        assertEquals(listOf("a", "c"), store.readText(id).values.toList())
    }
}
