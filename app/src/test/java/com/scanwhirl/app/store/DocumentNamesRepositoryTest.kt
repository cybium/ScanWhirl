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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Fictional fixtures only. Exercises the actual repository publish paths, not a naming mock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DocumentNamesRepositoryTest {
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var image: File
    private lateinit var pdf: File

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        image = File(app.cacheDir, "u2-page.jpg").apply { writeBytes(ImportFixtures.image(width = 80, height = 100)) }
        pdf = File(app.cacheDir, "u2-source.pdf")
        PdfGenerator.imagesToPdf(listOf(image), pdf)
    }

    @After fun tearDown() {
        ScanRepository.resetTestSeams()
        Faults.resetAll()
    }

    private suspend fun scan(name: String) =
        repo.saveScan(listOf(Uri.fromFile(image)), null, name)

    // Android's native PdfRenderer needs FileDescriptor.getOwnerId$(), absent on host JDK 17.
    // Only rendering is substituted; staging, metadata and publication are the real import path.
    @Implements(PdfRenderer::class)
    class NameTestRenderer {
        @Implementation fun __constructor__(fd: ParcelFileDescriptor) = Unit
        @Implementation fun getPageCount(): Int = 1
        @Implementation fun openPage(index: Int): PdfRenderer.Page = Shadow.newInstanceOf(PdfRenderer.Page::class.java)
        @Implementation fun close() = Unit
    }

    @Implements(PdfRenderer.Page::class)
    class NameTestPage {
        @Implementation fun getWidth(): Int = 80
        @Implementation fun getHeight(): Int = 100
        @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) {
            bitmap.eraseColor(Color.WHITE)
        }
        @Implementation fun close() = Unit
    }

    @Test
    @Config(shadows = [NameTestRenderer::class, NameTestPage::class])
    fun `PDF importing same name three times suffixes second and third`() = runBlocking {
        val names = (1..3).map { repo.importPdfAsScan(Uri.fromFile(pdf), "AuditCollision").name }
        assertEquals(listOf("AuditCollision", "AuditCollision (2)", "AuditCollision (3)"), names)
    }

    @Test fun `photo and scan saves share the same unique namespace`() = runBlocking {
        assertEquals("X", scan("X").name)
        assertEquals("X (2)", scan("X").name)
        assertEquals("X (3)", scan("X").name)
    }

    @Test fun `case and surrounding space collide`() = runBlocking {
        scan(" Electricity bill ")
        assertEquals("electricity BILL (2)", scan(" electricity BILL ").name)
    }

    @Test fun `rename collision auto suffixes without touching other document`() = runBlocking {
        val first = scan("X")
        val second = scan("Other")
        repo.renameDocument(second.id, " x ")
        assertEquals("x (2)", repo.getDocument(second.id)!!.name)
        assertEquals("X", repo.getDocument(first.id)!!.name)
    }

    @Test fun `rename to own name does not suffix`() = runBlocking {
        val doc = scan("X")
        repo.renameDocument(doc.id, " X ")
        assertEquals("X", repo.getDocument(doc.id)!!.name)
    }

    @Test fun `trash does not reserve title but restore must resolve collision`() = runBlocking {
        val original = scan("X")
        val key = repo.moveToTrash(original.id)!!
        val replacement = scan("X")
        assertEquals("X", replacement.name)
        assertEquals("X (2)", repo.restoreFromTrash(key).name)
        assertEquals("X", repo.getDocument(replacement.id)!!.name)
    }

    @Test fun `vault reserves titles to avoid collisions on return to library`() = runBlocking {
        val doc = scan("X")
        File(doc.pdfFile.parentFile, "vault.flag").writeText("1")
        assertEquals("X (2)", scan("X").name)
    }

    @Test fun `Hindi titles are preserved and suffixed`() = runBlocking {
        assertEquals("बिजली का बिल", scan("बिजली का बिल").name)
        assertEquals("बिजली का बिल (2)", scan("बिजली का बिल").name)
    }

    @Test fun `tool copies resolve repeated explicit titles`() = runBlocking {
        val doc = scan("X")
        assertEquals("X (2)", repo.duplicateDocument(doc.id, "X").name)
        assertEquals("X (3)", repo.duplicateDocument(doc.id, "X").name)
    }

    @Test fun `concurrent tool creates from separate repository instances are unique`() = runBlocking {
        val doc = scan("Source")
        val names = (1..8).map {
            async(Dispatchers.IO) { ScanRepository(app).duplicateDocument(doc.id, "Concurrent").name }
        }.awaitAll()
        assertEquals(8, names.toSet().size)
        assertEquals(setOf("Concurrent") + (2..8).map { "Concurrent ($it)" }, names.toSet())
    }

    @Test fun `concurrent mixed scan and tool creates are unique`() = runBlocking {
        val doc = scan("Source")
        val names = (1..6).map { i ->
            async(Dispatchers.IO) {
                if (i % 2 == 0) scan("Mixed").name
                else ScanRepository(app).duplicateDocument(doc.id, "Mixed").name
            }
        }.awaitAll()
        assertEquals(6, names.toSet().size)
    }

    @Test fun `suffix respects existing eighty character title limit`() = runBlocking {
        val title = "क".repeat(80)
        scan(title)
        val second = scan(title).name
        assertEquals("क".repeat(76) + " (2)", second)
        assertTrue(second.length <= 80)
    }

    @Test fun `existing duplicate titles are not retroactively rewritten`() = runBlocking {
        val first = scan("Old")
        val second = scan("Other")
        val meta = File(second.pdfFile.parentFile, "meta.txt")
        meta.writeText(meta.readLines().toMutableList().also { it[0] = "Old" }.joinToString("\n") + "\n")
        val before = meta.readText()
        assertEquals("Old (2)", scan("Old").name)
        assertEquals("Old", repo.getDocument(first.id)!!.name)
        assertEquals(before, meta.readText())
    }

    @Test fun `rename preserves creation category exports and unknown metadata`() = runBlocking {
        scan("X")
        val doc = scan("Other")
        val meta = File(doc.pdfFile.parentFile, "meta.txt")
        meta.appendText("exports=content://fictional/export\nfuture=keep\n")
        val rest = meta.readLines().drop(1)
        repo.renameDocument(doc.id, "X")
        assertEquals("X (2)", repo.getDocument(doc.id)!!.name)
        assertEquals(rest, meta.readLines().drop(1))
    }

    @Test fun `prepared scan resolves collisions that appeared before replay`() = runBlocking {
        val review = File(app.cacheDir, "u2-review").apply { mkdirs() }
        SaveFaults.hook = { if (it == "before_publish") throw AssertionError("process death") }
        var died = false
        try {
            repo.saveScan(listOf(Uri.fromFile(image)), null, "X", reviewDir = review)
        } catch (_: AssertionError) {
            // Error deliberately bypasses transaction cleanup, just as process death does.
            died = true
        } finally {
            Faults.resetAll()
        }
        assertTrue("expected simulated death", died)
        val other = scan("X")
        val restored = repo.saveScan(listOf(Uri.fromFile(image)), null, "X", reviewDir = review)
        assertEquals("X (2)", restored.name)
        assertEquals("X", repo.getDocument(other.id)!!.name)
        assertEquals(restored.id, repo.saveScan(listOf(Uri.fromFile(image)), null, "X", reviewDir = review).id)
        assertEquals(2, repo.listDocuments().size)
    }
}
