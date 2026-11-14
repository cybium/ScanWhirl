package com.scanwhirl.app.util

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bounded, random-access structural verifier for PDFs written by [PdfGenerator].
 *
 * It never reads the whole file. Everything it trusts is *read from the file*
 * (xref offsets, `/Kids`, `/Count`, `/Length`), not recomputed from the
 * writer's own counters, so a writer bug cannot make it pass vacuously:
 *
 *  1. `%PDF-1.` header; `startxref N` + `%%EOF` terminator at the tail.
 *  2. The xref table at `N`: entry count, 20-byte entries, free entry 0,
 *     every in-use offset inside the file, before the table, and unique.
 *  3. The trailer: `/Size` equals the xref count, `/Root` is a valid object.
 *  4. Every referenced object really starts with `<num> 0 obj` at its offset.
 *  5. Catalog → page tree: `/Kids` cardinality == `/Count` == expected pages,
 *     all kids distinct, in range, and each a `/Type /Page` whose `/Parent` is
 *     the tree root.
 *  6. Per page: `/MediaBox`, a content stream that draws that page's image,
 *     and an image stream whose declared `/Length` ends exactly on `endstream`
 *     inside its own object. The image starts with a JPEG SOI.
 *  7. When [expectedImages] is given, the embedded DCT streams are compared
 *     byte-for-byte (chunked) with the expected JPEG files, in order.
 *
 * It does NOT prove a viewer renders the page; Android `PdfRenderer` and an
 * external parser remain separate acceptance checks.
 *
 * Memory: object bodies are capped (page tree 64 MiB, page dictionary 64 KiB,
 * content stream 16 MiB); image data is streamed in 64 KiB chunks. There is
 * deliberately no page-count ceiling.
 */
object PdfVerifier {

    class InvalidPdfException(message: String) : IOException(message)

    private const val MAX_TREE_OBJECT = 64L shl 20
    private const val MAX_PAGE_OBJECT = 64L * 1024
    private const val MAX_CONTENT_STREAM = 16L shl 20
    private const val CHUNK = 64 * 1024

    /**
     * @param expectedPages required page count; when null it is taken from [expectedImages].
     * @param decryption when the file is AES-encrypted (v2 Protect PDF), the key that opens its
     *   streams; the content and image checks then run on the decrypted bytes.
     * @throws InvalidPdfException when the file is not a structurally sound PDF of that shape.
     */
    fun verify(
        file: File,
        expectedPages: Int? = null,
        expectedImages: List<File>? = null,
        decryption: PdfAes.Decryption? = null
    ) {
        val expected = expectedImages?.size ?: expectedPages
        if (expectedPages != null && expectedImages != null && expectedPages != expectedImages.size) {
            throw InvalidPdfException("Expected page count disagrees with the page files")
        }
        if (!file.isFile) throw InvalidPdfException("PDF is missing")
        RandomAccessFile(file, "r").use { Checker(it, expected, expectedImages, decryption).run() }
    }

    private fun fail(message: String): Nothing = throw InvalidPdfException(message)

    private class Checker(
        private val raf: RandomAccessFile,
        private val expectedPages: Int?,
        private val expectedImages: List<File>?,
        private val decryption: PdfAes.Decryption?
    ) {
        private val size = raf.length()
        private lateinit var offsets: LongArray // index = object number; 0 unused
        private lateinit var boundaries: LongArray // sorted object offsets + xref offset
        private var xrefOffset = 0L

        private fun readAt(offset: Long, length: Int): ByteArray {
            if (offset < 0 || length < 0 || offset + length > size) fail("PDF is truncated")
            val buf = ByteArray(length)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }

        private fun textAt(offset: Long, length: Int) = String(readAt(offset, length), Charsets.ISO_8859_1)

        fun run() {
            if (size < 100) fail("PDF is too small")
            if (!textAt(0, 8).startsWith("%PDF-1.")) fail("Not a PDF")

            val tailLen = minOf(size, 64L).toInt()
            val tail = textAt(size - tailLen, tailLen)
            val start = Regex("startxref\\n(\\d{1,15})\\n%%EOF\\n\\z").find(tail)
                ?: fail("PDF is not terminated by startxref/%%EOF")
            xrefOffset = start.groupValues[1].toLong()
            if (xrefOffset <= 0 || xrefOffset >= size) fail("startxref points outside the file")

            readXref()
            val root = readTrailer()

            val catalog = body(root, 4096)
            if (!catalog.contains("/Type /Catalog")) fail("Root is not a catalog")
            val pagesNum = Regex("/Pages (\\d+) 0 R").find(catalog)?.groupValues?.get(1)?.toIntOrNull()
                ?: fail("Catalog has no page tree")
            checkObject(pagesNum)

            val tree = body(pagesNum, MAX_TREE_OBJECT)
            if (!tree.contains("/Type /Pages")) fail("Page tree root is not /Type /Pages")
            val count = Regex("/Count (\\d+)").find(tree)?.groupValues?.get(1)?.toIntOrNull()
                ?: fail("Page tree has no /Count")
            val kidsText = Regex("/Kids \\[([^\\]]*)]").find(tree)?.groupValues?.get(1)
                ?: fail("Page tree has no /Kids")
            val kids = Regex("(\\d+) 0 R").findAll(kidsText).map { it.groupValues[1].toInt() }.toList()
            if (kids.size != count) fail("/Kids has ${kids.size} entries but /Count is $count")
            if (count == 0) fail("PDF has no pages")
            if (expectedPages != null && count != expectedPages) fail("Page count mismatch: $count != $expectedPages")

            val used = HashSet<Int>().apply { add(root); add(pagesNum) }
            kids.forEachIndexed { index, pageNum -> checkPage(index, pageNum, pagesNum, used) }
        }

        // -------- xref / trailer -------------------------------------------------

        private fun readXref() {
            val head = textAt(xrefOffset, minOf(64L, size - xrefOffset).toInt())
            val header = Regex("^xref\\n0 (\\d{1,9})\\n").find(head) ?: fail("Missing xref table")
            val n = header.groupValues[1].toInt()
            if (n < 4) fail("xref table is too small")
            val entriesStart = xrefOffset + header.value.length
            val entriesEnd = entriesStart + 20L * n
            if (entriesEnd > size) fail("xref table runs past the end of the file")

            offsets = LongArray(n)
            var index = 0
            var pos = entriesStart
            val chunkEntries = 1024
            while (index < n) {
                val take = minOf(chunkEntries, n - index)
                val bytes = readAt(pos, take * 20)
                for (e in 0 until take) {
                    parseEntry(bytes, e * 20, index)
                    index++
                }
                pos += take * 20L
            }
            // Offsets must be unique (no two objects share a location).
            val sorted = offsets.copyOfRange(1, n).also { it.sort() }
            for (i in 1 until sorted.size) if (sorted[i] == sorted[i - 1]) fail("Two xref entries share an offset")
            boundaries = sorted + xrefOffset
            trailerStart = entriesEnd
        }

        private var trailerStart = 0L

        private fun parseEntry(b: ByteArray, at: Int, number: Int) {
            fun digits(from: Int, len: Int): Long {
                var v = 0L
                for (i in from until from + len) {
                    val c = b[at + i].toInt()
                    if (c < '0'.code || c > '9'.code) fail("Malformed xref entry $number")
                    v = v * 10 + (c - '0'.code)
                }
                return v
            }
            val type = b[at + 17].toInt().toChar()
            val eol = String(b, at + 18, 2, Charsets.ISO_8859_1)
            if (b[at + 10].toInt() != ' '.code || b[at + 16].toInt() != ' '.code ||
                eol != " \n" && eol != " \r" && eol != "\r\n"
            ) fail("Malformed xref entry $number")
            val offset = digits(0, 10)
            val generation = digits(11, 5)
            if (number == 0) {
                if (type != 'f' || generation != 65535L) fail("xref entry 0 must be the free head")
                return
            }
            if (type != 'n') fail("xref entry $number is not in use")
            if (offset < 9 || offset >= xrefOffset) fail("xref entry $number points outside the object area")
            offsets[number] = offset
        }

        /** Returns the /Root object number. */
        private fun readTrailer(): Int {
            val remaining = size - trailerStart
            if (remaining <= 0 || remaining > 4096) fail("Unexpected data after the xref table")
            val text = textAt(trailerStart, remaining.toInt())
            // The dictionary may carry a nested `/Encrypt << … >>` (AES), so split on the trailer's
            // own delimiters instead of matching a flat `[^>]*` body.
            if (!text.startsWith("trailer\n")) fail("Malformed trailer")
            val tailAt = text.lastIndexOf("\nstartxref\n")
            if (tailAt < 0) fail("Malformed trailer")
            val dict = text.substring("trailer\n".length, tailAt)
            if (!dict.startsWith("<<") || !dict.endsWith(">>")) fail("Malformed trailer")
            val after = text.substring(tailAt + 1)
            val match = Regex("^startxref\\n(\\d+)\\n%%EOF\\n\\z").find(after)
                ?: fail("Malformed trailer")
            if (match.groupValues[1].toLong() != xrefOffset) fail("startxref disagrees with the trailer")
            val sizeEntry = Regex("/Size (\\d+)").find(dict)?.groupValues?.get(1)?.toInt()
            if (sizeEntry != offsets.size) fail("Trailer /Size $sizeEntry != xref count ${offsets.size}")
            val root = Regex("/Root (\\d+) 0 R").find(dict)?.groupValues?.get(1)?.toInt()
                ?: fail("Trailer has no /Root")
            checkObject(root)
            return root
        }

        // -------- objects --------------------------------------------------------

        private fun checkObject(num: Int) {
            if (num < 1 || num >= offsets.size) fail("Reference to object $num is outside the xref table")
        }

        private fun boundaryAfter(offset: Long): Long {
            var lo = 0
            var hi = boundaries.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (boundaries[mid] > offset) hi = mid else lo = mid + 1
            }
            if (boundaries[lo] <= offset) fail("Object boundary lookup failed")
            return boundaries[lo]
        }

        /** Verifies `<num> 0 obj\n` really sits at the xref offset; returns the body start. */
        private fun bodyStart(num: Int): Long {
            checkObject(num)
            val off = offsets[num]
            val head = textAt(off, minOf(32L, size - off).toInt())
            val prefix = "$num 0 obj\n"
            if (!head.startsWith(prefix)) fail("xref offset for object $num does not point at '$prefix'")
            return off + prefix.length
        }

        /** Whole body of a non-stream object, ending in `endobj`. */
        private fun body(num: Int, cap: Long): String {
            val start = bodyStart(num)
            val end = boundaryAfter(offsets[num])
            val length = end - start
            if (length <= 0 || length > cap) fail("Object $num has an implausible size")
            val text = textAt(start, length.toInt())
            if (!text.trimEnd().endsWith("endobj")) fail("Object $num is not terminated by endobj")
            return text
        }

        private class Stream(val dict: String, val dataStart: Long, val length: Long)

        /** Reads a stream body; when the file is AES-encrypted, decrypts it first. */
        private fun streamBytes(s: Stream): ByteArray {
            if (s.length > Int.MAX_VALUE.toLong() - 1) fail("Stream is implausibly large")
            val raw = readAt(s.dataStart, s.length.toInt())
            val dec = decryption ?: return raw
            return try {
                dec.decryptStream(raw)
            } catch (e: java.io.IOException) {
                fail("AES stream does not decrypt: ${e.message}")
            }
        }

        /** Dictionary + data bounds of a stream object, proving `/Length` ends exactly on `endstream`. */
        private fun stream(num: Int, eolBeforeEndstream: Boolean): Stream {
            val start = bodyStart(num)
            val objectEnd = boundaryAfter(offsets[num])
            val head = textAt(start, minOf(2048L, objectEnd - start).toInt())
            val marker = head.indexOf(">>\nstream\n")
            if (marker < 0) fail("Object $num is not a stream")
            val dict = head.substring(0, marker + 2)
            val length = Regex("/Length (\\d+)").find(dict)?.groupValues?.get(1)?.toLongOrNull()
                ?: fail("Stream $num has no /Length")
            val dataStart = start + marker + ">>\nstream\n".length
            val dataEnd = dataStart + length
            if (dataEnd > objectEnd) fail("Stream $num /Length runs past its object")
            val trailLen = minOf(40L, size - dataEnd).toInt()
            if (trailLen <= 0) fail("Stream $num is truncated")
            val trail = textAt(dataEnd, trailLen)
            val skip = when {
                trail.startsWith("\r\n") -> 2
                trail.startsWith("\n") || trail.startsWith("\r") -> 1
                else -> 0
            }
            // This writer puts an EOL between image data and `endstream` (not part of /Length) and none
            // after a content stream (which ends in its own newline). Holding both to the writer's exact
            // layout means /Length that is one too long OR one too short is rejected.
            if (eolBeforeEndstream != (skip > 0)) fail("Stream $num /Length is off by the EOL before 'endstream'")
            if (!trail.startsWith("endstream", skip)) fail("Stream $num /Length does not end on 'endstream'")
            val afterStream = skip + "endstream".length
            val objMark = trail.indexOf("endobj", afterStream)
            if (objMark < 0 || trail.substring(afterStream, objMark).isNotBlank()) {
                fail("Stream $num is not terminated by endobj")
            }
            if (dataEnd + objMark + "endobj".length > objectEnd) fail("Stream $num overruns its object")
            return Stream(dict, dataStart, length)
        }

        private fun checkPage(index: Int, pageNum: Int, treeNum: Int, used: MutableSet<Int>) {
            checkObject(pageNum)
            if (!used.add(pageNum)) fail("Page object $pageNum is listed twice")
            val page = body(pageNum, MAX_PAGE_OBJECT)
            if (!Regex("/Type /Page(?![a-z])").containsMatchIn(page)) fail("Kid $pageNum is not a /Type /Page")
            val parent = Regex("/Parent (\\d+) 0 R").find(page)?.groupValues?.get(1)?.toInt()
            if (parent != treeNum) fail("Page $pageNum has the wrong /Parent")
            val box = Regex("/MediaBox \\[0 0 (\\d+) (\\d+)]").find(page) ?: fail("Page $pageNum has no /MediaBox")
            if (box.groupValues[1].toLong() <= 0 || box.groupValues[2].toLong() <= 0) fail("Page $pageNum has an empty /MediaBox")
            val contentNum = Regex("/Contents (\\d+) 0 R").find(page)?.groupValues?.get(1)?.toInt()
                ?: fail("Page $pageNum has no /Contents")
            val image = Regex("/XObject << /Im(\\d+) (\\d+) 0 R").find(page) ?: fail("Page $pageNum has no image")
            if (image.groupValues[1].toInt() != index) fail("Page $pageNum draws the wrong image slot")
            val imageNum = image.groupValues[2].toInt()
            checkObject(contentNum)
            checkObject(imageNum)
            if (!used.add(contentNum) || !used.add(imageNum)) fail("Page $pageNum shares objects with another page")

            val content = stream(contentNum, eolBeforeEndstream = false)
            if (content.length > MAX_CONTENT_STREAM + 64) fail("Content stream of page $pageNum is too large")
            val drawingBytes = streamBytes(content)
            if (drawingBytes.size > MAX_CONTENT_STREAM) fail("Content stream of page $pageNum is too large")
            val drawing = String(drawingBytes, Charsets.ISO_8859_1)
            if (!drawing.contains("/Im$index Do")) fail("Content stream of page $pageNum does not draw its image")

            val img = stream(imageNum, eolBeforeEndstream = true)
            if (!img.dict.contains("/Subtype /Image") || !img.dict.contains("/Filter /DCTDecode")) {
                fail("Image $imageNum is not a DCT image")
            }
            if (img.length < 4) fail("Image $imageNum is empty")
            val imageBytes = if (decryption == null) null else streamBytes(img)
            if (imageBytes != null && imageBytes.size < 4) fail("Image $imageNum is empty")
            val soi = if (imageBytes != null) imageBytes.copyOfRange(0, 2) else readAt(img.dataStart, 2)
            if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) fail("Image $imageNum is not a JPEG")
            expectedImages?.get(index)?.let { expected ->
                if (imageBytes != null) {
                    if (!expected.isFile || expected.length() != imageBytes.size.toLong() ||
                        !expected.readBytes().contentEquals(imageBytes)
                    ) {
                        fail("Image $imageNum differs from its page file")
                    }
                } else {
                    compareImage(img, expected, imageNum)
                }
            }
        }

        private fun compareImage(img: Stream, expected: File, imageNum: Int) {
            if (!expected.isFile || expected.length() != img.length) {
                fail("Image $imageNum length ${img.length} differs from page file")
            }
            val a = ByteArray(CHUNK)
            val b = ByteArray(CHUNK)
            FileInputStream(expected).use { input ->
                var done = 0L
                while (done < img.length) {
                    val n = minOf(CHUNK.toLong(), img.length - done).toInt()
                    raf.seek(img.dataStart + done)
                    raf.readFully(a, 0, n)
                    var read = 0
                    while (read < n) {
                        val r = input.read(b, read, n - read)
                        if (r < 0) fail("Page file is shorter than expected")
                        read += r
                    }
                    for (i in 0 until n) if (a[i] != b[i]) fail("Image $imageNum differs from its page file")
                    done += n
                }
            }
        }
    }
}
