package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadSaverTest {

    @Test
    fun `single page file name has no page suffix`() {
        assertEquals(
            "Receipt.jpg",
            DownloadSaver.fileNameFor("Receipt", 1, 1)
        )
    }

    @Test
    fun `multi page file names are numbered`() {
        assertEquals("Doc - page 1.jpg", DownloadSaver.fileNameFor("Doc", 1, 3))
        assertEquals("Doc - page 3.jpg", DownloadSaver.fileNameFor("Doc", 3, 3))
    }

    @Test
    fun `unsafe characters are stripped from file names`() {
        assertEquals(
            "a_b_c.jpg",
            DownloadSaver.fileNameFor("a/b:c*?", 1, 1)
        )
    }

    @Test
    fun `uniqueName keeps candidate when free`() {
        assertEquals(
            "Doc.jpg",
            DownloadSaver.uniqueName("Doc.jpg") { false }
        )
    }

    @Test
    fun `uniqueName appends counter until free`() {
        val taken = setOf("Doc.jpg", "Doc (2).jpg")
        assertEquals(
            "Doc (3).jpg",
            DownloadSaver.uniqueName("Doc.jpg") { it in taken }
        )
    }

    @Test
    fun `uniqueName handles names without extension`() {
        val taken = setOf("Document 1")
        assertEquals(
            "Document 1 (2)",
            DownloadSaver.uniqueName("Document 1") { it in taken }
        )
    }

    @Test
    fun `subdir constant matches store listing folder`() {
        assertEquals("ScanWhirl", DownloadSaver.SUBDIR)
        assertTrue(DownloadSaver.SUBDIR.isNotBlank())
    }

    @Test
    fun `pdf names keep the title and add the extension`() {
        assertEquals("Document 1.pdf", DownloadSaver.pdfNameFor("Document 1"))
        assertEquals("My Scan.pdf", DownloadSaver.pdfNameFor("My Scan"))
    }

    @Test
    fun `pdf names are sanitized like image names`() {
        assertEquals("a_b_c.pdf", DownloadSaver.pdfNameFor("a/b:c"))
        assertEquals("Document.pdf", DownloadSaver.pdfNameFor("   "))
    }
}
