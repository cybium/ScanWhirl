package com.scanwhirl.app.store

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.File

/**
 * Lane RS (R2 P1): an external/device PDF that would grow is rejected inside staging, before any
 * publish, with a durable NOT_SMALLER receipt. Fictional fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RsExternalCompressRejectTest {
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var pdf: File

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        val image = File(app.cacheDir, "rs-ext-page.jpg").apply {
            writeBytes(ImportFixtures.image(width = 80, height = 100))
        }
        pdf = File(app.cacheDir, "rs-ext-source.pdf")
        PdfGenerator.imagesToPdf(listOf(image), pdf)
    }

    @After fun tearDown() {
        ScanRepository.resetTestSeams()
        Faults.resetAll()
        OpFaults.reset()
    }

    @Implements(PdfRenderer::class)
    class ExtRenderer {
        @Implementation fun __constructor__(fd: ParcelFileDescriptor) = Unit
        @Implementation fun getPageCount(): Int = 1
        @Implementation fun openPage(index: Int): PdfRenderer.Page =
            Shadow.newInstanceOf(PdfRenderer.Page::class.java)
        @Implementation fun close() = Unit
    }

    @Implements(PdfRenderer.Page::class)
    class ExtPage {
        @Implementation fun getWidth(): Int = 80
        @Implementation fun getHeight(): Int = 100
        @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) {
            bitmap.eraseColor(Color.WHITE)
        }
        @Implementation fun close() = Unit
    }

    private fun assertRejected(ids: Set<String>, before: Long, target: Long?) {
        assertEquals("nothing published", ids, repo.docsDir.list().orEmpty().toSet())
        val receipt = requireNotNull(V2tResultReceiptStore(app).load())
        assertEquals("NOT_SMALLER", receipt.outcomeClass)
        assertTrue(receipt.docIds.isEmpty())
        assertTrue(receipt.files.isEmpty())
        assertTrue("external: no library source", receipt.receiptSourceId.isNullOrEmpty())
        assertEquals(before, receipt.beforeBytes)
        assertEquals(target, receipt.targetBytes)
    }

    @Test
    @Config(shadows = [ExtRenderer::class, ExtPage::class])
    fun externalSelectedImportThatGrowsPublishesNothing() = runBlocking {
        val ids = repo.docsDir.list().orEmpty().toSet()
        try {
            repo.importPdfAsScanSelected(
                Uri.fromFile(pdf), "Grown", indices = listOf(0), rejectNotSmallerThan = 1L
            )
            fail("expected NotSmaller")
        } catch (e: ScanRepository.NotSmaller) {
            assertEquals(1L, e.before)
        }
        assertRejected(ids, 1L, null)
    }

    @Test
    @Config(shadows = [ExtRenderer::class, ExtPage::class])
    fun externalTargetImportThatGrowsPublishesNothing() = runBlocking {
        val ids = repo.docsDir.list().orEmpty().toSet()
        try {
            repo.importPdfToTarget(
                Uri.fromFile(pdf), "Grown", listOf(0), 50_000L, rejectNotSmallerThan = 1L
            )
            fail("expected NotSmaller")
        } catch (e: ScanRepository.NotSmaller) {
            assertEquals(50_000L, e.target)
        }
        assertRejected(ids, 1L, 50_000L)
    }

    @Test
    @Config(shadows = [ExtRenderer::class, ExtPage::class])
    fun interruptedRightAfterRejectionStillPublishesNothing() = runBlocking {
        val ids = repo.docsDir.list().orEmpty().toSet()
        class Death : Error()
        OpFaults.hook = { if (it == "compress:rejected") throw Death() }
        try {
            try {
                repo.importPdfAsScanSelected(
                    Uri.fromFile(pdf), "Grown", indices = listOf(0), rejectNotSmallerThan = 1L
                )
                fail("expected simulated process death")
            } catch (_: Death) { }
            assertRejected(ids, 1L, null)
        } finally { OpFaults.reset() }
    }

    @Test
    @Config(shadows = [ExtRenderer::class, ExtPage::class])
    fun smallerExternalOutputStillPublishes() = runBlocking {
        val doc = requireNotNull(
            repo.importPdfAsScanSelected(
                Uri.fromFile(pdf), "Fits", indices = listOf(0),
                rejectNotSmallerThan = 100L shl 20
            )
        )
        assertNotNull(repo.getDocument(doc.id))
    }
}
