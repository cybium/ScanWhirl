package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * v2 UI: 03_tools_hub — To Excel writes a minimal valid OOXML workbook with `java.util.zip`:
 * correct part names and relationships, inline strings, real numbers with number formats.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class XlsxWriterTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun out(name: String): File = File(app.cacheDir, name).apply { parentFile?.mkdirs() }

    private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().toList().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun text(parts: Map<String, ByteArray>, name: String): String =
        String(parts[name] ?: throw AssertionError("missing part $name"), Charsets.UTF_8)

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

    private fun children(element: Element, localName: String): List<Element> =
        (0 until element.childNodes.length).mapNotNull { i ->
            val node = element.childNodes.item(i) as? Element ?: return@mapNotNull null
            val name = node.localName ?: node.nodeName.substringAfter(':')
            node.takeIf { name == localName }
        }

    private fun assertPackageValid(parts: Map<String, ByteArray>) {
        val types = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(parts["[Content_Types].xml"]!!.inputStream()).documentElement
        for (override in children(types, "Override")) {
            val part = override.getAttribute("PartName").trimStart('/')
            assertTrue("content-type override for missing part $part", parts.containsKey(part))
        }
        for ((name, bytes) in parts) {
            if (!name.endsWith(".rels")) continue
            val source = if (name == "_rels/.rels") "" else name.replace("/_rels/", "/").removeSuffix(".rels")
            val rels = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(bytes.inputStream()).documentElement
            val ids = mutableSetOf<String>()
            for (rel in children(rels, "Relationship")) {
                assertTrue("duplicate rel id in $name", ids.add(rel.getAttribute("Id")))
                val target = resolve(source, rel.getAttribute("Target"))
                assertTrue("$name -> $target is missing", parts.containsKey(target))
            }
        }
    }

    @Test
    fun `a workbook has every part, sheets in order and valid relationships`() {
        val file = out("report.xlsx")
        XlsxWriter.write(
            file,
            listOf(
                XlsxWriter.Sheet(
                    "Page 1",
                    listOf(
                        listOf("Invoice"),
                        listOf("Item", "Qty", "Rate", "Amount"),
                        listOf("Pen", "2", "₹10", "₹20"),
                        listOf("Book", "1", "₹250", "₹250")
                    )
                ),
                XlsxWriter.Sheet("Page 2", listOf(listOf("Plain text")))
            )
        )
        val parts = entries(file)
        for (required in listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"
        )) {
            assertTrue("required part $required", parts.containsKey(required))
        }
        assertPackageValid(parts)
        val workbook = text(parts, "xl/workbook.xml")
        assertTrue(workbook.contains("<sheet name=\"Page 1\" sheetId=\"1\" r:id=\"rId1\"/>"))
        assertTrue(workbook.contains("<sheet name=\"Page 2\" sheetId=\"2\" r:id=\"rId2\"/>"))
        val styles = text(parts, "xl/styles.xml")
        assertTrue("rupee format", styles.contains("\u20B9"))
        assertTrue("percent format", styles.contains("formatCode=\"0%\""))
        val sheet = text(parts, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Invoice</t></is></c>"))
        assertTrue("integer cell with a number format", sheet.contains("<c r=\"B3\" s=\"1\"><v>2</v></c>"))
        assertTrue("rupee cell stores the number", sheet.contains("<c r=\"C3\" s=\"4\"><v>10</v></c>"))
        assertTrue(sheet.contains("<c r=\"B4\" s=\"1\"><v>1</v></c>"))
        assertTrue(sheet.contains("<c r=\"C4\" s=\"4\"><v>250</v></c>"))
        assertTrue(text(parts, "xl/worksheets/sheet2.xml").contains("Plain text"))
    }

    @Test
    fun `numeric parsing covers currency decimals percent and Indian grouping`() {
        assertEquals(XlsxWriter.Numeric(4560.0, XlsxWriter.NumberKind.RUPEE), XlsxWriter.parseNumeric("₹4,560"))
        assertEquals(XlsxWriter.Numeric(1234.5, XlsxWriter.NumberKind.DECIMAL), XlsxWriter.parseNumeric("1,234.50"))
        assertEquals(XlsxWriter.Numeric(0.12, XlsxWriter.NumberKind.PERCENT), XlsxWriter.parseNumeric("12%"))
        assertEquals(XlsxWriter.Numeric(123456.0, XlsxWriter.NumberKind.DECIMAL), XlsxWriter.parseNumeric("1,23,456.00"))
        assertEquals(XlsxWriter.Numeric(123.0, XlsxWriter.NumberKind.INTEGER), XlsxWriter.parseNumeric("123"))
        assertEquals(XlsxWriter.Numeric(-1000.0, XlsxWriter.NumberKind.DECIMAL), XlsxWriter.parseNumeric("-1,000"))
        assertEquals(XlsxWriter.Numeric(4999.0, XlsxWriter.NumberKind.RUPEE), XlsxWriter.parseNumeric("Rs. 4,999"))
        assertNull("a phone number is text", XlsxWriter.parseNumeric("9876543210"))
        assertNull("a reference with a leading zero is text", XlsxWriter.parseNumeric("007"))
        assertNull("words are text", XlsxWriter.parseNumeric("₹12 each"))
        assertNull(XlsxWriter.parseNumeric(""))
    }

    @Test
    fun `a failed write leaves no visible workbook`() {
        val directory = File(app.cacheDir, "xlsx_target").apply { mkdirs() }
        val r = runCatching { XlsxWriter.write(directory, listOf(XlsxWriter.Sheet("Page 1", listOf(listOf("x"))))) }
        assertTrue(r.isFailure)
        assertTrue("the directory is untouched", directory.isDirectory)
        assertTrue("no .part file", !File(directory.path + ".part").exists())
    }

    @Test
    fun `text is escaped and sheet names are preserved`() {
        val file = out("escape.xlsx")
        XlsxWriter.write(
            file,
            listOf(XlsxWriter.Sheet("Page & 1", listOf(listOf("A & B < C"))))
        )
        val parts = entries(file)
        assertPackageValid(parts)
        assertTrue(text(parts, "xl/worksheets/sheet1.xml").contains("A &amp; B &lt; C"))
        assertTrue(text(parts, "xl/workbook.xml").contains("Page &amp; 1"))
        assertNotNull(parts["xl/styles.xml"])
    }
}
