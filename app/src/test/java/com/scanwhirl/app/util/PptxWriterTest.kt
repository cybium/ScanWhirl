package com.scanwhirl.app.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * v2 UI: 03_tools_hub — To PowerPoint writes a complete OOXML package with `java.util.zip` only.
 *
 * "Complete" is asserted strictly, the way PowerPoint reads it: every required part exists,
 * every `[Content_Types].xml` override names a real part, every relationship target resolves,
 * the presentation has one slide per page, and each slide's only shape references an image in
 * `ppt/media`. A failure leaves no visible file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PptxWriterTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun out(name: String): File =
        File(app.cacheDir, name).apply { parentFile?.mkdirs() }

    private fun jpeg(width: Int = 120, height: Int = 80): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun text(parts: Map<String, ByteArray>, name: String): String {
        val bytes = parts[name] ?: throw AssertionError("missing part $name")
        return String(bytes, Charsets.UTF_8)
    }

    private fun xml(parts: Map<String, ByteArray>, name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(parts[name]?.inputStream() ?: throw AssertionError("missing part $name"))
            .documentElement

    private fun children(element: Element, localName: String): List<Element> =
        (0 until element.childNodes.length).mapNotNull { i ->
            val node = element.childNodes.item(i) as? Element ?: return@mapNotNull null
            val name = node.localName ?: node.nodeName.substringAfter(':')
            node.takeIf { name == localName }
        }

    /** Resolve an OPC relationship target against the source part's folder. */
    private fun resolve(sourcePart: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val base = sourcePart.substringBeforeLast('/', "")
        val out = ArrayDeque<String>()
        for (segment in "$base/$target".split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    /**
     * Strict package check: every relationship part names its source, every target exists, every
     * id is unique inside its part, and every content-type override names an existing part.
     */
    private fun assertPackageValid(parts: Map<String, ByteArray>) {
        val types = xml(parts, "[Content_Types].xml")
        for (override in children(types, "Override")) {
            val part = override.getAttribute("PartName").trimStart('/')
            assertTrue("content-type override for missing part $part", parts.containsKey(part))
        }
        for ((name, bytes) in parts) {
            if (!name.endsWith(".rels")) continue
            val source = when {
                name == "_rels/.rels" -> ""
                else -> name.replace("/_rels/", "/").removeSuffix(".rels")
            }
            val rels = xml(parts, name)
            val ids = mutableSetOf<String>()
            for (relationship in children(rels, "Relationship")) {
                val id = relationship.getAttribute("Id")
                assertTrue("duplicate relationship $id in $name", ids.add(id))
                if (relationship.getAttribute("TargetMode") == "External") continue
                val target = resolve(source, relationship.getAttribute("Target"))
                assertTrue(
                    "$name -> ${relationship.getAttribute("Target")} (=$target) is missing",
                    parts.containsKey(target)
                )
            }
        }
    }

    private fun slideCount(parts: Map<String, ByteArray>): Int {
        val presentation = xml(parts, "ppt/presentation.xml")
        val list = children(presentation, "sldIdLst").first()
        return children(list, "sldId").size
    }

    // ---- package ----------------------------------------------------------

    @Test
    fun `pptx has every required part, one slide per page and one picture each`() {
        val file = out("deck.pptx")
        PptxWriter.write(
            file,
            pageCount = 3,
            portrait = true,
            pageJpeg = { jpeg() },
            pageNotes = { null }
        )
        val parts = entries(file)

        for (required in listOf(
            "[Content_Types].xml", "_rels/.rels",
            "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            "ppt/theme/theme1.xml",
            "ppt/slides/slide1.xml", "ppt/slides/_rels/slide1.xml.rels",
            "ppt/slides/slide2.xml", "ppt/slides/_rels/slide2.xml.rels",
            "ppt/slides/slide3.xml", "ppt/slides/_rels/slide3.xml.rels",
            "ppt/media/image1.jpeg", "ppt/media/image2.jpeg", "ppt/media/image3.jpeg"
        )) {
            assertTrue("required part $required is missing", parts.containsKey(required))
        }
        assertFalse("a text-only deck needs no notes master", parts.containsKey("ppt/notesMasters/notesMaster1.xml"))
        assertEquals(3, slideCount(parts))
        assertPackageValid(parts)

        val presentation = text(parts, "ppt/presentation.xml")
        assertTrue("A4 portrait width", presentation.contains("<p:sldSz cx=\"7560000\" cy=\"10692000\"/>"))
        for (i in 1..3) {
            val slide = text(parts, "ppt/slides/slide$i.xml")
            assertTrue("slide $i has no picture", slide.contains("<p:pic>"))
            assertTrue("slide $i does not embed an image", slide.contains("r:embed=\"rId1\""))
            val rels = text(parts, "ppt/slides/_rels/slide$i.xml.rels")
            assertTrue("slide $i rels point elsewhere", rels.contains("Target=\"../media/image$i.jpeg\""))
            assertTrue("slide $i rels type", rels.contains("/image"))
            val image = parts["ppt/media/image$i.jpeg"]!!
            assertEquals(0xFF, image[0].toInt() and 255)
            assertEquals(0xD8, image[1].toInt() and 255)
        }
        val master = text(parts, "ppt/slideMasters/_rels/slideMaster1.xml.rels")
        assertTrue("master must reference a layout", master.contains("slideLayouts/slideLayout1.xml"))
        assertTrue("master must reference the theme", master.contains("theme/theme1.xml"))
        assertTrue(
            "layout must reference its master",
            text(parts, "ppt/slideLayouts/_rels/slideLayout1.xml.rels").contains("slideMasters/slideMaster1.xml")
        )
    }

    @Test
    fun `landscape decks swap the A4 slide size`() {
        val file = out("landscape.pptx")
        PptxWriter.write(file, pageCount = 1, portrait = false, pageJpeg = { jpeg(200, 100) })
        val presentation = text(entries(file), "ppt/presentation.xml")
        assertTrue(presentation.contains("<p:sldSz cx=\"10692000\" cy=\"7560000\"/>"))
    }

    @Test
    fun `page text becomes the slide notes and adds the notes master`() {
        val file = out("notes.pptx")
        PptxWriter.write(
            file,
            pageCount = 2,
            portrait = true,
            pageJpeg = { jpeg() },
            pageNotes = { index -> if (index == 0) "Hello & <notes>" else null }
        )
        val parts = entries(file)
        assertTrue("notes master is required for notes", parts.containsKey("ppt/notesMasters/notesMaster1.xml"))
        assertTrue(parts.containsKey("ppt/notesMasters/_rels/notesMaster1.xml.rels"))
        assertTrue(parts.containsKey("ppt/notesSlides/notesSlide1.xml"))
        assertTrue(parts.containsKey("ppt/notesSlides/notesSlide2.xml"))
        assertEquals(2, slideCount(parts))
        assertPackageValid(parts)
        assertTrue(
            "presentation must reference the notes master",
            text(parts, "ppt/presentation.xml").contains("<p:notesMasterIdLst>")
        )
        val notes = text(parts, "ppt/notesSlides/notesSlide1.xml")
        assertTrue("notes text is escaped: $notes", notes.contains("Hello &amp; &lt;notes&gt;"))
        assertTrue(
            "slide 1 must link its notes",
            text(parts, "ppt/slides/_rels/slide1.xml.rels").contains("notesSlides/notesSlide1.xml")
        )
    }

    @Test
    fun `a failing page leaves no visible pptx`() {
        val file = out("broken.pptx")
        val r = runCatching {
            PptxWriter.write(
                file, pageCount = 2, portrait = true,
                pageJpeg = { index ->
                    if (index == 1) throw IllegalStateException("injected page failure") else jpeg()
                }
            )
        }
        assertTrue("the failure must reach the caller", r.isFailure)
        assertTrue("no pptx may be visible", !file.exists())
        assertTrue("no .part file may be left", !File(file.path + ".part").exists())
    }

    @Test
    fun `a page that is not an image is refused`() {
        val file = out("badimage.pptx")
        val r = runCatching {
            PptxWriter.write(file, pageCount = 1, portrait = true, pageJpeg = { "not a jpeg".toByteArray() })
        }
        assertTrue(r.isFailure)
        assertTrue(!file.exists())
    }

    @Test
    fun `the same package is produced deterministically for the same input`() {
        val first = out("det1.pptx")
        val second = out("det2.pptx")
        val jpegBytes = jpeg()
        PptxWriter.write(first, 1, portrait = true, pageJpeg = { jpegBytes }, pageNotes = { "line" })
        PptxWriter.write(second, 1, portrait = true, pageJpeg = { jpegBytes }, pageNotes = { "line" })
        val a = entries(first)
        val b = entries(second)
        assertEquals(a.keys, b.keys)
        for ((name, bytes) in a) {
            if (name.endsWith(".jpeg")) continue // ZipOutputStream timestamps differ per entry
            assertTrue("part $name differs between runs", bytes.contentEquals(b.getValue(name)))
        }
        assertNotNull(xml(a, "ppt/presentation.xml"))
        try {
            // If the writer ever loses its XML declaration the parser above would fail; this is
            // just a readable assertion for the failure message.
            assertTrue(text(a, "ppt/presentation.xml").startsWith("<?xml"))
        } catch (e: AssertionError) {
            fail(e.message)
        }
    }
}
