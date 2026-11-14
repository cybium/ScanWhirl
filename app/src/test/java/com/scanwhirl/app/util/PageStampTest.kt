package com.scanwhirl.app.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * L5 Phase A: PageStamp bakes vector marks into page pixels in a NEW document copy.
 *
 * The data-safety test is [stamping leaves the original document byte identical]; the
 * privacy test is [redaction removes that page's ocr text and kept original].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    @After
    fun reset() = Durable.resetForTests()

    // ---------------------------------------------------------------- helpers

    private fun white(width: Int = 600, height: Int = 800): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

    private fun gray(c: Int): Int =
        (Color.red(c) * 77 + Color.green(c) * 151 + Color.blue(c) * 28) shr 8

    private fun minGray(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.minOf { gray(it) }
    }

    private fun darkCount(bmp: Bitmap, rect: PageStamp.Box): Int {
        val left = (rect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val right = (rect.right * bmp.width).toInt().coerceIn(left + 1, bmp.width)
        val top = (rect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val bottom = (rect.bottom * bmp.height).toInt().coerceIn(top + 1, bmp.height)
        val px = IntArray((right - left) * (bottom - top))
        bmp.getPixels(px, 0, right - left, left, top, right - left, bottom - top)
        return px.count { gray(it) < 170 }
    }

    private fun assertChannel(expected: Int, actual: Int, tolerance: Int, what: String) {
        assertTrue(
            "$what: expected $expected Â± $tolerance but got $actual",
            abs(expected - actual) <= tolerance
        )
    }

    // ---------------------------------------------------------------- redaction

    @Test
    fun `redact paints every pixel in its box with the fill colour`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 2)
        val box = PageStamp.Box(0.05f, 0.05f, 0.60f, 0.50f)
        val out = repo.stampDocument(
            id,
            listOf(PageStamp.PageEdits(0, listOf(PageStamp.Mark.Redact(box, Color.BLACK)))),
            null
        )
        val page = requireNotNull(out.pageFiles.firstOrNull())
        val bmp = requireNotNull(BitmapFactory.decodeFile(page.path))
        try {
            // Inset so JPEG ringing at the box border is not measured.
            val insetX = (bmp.width * 0.02f).toInt().coerceAtLeast(2)
            val insetY = (bmp.height * 0.02f).toInt().coerceAtLeast(2)
            val left = (box.left * bmp.width).toInt() + insetX
            val right = (box.right * bmp.width).toInt() - insetX
            val top = (box.top * bmp.height).toInt() + insetY
            val bottom = (box.bottom * bmp.height).toInt() - insetY
            for (y in top until bottom) {
                for (x in left until right) {
                    val c = bmp.getPixel(x, y)
                    assertChannel(0, Color.red(c), 20, "redacted pixel ($x,$y)")
                    assertChannel(0, Color.green(c), 20, "redacted pixel ($x,$y)")
                    assertChannel(0, Color.blue(c), 20, "redacted pixel ($x,$y)")
                }
            }
            // A pixel outside the box is still page content, so the box was placed, not the page blanked.
            val outside = bmp.getPixel((bmp.width * 0.9f).toInt(), (bmp.height * 0.9f).toInt())
            assertTrue("outside the box must keep page content", gray(outside) > 60)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `redaction removes that page's ocr text and kept original`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 2)
        DocFixtures.withOcr(repo, id)
        // A filter creates the kept original that must not survive redaction.
        repo.setPageFilter(id, 0, ScanFilter.ENHANCE)
        val sourceOrig = File(File(repo.docsDir, id), "page_000.orig")
        assertTrue("fixture needs a kept original", sourceOrig.isFile)
        assertTrue("fixture needs source OCR", "page_000.jpg" in repo.ocrDoc(id))
        assertTrue("fixture needs a text layer", File(File(repo.docsDir, id), "text_layer.flag").isFile)

        val out = repo.stampDocument(
            id,
            listOf(
                PageStamp.PageEdits(
                    0,
                    listOf(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.5f, 0.3f)))
                )
            ),
            null
        )

        val outDir = File(repo.docsDir, out.id)
        val text = repo.ocrDoc(out.id)
        assertFalse("the redacted page's recognised text must be gone", "page_000.jpg" in text)
        assertTrue("other pages keep their text", "page_001.jpg" in text)
        assertFalse("no kept original may carry the unredacted pixels", File(outDir, "page_000.orig").exists())
        assertTrue("the redacted page is queued for recognition", File(outDir, "ocr_pending").isFile)
        assertFalse("the text layer for the redacted page was dropped", File(outDir, "text_layer.flag").exists())
        // The source is immutable: its OCR and original are untouched.
        assertTrue("source OCR untouched", "page_000.jpg" in repo.ocrDoc(id))
        assertTrue("source original untouched", sourceOrig.isFile)
    }

    @Test
    fun `stamping leaves the original document byte identical`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 2)
        DocFixtures.withOcr(repo, id)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        val beforeName = requireNotNull(repo.getDocument(id)).name

        val out = repo.stampDocument(
            id,
            listOf(
                PageStamp.PageEdits(
                    1,
                    listOf(
                        PageStamp.Mark.Text("APPROVED", PageStamp.Box(0.1f, 0.1f, 0.8f, 0.2f)),
                        PageStamp.Mark.Watermark("COPY", opacity = 0.15f)
                    )
                )
            ),
            null
        )

        assertEquals("the source directory must not change at all", before, PdfInvariants.dirDigest(File(repo.docsDir, id)))
        assertEquals(beforeName, requireNotNull(repo.getDocument(id)).name)
        assertTrue("a separate document was created", out.id != id)
        assertTrue("the copy is named after its edit", out.name.endsWith("(watermarked)"))
        PdfInvariants.assertI1(repo, out.id)
    }

    // ---------------------------------------------------------------- mark rendering

    @Test
    fun `watermark opacity is measurable`() {
        val faint = white()
        PageStamp.render(
            faint,
            listOf(PageStamp.Mark.Watermark("CONFIDENTIAL", color = Color.BLACK, opacity = 0.25f)),
            1, 1
        )
        val faintMin = minGray(faint)
        faint.recycle()

        val strong = white()
        PageStamp.render(
            strong,
            listOf(PageStamp.Mark.Watermark("CONFIDENTIAL", color = Color.BLACK, opacity = 0.60f)),
            1, 1
        )
        val strongMin = minGray(strong)
        strong.recycle()

        // Black ink at 25% over white is about 191 gray; at 60% about 102.
        assertTrue("25% watermark must be visible but light (got $faintMin)", faintMin in 176..210)
        assertTrue("60% watermark must be clearly darker (got $strongMin)", strongMin < faintMin - 50)
    }

    @Test
    fun `signature ink is drawn inside its box only`() {
        val bmp = white()
        try {
            val box = PageStamp.Box(0.25f, 0.25f, 0.65f, 0.45f)
            PageStamp.render(
                bmp,
                listOf(
                    PageStamp.Mark.Signature(
                        strokes = listOf(
                            PageStamp.Stroke(
                                listOf(
                                    PageStamp.Point(0f, 0.8f),
                                    PageStamp.Point(0.3f, 0.1f),
                                    PageStamp.Point(0.6f, 0.9f),
                                    PageStamp.Point(1f, 0.2f)
                                )
                            )
                        ),
                        box = box,
                        width = 0.012f
                    )
                ),
                1, 1
            )
            assertTrue("the ink must be visible", darkCount(bmp, box) > 20)
            assertEquals("nothing may be drawn in the page corner", 0, darkCount(bmp, PageStamp.Box(0f, 0f, 0.2f, 0.15f)))
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `text freehand highlight badge and page number all mark the page`() {
        val marks = listOf(
            PageStamp.Mark.Text("SELF-ATTESTED", PageStamp.Box(0.1f, 0.05f, 0.9f, 0.14f)),
            PageStamp.Mark.Freehand(
                listOf(
                    PageStamp.Stroke(
                        listOf(
                            PageStamp.Point(0.1f, 0.3f),
                            PageStamp.Point(0.9f, 0.35f)
                        )
                    )
                ),
                width = 0.008f
            ),
            PageStamp.Mark.Highlight(PageStamp.Box(0.1f, 0.5f, 0.6f, 0.55f)),
            PageStamp.Mark.Badge(
                PageStamp.BadgeKind.PAID,
                PageStamp.Box(0.55f, 0.6f, 0.95f, 0.72f),
                date = "14 Nov 2026"
            ),
            PageStamp.Mark.PageNumber(PageStamp.Position.BOTTOM_RIGHT, "%d / %d", 1)
        )
        marks.forEach { mark ->
            val bmp = white()
            try {
                PageStamp.render(bmp, listOf(mark), pageNumber = 2, pageCount = 7)
                val changed = countNonWhite(bmp)
                assertTrue("${mark.javaClass.simpleName} drew nothing", changed > 10)
            } finally {
                bmp.recycle()
            }
        }
    }

    @Test
    fun `page number uses the requested format and position`() {
        val bmp = white()
        try {
            PageStamp.render(
                bmp,
                listOf(PageStamp.Mark.PageNumber(PageStamp.Position.BOTTOM_CENTER, "Page %d of %d", 1)),
                pageNumber = 2, pageCount = 3
            )
            assertTrue("page number must be at the bottom", darkCount(bmp, PageStamp.Box(0.2f, 0.88f, 0.8f, 1f)) > 10)
            assertEquals("nothing at the top", 0, darkCount(bmp, PageStamp.Box(0f, 0f, 1f, 0.6f)))
        } finally {
            bmp.recycle()
        }
    }

    private fun countNonWhite(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { it != Color.WHITE }
    }

    // ---------------------------------------------------------------- file/page limits

    @Test
    fun `a page is sampled to at most 3000 px before drawing`() {
        val page = tmp.newFile("page_000.jpg")
        page.writeBytes(ImportFixtures.image(width = 3200, height = 320))
        PageStamp.applyPage(
            page,
            listOf(PageStamp.Mark.Text("MARK", PageStamp.Box(0.1f, 0.1f, 0.9f, 0.2f))),
            1, 1
        )
        val (w, h) = requireNotNull(ImageTools.imageSize(page.readBytes()))
        assertTrue("width $w must be sampled to 3000 or less", w <= 3000)
        assertTrue("height $h stays sane", h in 1..3000)
        assertEquals(0, page.parentFile!!.list()!!.count { it.contains(".swtmp") || it.contains(".swbak") })
    }

    @Test
    fun `a locked source is refused before any copy is made`() = runBlocking {
        val id = DocFixtures.locked(repo, context)
        val countBefore = repo.listDocuments(includeVaulted = true).size
        try {
            repo.stampDocument(
                id,
                listOf(PageStamp.PageEdits(0, listOf(PageStamp.Mark.Text("X", PageStamp.Box(0.1f, 0.1f, 0.5f, 0.2f))))),
                null
            )
            fail("a locked document must not be stamped into a plain copy")
        } catch (_: Exception) {
        }
        assertEquals(countBefore, repo.listDocuments(includeVaulted = true).size)
    }

    @Test
    fun `a failed page swap publishes nothing and leaves the source intact`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 2)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        val countBefore = repo.listDocuments().size
        Durable.renameSeam = { from, to -> if (to.name == "page_000.jpg") false else from.renameTo(to) }

        try {
            repo.stampDocument(
                id,
                listOf(PageStamp.PageEdits(0, listOf(PageStamp.Mark.Text("X", PageStamp.Box(0.1f, 0.1f, 0.5f, 0.2f))))),
                null
            )
            fail("a failed swap must be reported")
        } catch (_: Exception) {
        }

        assertEquals("no half-edited copy may be visible", countBefore, repo.listDocuments().size)
        assertEquals("the source must be byte-identical", before, PdfInvariants.dirDigest(File(repo.docsDir, id)))
    }

    @Test
    fun `an empty edit list is refused`() = runBlocking {
        val id = DocFixtures.saved(repo, context, pages = 1)
        try {
            repo.stampDocument(id, emptyList(), null)
            fail("nothing to draw must be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, repo.listDocuments().size)
    }
}
