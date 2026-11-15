package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.OcrFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.RandomAccessFile

/**
 * "OCR a gallery image" must stay lenient and bounded: it is not a page import.
 * ML Kit is replaced by a recorder; what is under test is what the picker flow
 * hands to the recognizer and what the user sees when an image cannot be read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrGalleryImageFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Before @After fun resetToasts() = ShadowToast.reset()

    private fun bmp(): ByteArray {
        // Reuse the 24-bit builder logic in miniature: 8x8 solid red.
        val width = 8
        val height = 8
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xFF) }
        out.write('B'.code); out.write('M'.code)
        le(54 + 24 * height, 4); le(0, 4); le(54, 4)
        le(40, 4); le(width, 4); le(height, 4); le(1, 2); le(24, 2); le(0, 4)
        le(24 * height, 4); le(2835, 4); le(2835, 4); le(0, 4); le(0, 4)
        repeat(height) { repeat(width) { out.write(0); out.write(0); out.write(255) }; }
        return out.toByteArray()
    }

    private class Run(val seen: MutableList<Pair<Int, Int>> = mutableListOf(), var editor: Intent? = null)

    private fun runFlow(file: File, recognized: String = "OCR TEXT"): Run {
        val run = Run()
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val flow = OcrFlow(activity, repo, activity) { bitmap: Bitmap ->
                    synchronized(run.seen) { run.seen.add(bitmap.width to bitmap.height) }
                    recognized
                }
                flow.runOcrForImage(Uri.fromFile(file))
            }
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                scenario.onActivity { run.editor = shadowOf(it).nextStartedActivity ?: run.editor }
                if (run.editor != null || ShadowToast.getLatestToast() != null) break
                Thread.sleep(25)
            }
        }
        return run
    }

    @Test fun `BMP from the gallery reaches the recognizer and opens the editor`() {
        val file = File(tmp.root, "photo.bmp").apply { writeBytes(bmp()) }
        val before = file.readBytes()
        val run = runFlow(file)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(8 to 8), run.seen)
        val intent = run.editor!!
        assertEquals(OcrEditorActivity::class.java.name, intent.component?.className)
        assertEquals("OCR TEXT", File(intent.getStringExtra(OcrEditorActivity.EXTRA_TEXT_PATH)!!).readText())
        assertArrayEquals("the original must be untouched", before, file.readBytes())
    }

    @Test fun `large rotated JPEG reaches the recognizer upright at full resolution`() {
        val file = File(tmp.root, "phone.jpg")
        ImportFixtures.oriented(file, 6, width = 4000, height = 3000)
        val before = file.readBytes()
        val run = runFlow(file)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(listOf(3000 to 4000), run.seen)
        assertNotNull(run.editor)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `unreadable image shows an actionable error and never reaches the recognizer`() {
        val file = File(tmp.root, "broken.jpg").apply { writeBytes("not an image".toByteArray()) }
        val run = runFlow(file)
        assertTrue(run.seen.isEmpty())
        assertNull(run.editor)
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("Choose a different image"))
    }

    @Test fun `file above 32 MB is refused with an actionable error`() {
        val file = File(tmp.root, "huge.bmp")
        RandomAccessFile(file, "rw").use { it.setLength(33L * 1024 * 1024) }
        val run = runFlow(file)
        assertTrue(run.seen.isEmpty())
        assertNull(run.editor)
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("32 MB"))
        assertEquals(33L * 1024 * 1024, file.length())
    }
}
