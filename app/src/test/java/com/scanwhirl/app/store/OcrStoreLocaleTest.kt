package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * H4-3: `pageKeyFor` used `"page_%03d.jpg".format(index)` with the default locale, so on a phone
 * whose FORMAT locale writes non-ASCII digits (mr-IN, ar, …) a delete/reorder rewrote the OCR
 * store keys as `page_००५.jpg`. Those keys match no page file, so every later page lost its
 * recognised text and page-level search hits. Page names on disk are always ASCII
 * (`String.format(Locale.ROOT, …)`), so the store keys must be too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrStoreLocaleTest {

    private lateinit var app: Application
    private lateinit var root: File
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.filesDir, "f9text_ocrlocale_${System.nanoTime()}").apply { mkdirs() }
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("mr-IN"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        root.deleteRecursively()
    }

    private fun pageOf(text: String) =
        OcrPage(100, 100, listOf(OcrLine(text, 1, 1, 50, 20)))

    private fun store() = OcrStore(root)

    @Test
    fun `after delete shifts keys to ascii page names`() {
        val id = "doc_delete"
        val store = store()
        store.putPage(id, "page_001.jpg", "one", pageOf("one"))
        store.putPage(id, "page_002.jpg", "two", pageOf("two"))

        store.afterDelete(id, 0)

        assertEquals(
            "keys must stay ASCII whatever the device locale writes for %03d",
            setOf("page_000.jpg", "page_001.jpg"),
            store.readText(id).keys
        )
        assertEquals(setOf("page_000.jpg", "page_001.jpg"), store.readGeo(id).keys)
        assertEquals("one", store.readText(id)["page_000.jpg"])
        assertEquals("two", store.readText(id)["page_001.jpg"])
    }

    @Test
    fun `after move keeps ascii page names`() {
        val id = "doc_move"
        val store = store()
        store.putPage(id, "page_000.jpg", "zero", pageOf("zero"))
        store.putPage(id, "page_001.jpg", "one", pageOf("one"))

        store.afterMove(id, 0, 1)

        assertEquals(setOf("page_000.jpg", "page_001.jpg"), store.readText(id).keys)
        assertEquals("one", store.readText(id)["page_000.jpg"])
        assertEquals("zero", store.readText(id)["page_001.jpg"])
        assertEquals(setOf("page_000.jpg", "page_001.jpg"), store.readGeo(id).keys)
    }
}
