package com.scanwhirl.app.util

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.PdfViewerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundedCopyRegressionTest {
    @Test fun `renderer refuses oversized picked PDF before opening descriptor and cleans up`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://l1bounded/oversized.pdf")
        val input = object : java.io.InputStream() {
            var left = PdfImport.MAX_BYTES + 1
            override fun read() = if (left-- > 0) 1 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(left, len.toLong()).toInt()
                left -= n
                return n
            }
        }
        shadowOf(app.contentResolver).registerInputStream(uri, input)
        try {
            PdfPageRenderer.withSeekablePdf(app, uri) { error("oversized copy was accepted") }
            fail("must refuse")
        } catch (_: java.io.IOException) {
            // The refusal is typed by the new helper; no renderer/descriptor work is allowed.
        }
        assertTrue(File(app.cacheDir, "pdf_fd").listFiles().orEmpty().isEmpty())
    }
    @Test fun `sweep does not remove a copy owned by an active reader`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "pdf_fd/seek_active.pdf")
        try {
            assertEquals(BoundedCopy.Result.OK,
                BoundedCopy.copy(file, 100, { Long.MAX_VALUE }) {
                    ByteArrayInputStream("fictional".toByteArray())
                })
            PrivacySweep.run(app)
            assertTrue(file.exists())
        } finally {
            BoundedCopy.release(file)
        }
    }
    @Test fun `cold start removes abandoned seekable and viewer copies`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val files = listOf("pdf_fd/seek_old.pdf", "pdf_open/view_old.pdf").map {
            File(app.cacheDir, it).apply { parentFile!!.mkdirs(); writeText("fictional") }
        }
        PrivacySweep.run(app)
        files.forEach { assertFalse("stale plaintext remains: $it", it.exists()) }
    }

    @Test fun `external viewer never reads a picked PDF on main`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val uri = Uri.parse("content://l1bounded/fake.pdf")
        val read = AtomicBoolean()
        val main = AtomicBoolean()
        val reads = AtomicInteger()
        shadowOf(app.contentResolver).registerInputStream(uri, object : ByteArrayInputStream("%PDF-fixture".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read.set(true)
                reads.incrementAndGet()
                if (Looper.myLooper() == Looper.getMainLooper()) main.set(true)
                return super.read(b, off, len)
            }
        })
        val original = PdfViewerActivity.pagesFactory
        PdfViewerActivity.pagesFactory = {
            object : PdfViewerActivity.PdfPages {
                override val pageCount = 1
                override fun render(index: Int, maxDim: Int) = null
                override fun close() = Unit
            }
        }
        try {
            ActivityScenario.launch<PdfViewerActivity>(
                PdfViewerActivity.uriIntent(app, uri.toString(), "Fictional")
            ).use { scenario ->
                val deadline = System.currentTimeMillis() + 10_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    scenario.onActivity {
                        ready = it.findViewById<androidx.recyclerview.widget.RecyclerView>(
                            com.scanwhirl.app.R.id.pdfPager).adapter?.itemCount == 1
                    }
                    Thread.sleep(10)
                }
                assertTrue("reader opened", ready)
                assertTrue("the provider was read", read.get())
                assertFalse("picked-file read was on MAIN", main.get())
                val count = reads.get()
                scenario.recreate()
                shadowOf(Looper.getMainLooper()).idle()
                scenario.onActivity {
                    assertEquals(1, it.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        com.scanwhirl.app.R.id.pdfPager).adapter?.itemCount)
                }
                assertEquals("rotation reuses the existing private copy", count, reads.get())
            }
        } finally {
            PdfViewerActivity.pagesFactory = original
        }
    }
}
