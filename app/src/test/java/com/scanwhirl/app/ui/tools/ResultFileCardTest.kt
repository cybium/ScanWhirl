package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.OfficeFileCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v2 UI run4a: the result screen for .docx/.xlsx/.pptx/.zip shows a file-type card (type badge,
 * name, size and a real page/sheet/slide/file count), not an empty white preview box.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ResultFileCardTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun packageFile(name: String, entries: Map<String, String>): File {
        val file = File(app.cacheDir, "documents/result/$name").apply { parentFile?.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `docx result shows a DOCX card with the page count`() {
        val docx = packageFile(
            "Invoice.docx",
            mapOf(
                "word/document.xml" to
                    "<w:body><w:p/><w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" +
                    "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p></w:body>"
            )
        )
        val intent = ToolResultActivity.intent(
            app, "to_word", files = listOf(docx),
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertEquals(
                    "DOCX", activity.findViewById<TextView>(R.id.toolResultTypeBadge).text.toString()
                )
                assertEquals(
                    "Invoice.docx", activity.findViewById<TextView>(R.id.toolResultFileName).text.toString()
                )
                assertTrue("the page count appears", waitUntil {
                    activity.findViewById<TextView>(R.id.toolResultFileMeta)
                        ?.text?.toString()?.contains("3 pages") == true
                })
            }
        }
    }

    @Test
    fun `xlsx pptx and zip results each badge their own type`() {
        val xlsx = packageFile(
            "Book.xlsx",
            mapOf(
                "xl/worksheets/sheet1.xml" to "<worksheet/>",
                "xl/worksheets/sheet2.xml" to "<worksheet/>"
            )
        )
        val pptx = packageFile(
            "Deck.pptx",
            mapOf(
                "ppt/slides/slide1.xml" to "<p:sld/>",
                "ppt/slides/slide2.xml" to "<p:sld/>",
                "ppt/slides/slide3.xml" to "<p:sld/>"
            )
        )
        val zip = packageFile(
            "Pages.zip",
            mapOf("a.jpg" to "a", "b.jpg" to "b", "c.jpg" to "c", "d.jpg" to "d")
        )

        fun card(file: File, expectedBadge: String, expectedMeta: String) {
            ActivityScenario.launch<ToolResultActivity>(
                ToolResultActivity.intent(app, "zip", files = listOf(file), mime = "application/zip")
            ).use { scenario ->
                scenario.onActivity { activity ->
                    idle()
                    assertEquals(
                        expectedBadge,
                        activity.findViewById<TextView>(R.id.toolResultTypeBadge).text.toString()
                    )
                    assertTrue("$expectedBadge card shows $expectedMeta", waitUntil {
                        activity.findViewById<TextView>(R.id.toolResultFileMeta)
                            ?.text?.toString()?.contains(expectedMeta) == true
                    })
                }
            }
        }
        card(xlsx, "XLSX", "2 sheets")
        card(pptx, "PPTX", "3 slides")
        card(zip, "ZIP", "4 files")
    }

    @Test
    fun `the pptx card says the slides are pictures and the text is in the notes`() {
        val pptx = packageFile(
            "Deck.pptx",
            mapOf(
                "ppt/slides/slide1.xml" to "<p:sld/>",
                "ppt/notesSlides/notesSlide1.xml" to "<p:notes/>"
            )
        )
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "to_powerpoint", files = listOf(pptx), mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val words = allText(activity.window.decorView)
                assertTrue("the PPTX result must say the slides are page pictures: $words", words.contains("picture", true))
                assertTrue("the PPTX result must say where the text lives: $words", words.contains("notes", true))
                assertFalse("the PPTX result must not promise editable slides: $words", words.contains("editable", true))
            }
        }
    }

    private fun allText(view: android.view.View): String {
        val out = StringBuilder()
        fun walk(v: android.view.View) {
            if (v is TextView && v.visibility == android.view.View.VISIBLE) {
                out.append(v.text).append('\n')
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
        return out.toString()
    }

    @Test
    fun `the count helper reads real package contents`() {
        val docx = packageFile(
            "Two.docx",
            mapOf("word/document.xml" to "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        )
        assertEquals(2, OfficeFileCounts.count(docx))
        val xlsx = packageFile("One.xlsx", mapOf("xl/worksheets/sheet1.xml" to "<worksheet/>"))
        assertEquals(1, OfficeFileCounts.count(xlsx))
        val pptx = packageFile("Three.pptx", mapOf("ppt/slides/slide3.xml" to "<p:sld/>"))
        assertEquals(1, OfficeFileCounts.count(pptx))
        val plain = File(app.cacheDir, "documents/result/plain.txt").apply {
            parentFile?.mkdirs()
            writeText("not a package")
        }
        assertEquals(null, OfficeFileCounts.count(plain))
    }
}
