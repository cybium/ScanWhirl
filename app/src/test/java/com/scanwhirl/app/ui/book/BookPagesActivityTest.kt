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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** A close-up of one page on a dark table: one text block, no fold. */
    private fun jpegSinglePage(): ByteArray {
        // Portrait close-up: the aspect/shape rule makes it one page.
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(45, 42, 40))
        val canvas = Canvas(bitmap)
        canvas.drawRect(40f, 60f, 560f, 740f, Paint().apply { color = Color.rgb(242, 238, 228) })
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        var y = 110f
        while (y < 720f) {
            canvas.drawLine(100f, y, 500f, y, ink)
            y += 12f
        }
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun `a one-page photo is kept whole by default`() {
        val uri = register(jpegSinglePage(), "single")
        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            scenario.onActivity { it.loadImages(listOf(uri)) }
            assertTrue("the preview decodes", pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 1
            })

            scenario.onActivity { activity ->
                val toggle = activity.onePageSwitch(0)
                assertNotNull("Keep as one page is offered", toggle)
                assertTrue("and it is the default for this photo", toggle!!.isChecked)
                assertTrue("the card will make one page", activity.keepsAsOnePage(0))
                assertFalse("the split line is hidden", activity.previews[0].lineVisible)
                activity.createButton().performClick()
            }

            assertTrue("one page is created", pollUntil {
                repo.listDocuments().any { it.pageCount == 1 && it.pdfFile.length() > 0 }
            })
            assertEquals(1, repo.listDocuments().first().pageCount)
        }
    }

    @Test
    fun `turning Keep as one page off splits the photo again`() {
        val uri = register(jpegSinglePage(), "single-again")
        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            scenario.onActivity { it.loadImages(listOf(uri)) }
            assertTrue(pollUntil {
                var count = 0
                scenario.onActivity { count = it.previews.size }
                count == 1
            })

            scenario.onActivity { activity ->
                val toggle = activity.onePageSwitch(0)!!
                toggle.isChecked = false
                assertFalse(activity.keepsAsOnePage(0))
                assertTrue("the line comes back", activity.previews[0].lineVisible)
                activity.createButton().performClick()
            }

            assertTrue("the split makes two pages", pollUntil {
                repo.listDocuments().any { it.pageCount == 2 && it.pdfFile.length() > 0 }
            })
        }
    }

    @Test
    fun `new documents are named Book plus the date and numbered when repeated`() {
        val fixed = java.util.GregorianCalendar(2026, java.util.Calendar.NOVEMBER, 14).time
        ActivityScenario.launch(BookPagesActivity::class.java).use { scenario ->
            var first = ""
            scenario.onActivity { first = it.nextBookName(fixed) }
            assertEquals("Book 14 Nov", first)

            kotlinx.coroutines.runBlocking {
                repo.createDocument("Book 14 Nov", listOf(jpegSinglePage()))
            }
            var second = ""
            scenario.onActivity { second = it.nextBookName(fixed) }
            assertEquals("Book 14 Nov (2)", second)
        }
    }

    // fixui: tiles removed (owner F6/F7/F20) - the Book pages tile left the catalogue. The
    // activity stays for a post-launch entry point, but no tile can open a half-built screen.
    @Test
    fun `Book pages is gone from the catalogue`() {
        assertNull("the catalogue no longer lists Book pages", ToolRegistry.byId("book_pages"))
    }
}
