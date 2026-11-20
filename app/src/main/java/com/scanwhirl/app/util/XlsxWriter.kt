package com.scanwhirl.app.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, valid Excel workbook (`.xlsx`) written with `java.util.zip` only.
 *
 * One worksheet per page, inline strings (no shared-string table) and a small styles part with
 * number formats: integers, decimals, percent, and the common currency symbols (₹ $ € £ ¥) so a
 * cell keeps a readable display while holding a real number.
 *
 * Numeric rules ([parseNumeric]): a currency sign, a percent sign, a decimal point or grouping
 * commas make a cell numeric; a long plain digit run (phones) stays text; "1,23,456.00" and
 * "₹4,560" are numbers. The part names follow OOXML so Excel/WPS/LibreOffice open the file
 * without repair; the package is built as `.part` and renamed only when complete.
 */
object XlsxWriter {

    data class Sheet(val name: String, val rows: List<List<String>>)

    enum class NumberKind(val formatCode: String) {
        INTEGER("0"),
        DECIMAL("#,##0.00"),
        PERCENT("0.00%"),
        RUPEE("\"\u20B9\"#,##0.00"),
        DOLLAR("\"$\"#,##0.00"),
        EURO("\"\u20AC\"#,##0.00"),
        POUND("\"\u00A3\"#,##0.00"),
        YEN("\"\u00A5\"#,##0.00")
    }

    data class Numeric(val value: Double, val kind: NumberKind)

    fun write(outFile: File, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "No sheets to write" }
        sheets.forEach { require(it.name.isNotBlank()) { "A sheet needs a name" } }
        outFile.parentFile?.mkdirs()
        val partial = File(outFile.parentFile, outFile.name + ".part")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(partial))).use { zip ->
                entry(zip, "[Content_Types].xml", contentTypes(sheets.size))
                entry(zip, "_rels/.rels", ROOT_RELS)
                entry(zip, "xl/workbook.xml", workbook(sheets))
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
                entry(zip, "xl/styles.xml", STYLES)
                sheets.forEachIndexed { index, sheet ->
                    entry(zip, "xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
                }
            }
            if (partial.length() <= 0L) throw IOException("The workbook is empty")
            if (!partial.renameTo(outFile)) throw IOException("Could not save the workbook")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /**
     * Parses a cell as a number when that is unambiguous. Returns null for text.
     */
    fun parseNumeric(raw: String): Numeric? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        var kind: NumberKind? = null
        var percent = false
        if (text.endsWith("%")) {
            percent = true
            text = text.dropLast(1).trim()
        }
        val currency = listOf(
            "\u20B9" to NumberKind.RUPEE,
            "$" to NumberKind.DOLLAR,
            "\u20AC" to NumberKind.EURO,
            "\u00A3" to NumberKind.POUND,
            "\u00A5" to NumberKind.YEN
        ).firstOrNull { (symbol, _) -> text.startsWith(symbol) }
        if (currency != null) {
            kind = currency.second
            text = text.removePrefix(currency.first).trim()
        } else if (text.startsWith("Rs.", ignoreCase = true) || text.startsWith("INR", ignoreCase = true)) {
            kind = NumberKind.RUPEE
            text = text.drop(3).trim()
        }
        text = text.removePrefix("+").trim()
        val negative = text.startsWith("-")
        if (negative) text = text.drop(1).trim()
        if (percent && kind != null) return null // a currency and a percent together is not a number
        if (text.any { it.isLetter() || it == '@' || it == '#' }) return null
        if (!Regex("^\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?$|^\\d+(?:\\.\\d+)?$").matches(text)) return null
        val plain = text.replace(",", "")
        val value = plain.toDoubleOrNull() ?: return null
        val hasGrouping = ',' in text
        val hasDecimal = '.' in text
        if (!negative && kind == null && !percent && !hasGrouping && !hasDecimal) {
            // A plain digit run: small counts are numbers, long runs are phone/reference numbers.
            if (plain.length > 6 || (plain.length > 1 && plain.startsWith("0"))) return null
        }
        if (!negative && kind == null && !percent && plain.length > 6 && !hasGrouping) return null
        val signed = if (negative) -value else value
        val resolved = when {
            percent -> NumberKind.PERCENT
            kind != null -> kind!!
            hasDecimal || hasGrouping -> NumberKind.DECIMAL
            else -> NumberKind.INTEGER
        }
        return Numeric(if (percent) signed / 100.0 else signed, resolved)
    }

    // ---- parts ------------------------------------------------------------

    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(XML_DECL)
        append("<Types xmlns=\"$CT_NS\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append(
            "<Override PartName=\"/xl/workbook.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        )
        for (i in 1..sheetCount) {
            append(
                "<Override PartName=\"/xl/worksheets/sheet$i.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            )
        }
        append(
            "<Override PartName=\"/xl/styles.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
        )
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append(XML_DECL)
        append("<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$R_NS\"><sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"").append(escape(sheet.name.take(31))).append("\" sheetId=\"")
            append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append(XML_DECL)
        append("<Relationships xmlns=\"$PKG_NS\">")
        for (i in 1..sheetCount) {
            append("<Relationship Id=\"rId$i\" Type=\"$R_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$R_NS/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet): String = buildString {
        append(XML_DECL)
        append("<worksheet xmlns=\"$MAIN_NS\" xmlns:r=\"$R_NS\"><cols>")
        val columns = (sheet.rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        append("<col min=\"1\" max=\"").append(columns).append("\" width=\"22\" customWidth=\"1\"/>")
        append("</cols><sheetData>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            // OCR often returns a label and its value on one line ("Percentage: 85.50% Result:
            // PASS"). Split the unambiguous value out so Excel gets a real number cell while
            // the words stay in their own cells.
            val cells = row.flatMap(::splitCell)
            append("<row r=\"").append(rowIndex + 1).append("\">")
            cells.forEachIndexed { columnIndex, text ->
                val reference = "${columnName(columnIndex)}${rowIndex + 1}"
                val numeric = parseNumeric(text)
                if (numeric != null) {
                    append("<c r=\"").append(reference).append("\" s=\"").append(styleIndex(numeric.kind))
                    append("\"><v>").append(number(numeric.value)).append("</v></c>")
                } else if (text.isNotEmpty()) {
                    append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    append(escape(text)).append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** A value with a unit or separator (%, currency, grouping or decimal point) inside a text cell. */
    private val valueToken = Regex(
        "[-+]?(?:\\u20B9|\\$|\\u20AC|\\u00A3|\\u00A5)?\\s?\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?%?"
    )

    /**
     * Splits one OCR cell into text/value cells without losing a character. Only a token that
     * [parseNumeric] accepts and that carries a currency sign, percent sign, grouping comma or
     * decimal point is split, so ordinary words ("A4", "Page 1", "2026") stay whole. A value
     * that already fills the cell is returned unchanged.
     */
    internal fun splitCell(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty() || parseNumeric(text) != null) return listOf(raw)
        for (match in valueToken.findAll(text)) {
            val raw = match.value
            val token = raw.trim()
            if (token.isEmpty()) continue
            val start = match.range.first + (raw.length - raw.trimStart().length)
            val end = match.range.first + raw.trimEnd().length
            val before = text.substring(0, start)
            val after = text.substring(end)
            if (parseNumeric(token) == null) continue
            if (token.none { it == '%' || it == ',' || it == '.' || it in "\u20B9$\u20AC\u00A3\u00A5" }) continue
            if (before.lastOrNull()?.isLetterOrDigit() == true) continue
            if (after.firstOrNull()?.isLetterOrDigit() == true) continue
            val cells = mutableListOf<String>()
            if (before.isNotBlank()) cells += before.trim()
            cells += token
            if (after.isNotBlank()) cells += after.trim()
            return cells
        }
        return listOf(raw)
    }

    /** cellXfs index: 0 = text, then one per [NumberKind] in declaration order. */
    private fun styleIndex(kind: NumberKind): Int = NumberKind.entries.indexOf(kind) + 1

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.ROOT, "%.6f", value).trimEnd('0').trimEnd('.')

    private fun columnName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            out.insert(0, 'A' + remainder)
            n = (n - 1) / 26
        }
        return out.toString()
    }

    internal fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\u0000' -> Unit
                c < ' ' && c != '\n' && c != '\t' -> Unit
                else -> append(c)
            }
        }
    }

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"

    private const val ROOT_RELS = XML_DECL +
        "<Relationships xmlns=\"$PKG_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val STYLES: String = buildString {
        append(XML_DECL)
        append("<styleSheet xmlns=\"$MAIN_NS\">")
        append("<numFmts count=\"").append(NumberKind.entries.size).append("\">")
        NumberKind.entries.forEachIndexed { index, kind ->
            append("<numFmt numFmtId=\"").append(164 + index).append("\" formatCode=\"")
            append(escape(kind.formatCode)).append("\"/>")
        }
        append("</numFmts>")
        append("<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>")
        append("<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>")
        append("<fill><patternFill patternType=\"gray125\"/></fill></fills>")
        append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        append("<cellXfs count=\"").append(NumberKind.entries.size + 1).append("\">")
        append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        NumberKind.entries.forEachIndexed { index, _ ->
            append("<xf numFmtId=\"").append(164 + index)
            append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        }
        append("</cellXfs>")
        append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        append("</styleSheet>")
    }
}
