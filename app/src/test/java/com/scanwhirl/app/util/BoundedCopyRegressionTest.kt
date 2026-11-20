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
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundedCopyRegressionTest {
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
        ShadowContentResolver.registerInputStream(uri, object : ByteArrayInputStream("%PDF-fixture".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read.set(true)
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
            ).use {
                val deadline = System.currentTimeMillis() + 10_000
                while (!read.get() && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertTrue("the provider was read", read.get())
                assertFalse("picked-file read was on MAIN", main.get())
            }
        } finally {
            PdfViewerActivity.pagesFactory = original
        }
    }
}
