package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * v2 UI: 03_tools_hub — a 2000-page import renders one page at a time into private staging and
 * respects cancellation; choosing a range renders only those pages, renumbered from page 0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfImportRenderTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class FakeSource(
        private val count: Int,
        private val failAt: Int? = null,
        private val cancelAt: Int? = null
    ) : PdfImport.PageSource {
        val rendered = mutableListOf<Int>()

        override fun pageCount(): Int = count

        override fun render(index: Int): ByteArray {
            if (cancelAt != null && index >= cancelAt) throw CancellationException("cancelled at $index")
            if (failAt != null && index == failAt) throw IllegalStateException("injected render failure")
            rendered += index
            return ByteArray(1024) { 7 }
        }
    }

    @After
    fun tearDown() {
        PdfImport.resetForTests()
    }

    @Test
    fun `two thousand pages render one by one without holding the set in memory`() {
        runBlocking {
            val dir = File(app.cacheDir, "import_2000").apply { mkdirs() }
            val source = FakeSource(2000)
            var lastDone = 0
            var reports = 0
            PdfImport.renderPages(
                source, dir, (0 until 2000).toList(),
                freeBytes = 10L * 1024 * 1024 * 1024
            ) { done, total ->
                lastDone = done
                assertEquals(2000, total)
                reports++
            }
            assertEquals(2000, dir.listFiles()!!.size)
            assertEquals(2000, lastDone)
            assertEquals(2000, reports)
            assertEquals(2000, source.rendered.size)
            assertEquals((0 until 2000).toList(), source.rendered)
            assertTrue("pages are named page_000.jpg…", File(dir, "page_000.jpg").exists())
            assertTrue(File(dir, "page_1999.jpg").exists())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a chosen range renders only those pages and renumbers them`() {
        runBlocking {
            val dir = File(app.cacheDir, "import_range").apply { mkdirs() }
            val source = FakeSource(100)
            PdfImport.renderPages(
                source, dir, listOf(0, 1, 44),
                freeBytes = 5L * 1024 * 1024 * 1024
            )
            assertEquals(listOf(0, 1, 44), source.rendered)
            assertEquals(listOf("page_000.jpg", "page_001.jpg", "page_002.jpg"), dir.list()!!.sorted())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a render failure stops the pass and leaves staging for the caller to discard`() {
        runBlocking {
            val dir = File(app.cacheDir, "import_fail").apply { mkdirs() }
            val source = FakeSource(100, failAt = 5)
            val r = runCatching {
                PdfImport.renderPages(
                    source, dir, (0 until 100).toList(),
                    freeBytes = 5L * 1024 * 1024 * 1024
                )
            }
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull() is PdfImport.ImportException)
            assertEquals("five pages were written before the failure", 5, dir.listFiles()!!.size)
            dir.deleteRecursively()
            assertTrue(!dir.exists())
        }
    }

    @Test
    fun `cancellation propagates before a page is written`() {
        runBlocking {
            val dir = File(app.cacheDir, "import_cancel").apply { mkdirs() }
            val source = FakeSource(100, cancelAt = 3)
            val r = runCatching {
                PdfImport.renderPages(
                    source, dir, (0 until 100).toList(),
                    freeBytes = 5L * 1024 * 1024 * 1024
                )
            }
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull() is CancellationException)
            assertEquals(3, dir.listFiles()!!.size)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a page outside the PDF is refused`() {
        runBlocking {
            val dir = File(app.cacheDir, "import_bad").apply { mkdirs() }
            val r = runCatching {
                PdfImport.renderPages(
                    FakeSource(2), dir, listOf(0, 5),
                    freeBytes = 5L * 1024 * 1024 * 1024
                )
            }
            assertTrue(r.isFailure)
            assertEquals(0, dir.listFiles()!!.size)
            dir.deleteRecursively()
        }
    }
}
