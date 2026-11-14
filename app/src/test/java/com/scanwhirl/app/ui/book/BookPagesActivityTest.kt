package com.scanwhirl.app.ui.book

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v2 tool "Book pages": the gallery-picked spread is split at its gutter and becomes one new
 * document with two pages per source, through the existing create path.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookPagesActivityTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanWhirlApp.from(app).repository
        ProGate.setTestOverride(app, true)
    }

    @After fun tearDown() = Faults.resetAll()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 12000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        idle()
        return condition()
    }

    private fun jpegSpread(darkBand: IntRange?): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        if (darkBand != null) {
            Canvas(bitmap).drawRect(
                darkBand.first.toFloat(), 0f, (darkBand.last + 1).toFloat(), 500f,
                Paint().apply { color = Color.BLACK }
            )
        }
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun register(bytes: ByteArray, name: String): Uri {
        val uri = Uri.parse("content://books/$name.jpg")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
            ByteArrayInputStream(bytes)
        }
        return uri
    }

    @Test
    fun `each picked spread becomes two pages in one new document`() {
        val first = register(jpegSpread(390..410), "first")
        val second = register(jpegSpread(null), "second")

        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            scenario.onActivity { it.loadImages(listOf(first, second)) }
            assertTrue("both previews decode", pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 2
            })

            scenario.onActivity { activity ->
                val previews = activity.previews
                assertTrue("the dark fold is detected", previews[0].ratio in 0.45f..0.55f)
                assertEquals("a flat photo falls back to the middle", 0.5f, previews[1].ratio, 0.01f)
                activity.createButton().performClick()
            }

            // createDocument publishes the directory before the PDF is generated, so wait for the
            // finished document (pages + PDF), not merely for it to appear in the list.
            assertTrue(
                "the document is created",
                pollUntil {
                    repo.listDocuments().any { it.pageCount == 4 && it.pdfFile.length() > 0 }
                }
            )
            val doc = repo.listDocuments().first()
            assertEquals("two pages per picked spread", 4, doc.pageCount)
            assertEquals(4, doc.pageFiles.count { it.length() > 0 })
            assertTrue("the new document has a PDF", doc.pdfFile.length() > 0)
        }
    }

    @Test
    fun `Book pages is a ready Pro tool that launches its own screen`() {
        val tool = ToolRegistry.byId("book_pages")
        assertNotNull("the catalogue has Book pages", tool)
        assertEquals(ToolStatus.READY, tool!!.status)
        assertTrue("Book pages is Pro", tool.pro)

        val host = RecordingHost(app)
        tool.launch(host, tool)
        val started = host.started
        assertNotNull("the tile starts a real screen", started)
        assertEquals(BookPagesActivity::class.java.name, started!!.component?.className)
    }

    /** A ToolHost that is also a Context (the shell is one), recording what the tile starts. */
    private class RecordingHost(base: Context) : ContextWrapper(base), ToolHost {
        var started: Intent? = null

        override fun startActivity(intent: Intent) {
            started = intent
        }

        override fun open(tool: SwTool) = throw AssertionError("Book pages must open its screen")
        override fun comingSoon(tool: SwTool) = throw AssertionError("Book pages is built")
    }
}
