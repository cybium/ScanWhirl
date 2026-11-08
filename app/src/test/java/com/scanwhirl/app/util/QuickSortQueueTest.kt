package com.scanwhirl.app.util

import com.scanwhirl.app.model.ScanDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T4.4 — the pure Quick Sort queue (plan §4.8): built from the library's
 * Unsorted + Other docs, pick hands the document back for an immediate
 * save, skip leaves it alone. The plan's own case lives in
 * [the plan case — skip n times, close, `remaining() == total − n`]:
 * skip() n times → close → `remaining() == total − n`, none categorized.
 */
class QuickSortQueueTest {

    @Test
    fun `only unsorted and other docs join the queue`() {
        val docs = listOf(
            doc("1", CategoryRules.UNSORTED),
            doc("2", CategoryRules.STUDY),
            doc("3", CategoryRules.OTHER),
            doc("4", CategoryRules.RECEIPTS)
        )
        val queue = QuickSortQueue.from(docs)
        assertEquals("the deliberate categories stay out", 2, queue.total)
        assertEquals("library order is kept", "1", queue.current()?.id)
    }

    @Test
    fun `pick hands the document back and advances`() {
        val queue = QuickSortQueue.from(
            listOf(doc("1", CategoryRules.UNSORTED), doc("2", CategoryRules.OTHER))
        )

        assertEquals("1", queue.pick(CategoryRules.STUDY)?.id)
        assertEquals("the picked doc leaves the queue", 1, queue.remaining())
        assertEquals("2", queue.current()?.id)

        assertEquals("2", queue.pick(CategoryRules.RECEIPTS)?.id)
        assertNull(queue.current())
        assertEquals(0, queue.remaining())
        // An empty queue is a no-op, never a crash (the sheet's X can race
        // the last pick).
        assertNull(queue.pick(CategoryRules.STUDY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pick refuses values that are not categories`() {
        QuickSortQueue.from(listOf(doc("1", CategoryRules.UNSORTED)))
            .pick(CategoryRules.UNSORTED)
    }

    @Test
    fun `the plan case - skip n times then close leaves the rest untouched`() {
        val docs = listOf(
            doc("1", CategoryRules.UNSORTED),
            doc("2", CategoryRules.UNSORTED),
            doc("3", CategoryRules.OTHER),
            doc("4", CategoryRules.UNSORTED),
            doc("5", CategoryRules.STUDY) // deliberately categorized: not queued
        )
        val queue = QuickSortQueue.from(docs)
        val total = queue.total
        val before = docs.associate { it.id to it.category }

        repeat(2) { queue.skip() }
        // "Close" = drop the queue mid-run (Rev 4 §3D: no confirmation).
        assertEquals("remaining == total - n", total - 2, queue.remaining())
        assertEquals("the queue walks on in order", "3", queue.current()?.id)
        // None categorized: skipping (or closing) never writes anything —
        // the queue hands a document back only through pick(), and nothing
        // was picked here.
        assertTrue(
            "every doc keeps the category it started with",
            docs.all { it.category == before[it.id] }
        )
    }

    @Test
    fun `progress reports position over the opening size`() {
        val queue = QuickSortQueue.from(
            listOf(
                doc("1", CategoryRules.UNSORTED),
                doc("2", CategoryRules.UNSORTED),
                doc("3", CategoryRules.UNSORTED)
            )
        )
        assertEquals(1, queue.progress().position)
        assertEquals(3, queue.progress().total)

        queue.skip()
        assertEquals("the second document is 2 of 3", 2, queue.progress().position)

        queue.pick(CategoryRules.STUDY)
        assertEquals("the last document is 3 of 3", 3, queue.progress().position)
        assertEquals(2, queue.done())
        assertEquals(1, queue.remaining())
    }

    @Test
    fun `an empty queue opens straight onto all sorted`() {
        val queue = QuickSortQueue.from(listOf(doc("1", CategoryRules.STUDY)))
        assertEquals(0, queue.total)
        assertNull(queue.current())
        assertNull(queue.skip())
        // position is meaningless with nothing on screen (total + done is).
        assertEquals(0, queue.done())
        assertEquals(0, queue.progress().total)
    }

    private fun doc(id: String, category: String) = ScanDocument(
        id = id,
        name = "Doc $id",
        createdAt = 0L,
        pageCount = 1,
        pdfFile = File("unused.pdf"),
        pageFiles = emptyList(),
        category = category
    )
}
