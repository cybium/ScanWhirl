package com.scanwhirl.app.util

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** LX: the writer changes nothing without links, and an independent parser reads what it adds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfLinkWriterTest {

    @get:Rule val folder = TemporaryFolder()

    private fun images(count: Int): List<File> = (0 until count).map { i ->
        File(folder.root, "page_$i.jpg").apply {
            writeBytes(ImportFixtures.image(width = 300, height = 400))
        }
    }

    private val links = listOf(
        PdfGenerator.PdfLink(0, "Chapter 3", 2, PdfGenerator.PdfLinkRect(20, 30, 180, 55)),
        PdfGenerator.PdfLink(0, "३. गति", 1, PdfGenerator.PdfLinkRect(20, 70, 180, 95))
    )

    @Test
    fun `output without links is byte for byte identical`() {
        val pages = images(3)
        val plain = File(folder.root, "plain.pdf")
        val explicit = File(folder.root, "explicit.pdf")
        PdfGenerator.imagesToPdf(pages, plain)
        PdfGenerator.imagesToPdf(pages, explicit, links = emptyList())
        assertArrayEquals(plain.readBytes(), explicit.readBytes())
    }

    @Test
    fun `PDFBox reads the outline and link destinations`() {
        val out = File(folder.root, "linked.pdf")
        PdfGenerator.imagesToPdf(images(3), out, links = links)

        Loader.loadPDF(out).use { doc ->
            val items = doc.documentCatalog.documentOutline.children().toList()
            assertEquals(2, items.size)
            assertEquals("Chapter 3", items[0].title)
            assertEquals(2, doc.pages.toList().indexOf((items[0].destination as PDPageDestination).page))
            assertEquals("३. गति", items[1].title)
            assertEquals(1, doc.pages.toList().indexOf((items[1].destination as PDPageDestination).page))

            val annotations = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
            assertEquals(2, annotations.size)
            val first = annotations[0].destination as PDPageDestination
            val second = annotations[1].destination as PDPageDestination
            assertEquals(2, doc.pages.toList().indexOf(first.page))
            assertEquals(1, doc.pages.toList().indexOf(second.page))
            assertTrue(annotations[0].rectangle.width > 0f)
            assertTrue(annotations[0].rectangle.height > 0f)
        }
    }

    @Test
    fun `links to a missing page are refused before writing`() {
        val out = File(folder.root, "bad.pdf")
        assertThrows(IllegalArgumentException::class.java) {
            PdfGenerator.imagesToPdf(
                images(2), out,
                links = listOf(PdfGenerator.PdfLink(0, "Bad", 9, PdfGenerator.PdfLinkRect(1, 1, 2, 2)))
            )
        }
        assertTrue(!out.exists() || !File(out.path + ".tmp").exists())
    }

    @Test
    fun `links are refused for PDF A and encrypted output`() {
        val pdfa = PdfA.Mode(ByteArray(256), "title", 0L, documentId = "0123456789abcdef0123456789abcdef")
        val encryption = PdfAes.Encryption(
            fileKey = ByteArray(32), o = ByteArray(48), u = ByteArray(48), oe = ByteArray(32),
            ue = ByteArray(32), perms = ByteArray(16), permissions = -1, fileId = ByteArray(16)
        )
        assertThrows(IllegalArgumentException::class.java) {
            PdfGenerator.imagesToPdf(images(2), File(folder.root, "a.pdf"), pdfa = pdfa, links = links)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfGenerator.imagesToPdf(
                images(2), File(folder.root, "e.pdf"), encryption = encryption, links = links
            )
        }
    }
}
