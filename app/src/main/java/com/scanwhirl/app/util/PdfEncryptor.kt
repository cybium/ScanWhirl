package com.scanwhirl.app.util

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Protects single-revision, classic-xref PDFs with the standard V5/R6 AESV3 handler. */
object PdfEncryptor {
    internal class Obj(val number: Int, val start: Int, val end: Int, val gen: Int = 0)

    private val latin = Charsets.ISO_8859_1
    private const val MAX_OBJECTS = 100_000
    private const val MAX_NESTING = 64
    private const val MAX_OBJECT_SYNTAX = 4 * 1024 * 1024
    private const val MAX_NODES_PER_OBJECT = 64_000
    private val headerPattern = Regex("%PDF-(1\\.[0-7]|2\\.0)(?:\\r\\n|\\r|\\n)")
    private val tailPattern = Regex(
        "(?:\\r\\n|\\r|\\n)startxref[ \\t]*(?:\\r\\n|\\r|\\n)" +
            "([0-9]{1,15})[ \\t]*(?:\\r\\n|\\r|\\n)%%EOF[\\x00\\t\\n\\f\\r ]*\\z"
    )
    private val xrefEntryPattern = Regex("([0-9]{10}) ([0-9]{5}) ([nf])(?: \\n| \\r|\\r\\n)")
    private val integer = Regex("[+-]?[0-9]+")
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
    private val trailerKeys = setOf("Size", "Root", "Info", "ID")
    private val unsupportedNames = setOf("XRef", "ObjStm", "Crypt", "Sig", "DocTimeStamp", "ByteRange")

    private fun unsupported(): Nothing = throw IOException("Not a PDF ScanWhirl can protect")

    /**
     * Never modifies [src]. The private temporary output is published only after all objects have
     * parsed, all strings and streams have been encrypted, and the fsynced bytes have been checked.
     */
    fun encrypt(src: File, dst: File, userPassword: String) {
        require(src.isFile && src.length() > 0) { "PDF is empty" }
        require(userPassword.isNotEmpty()) { "Password must not be empty" }
        require(src.canonicalFile != dst.canonicalFile) { "Protect needs a separate output file" }
        // Input, Latin-1 view, and per-stream cipher buffers are all in memory. Output is streamed.
        val maxInputBytes = minOf(PdfImport.MAX_BYTES, Runtime.getRuntime().maxMemory() / 8)
        if (src.length() > maxInputBytes) unsupported()
        val raw = src.readBytes()
        val text = String(raw, latin)
        val layout = readXref(text)
        if (layout.entries.size == MAX_OBJECTS) unsupported() // reserve a slot for /Encrypt
        val encryption = PdfAes.create(userPassword)
        val derived = PdfAes.userKey(userPassword, encryption.u, encryption.ue)
        if (derived == null || !derived.contentEquals(encryption.fileKey)) {
            throw IOException("The password could not be verified before writing")
        }

        val parent = dst.absoluteFile.parentFile ?: throw IOException("No folder for ${dst.name}")
        parent.mkdirs()
        val tmp = File.createTempFile("protect-", ".tmp", parent)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { file ->
                val buffered = BufferedOutputStream(DigestOutputStream(file, digest), 64 * 1024)
                val out = PdfOutput(buffered)
                rewrite(raw, text, layout, encryption, out)
                buffered.flush()
                Durable.syncStream(file, tmp)
            }
            if (!Sha256.of(tmp).equals(PdfAes.hex(digest.digest()), ignoreCase = true)) {
                throw IOException("Protection could not be verified")
            }
            if (!tmp.renameTo(dst)) throw IOException("Could not publish ${dst.name}")
            Durable.syncDir(parent)
        } finally {
            tmp.delete()
        }
    }

    private class PdfOutput(private val output: OutputStream) {
        var position = 0L
            private set

        fun write(bytes: ByteArray) {
            output.write(bytes)
            position += bytes.size
        }

        fun write(text: String) = write(text.toByteArray(latin))
    }

    private fun rewrite(
        raw: ByteArray, text: String, layout: Layout, encryption: PdfAes.Encryption, out: PdfOutput
    ) {
        // Drop comments and never copy arbitrary gaps, unindexed objects or old trailer bytes.
        if (layout.trailer.values.keys.any { it !in trailerKeys }) unsupported()
        val root = layout.trailer.values["Root"] as? Ref ?: unsupported()
        val info = layout.trailer.values["Info"]?.let { it as? Ref ?: unsupported() }
        val id = layout.trailer.values["ID"]
        if (id != null && (id !is Arr || id.values.size != 2 || id.values.any { it !is Bytes })) unsupported()
        layout.checkRef(root)
        info?.let(layout::checkRef)

        // AESV3 is defined by the Adobe PDF 1.7 extension and by ISO PDF 2.0. Advertise the
        // standard extension on a 1.x catalog so older readers can recognise the encryption.
        out.write("%PDF-${layout.outputVersion}\n%âãÏÓ\n")
        val offsets = LongArray(layout.entries.size)
        var pageTree: Ref? = null
        val dictTypes = mutableMapOf<Int, String>()
        for (obj in layout.objects) {
            val parsed = readObject(text, obj)
            checkValue(parsed.value, layout)
            val dict = parsed.value as? Dict
            val type = (dict?.values?.get("Type") as? Name)?.decoded
            if (type != null) dictTypes[obj.number] = type
            if (obj.number == root.number) {
                if (type != "Catalog" || parsed.body != null || dict == null) unsupported()
                pageTree = dict.values["Pages"] as? Ref ?: unsupported()
                if (layout.outputVersion == "1.7") {
                    val extensions = when (val current = dict.values["Extensions"]) {
                        null -> Dict(linkedMapOf())
                        is Dict -> current
                        else -> unsupported()
                    }
                    val adobe = Dict(linkedMapOf("BaseVersion" to Name("1.7", "1.7"), "ExtensionLevel" to Atom("8")))
                    val existing = extensions.values["ADBE"]
                    if (existing == null) extensions.values["ADBE"] = adobe
                    else {
                        val fields = (existing as? Dict)?.values ?: unsupported()
                        if ((fields["BaseVersion"] as? Name)?.decoded != "1.7") unsupported()
                        val level = (fields["ExtensionLevel"] as? Atom)?.text?.toIntOrNull() ?: unsupported()
                        if (level < 8) fields["ExtensionLevel"] = Atom("8")
                    }
                    dict.values["Extensions"] = extensions
                }
            }
            if (obj.number == info?.number && (dict == null || parsed.body != null)) unsupported()
            offsets[obj.number] = out.position
            out.write("${obj.number} ${obj.gen} obj\n")
            if (parsed.body != null) {
                val plain = raw.copyOfRange(parsed.body.first, parsed.body.last + 1)
                val encrypted = protectBytes(plain, encryption)
                (dict ?: unsupported()).values["Length"] = Atom(encrypted.size.toString())
                writeValue(out, dict, encryption)
                out.write("\nstream\n")
                out.write(encrypted)
                out.write("\nendstream")
            } else {
                writeValue(out, parsed.value, encryption)
            }
            out.write("\nendobj\n")
        }
        val pages = pageTree ?: unsupported()
        if (dictTypes[pages.number] != "Pages") unsupported()

        // The encryption dictionary and trailer ID are the only unencrypted strings we emit.
        val encryptNumber = layout.entries.size
        val encryptOffset = out.position
        out.write("$encryptNumber 0 obj\n${encryption.encryptDict()}\nendobj\n")
        val xrefOffset = out.position
        if (xrefOffset > 9_999_999_999L) unsupported()
        val size = encryptNumber + 1
        out.write("xref\n0 $size\n")
        // Keep generations and reconstruct the free list; no free object's original bytes are copied.
        val free = layout.entries.indices.filter { !layout.entries[it].inUse } + 0
        var nextFree = 1
        for ((index, entry) in layout.entries.withIndex()) {
            if (entry.inUse) out.write(String.format(Locale.ROOT, "%010d %05d n \n", offsets[index], entry.gen))
            else out.write(String.format(Locale.ROOT, "%010d %05d f \n", free[nextFree++], entry.gen))
        }
        out.write(String.format(Locale.ROOT, "%010d 00000 n \n", encryptOffset))
        val newId = PdfAes.hex(encryption.fileId)
        val infoEntry = info?.let { " /Info ${it.number} ${it.gen} R" }.orEmpty()
        out.write("trailer\n<< /Size $size /Root ${root.number} ${root.gen} R$infoEntry " +
            "/Encrypt $encryptNumber 0 R /ID [<$newId> <$newId>] >>\nstartxref\n$xrefOffset\n%%EOF\n")
    }

    private fun protectBytes(plain: ByteArray, encryption: PdfAes.Encryption): ByteArray {
        // AESV3 uses exactly the same IV-prefixed CBC/PKCS#7 operation for strings and streams.
        val cipher = encryption.encryptStream(plain)
        if (!encryption.decryptStream(cipher).contentEquals(plain)) throw IOException("Protection could not be verified")
        return cipher
    }

    private sealed interface Value
    private class Dict(val values: LinkedHashMap<String, Value>) : Value
    private class Arr(val values: List<Value>) : Value
    private class Name(val encoded: String, val decoded: String) : Value
    private class Atom(val text: String) : Value
    private class Ref(val number: Int, val gen: Int) : Value
    private class Bytes(val bytes: ByteArray) : Value

    private fun checkValue(value: Value, layout: Layout) {
        when (value) {
            is Ref -> layout.checkRef(value)
            is Name -> if (value.decoded in unsupportedNames) unsupported()
            is Arr -> value.values.forEach { checkValue(it, layout) }
            is Dict -> for ((key, child) in value.values) {
                if (key in unsupportedNames) unsupported()
                checkValue(child, layout)
            }
            else -> Unit
        }
    }

    private fun writeValue(out: PdfOutput, value: Value, encryption: PdfAes.Encryption) {
        when (value) {
            is Atom -> out.write(value.text)
            is Name -> out.write("/${value.encoded}")
            is Ref -> out.write("${value.number} ${value.gen} R")
            is Bytes -> out.write("<${PdfAes.hex(protectBytes(value.bytes, encryption))}>")
            is Arr -> {
                out.write("[")
                value.values.forEachIndexed { index, child ->
                    if (index > 0) out.write(" ")
                    writeValue(out, child, encryption)
                }
                out.write("]")
            }
            is Dict -> {
                out.write("<<")
                for ((key, child) in value.values) {
                    // Re-escape decoded names, including delimiter bytes and '#'.
                    out.write(" /${escapeName(key)} ")
                    writeValue(out, child, encryption)
                }
                out.write(" >>")
            }
        }
    }

    private fun escapeName(decoded: String): String = buildString {
        val digits = "0123456789ABCDEF"
        for (c in decoded) {
            if (c in '!'..'~' && !delimiter(c) && c != '#') append(c)
            else append('#').append(digits[c.code shr 4]).append(digits[c.code and 15])
        }
    }

    private class Entry(val offset: Long, val gen: Int, val inUse: Boolean)
    private class Layout(
        val outputVersion: String, val entries: List<Entry>, val objects: List<Obj>, val trailer: Dict
    ) {
        fun checkRef(ref: Ref) {
            val entry = entries.getOrNull(ref.number) ?: unsupported()
            if (!entry.inUse || entry.gen != ref.gen) unsupported()
        }
    }

    private fun readXref(text: String): Layout {
        val header = headerPattern.matchAt(text, 0) ?: unsupported()
        val tailStart = (text.length - 1024).coerceAtLeast(0)
        val tail = tailPattern.find(text.substring(tailStart)) ?: unsupported()
        val trailerEnd = tailStart + tail.range.first + when {
            tail.value.startsWith("\r\n") -> 2
            else -> 1
        }
        val xrefAt = tail.groupValues[1].toLong()
        if (xrefAt !in header.value.length.toLong() until trailerEnd.toLong()) unsupported()
        if (!whitespace(text[xrefAt.toInt() - 1])) unsupported()
        val xref = Cursor(text, xrefAt.toInt(), trailerEnd)
        xref.expect("xref")
        xref.skip()
        val first = xref.word().toIntOrNull()
        xref.skip()
        val count = xref.word().toIntOrNull() ?: unsupported()
        if (first != 0 || count !in 2..MAX_OBJECTS) unsupported()
        while (xref.peek() == ' ' || xref.peek() == '\t') xref.pos++
        xref.eol()
        if (20L * count > trailerEnd - xref.pos) unsupported()
        val entries = List(count) { index ->
            val match = xrefEntryPattern.matchAt(text, xref.pos) ?: unsupported()
            xref.pos += 20
            val offset = match.groupValues[1].toLong()
            val gen = match.groupValues[2].toInt()
            val live = match.groupValues[3] == "n"
            if (gen > 65535) unsupported()
            if (live) {
                if (index == 0 || gen == 65535 || offset !in header.value.length.toLong() until xrefAt) unsupported()
                if (!whitespace(text[offset.toInt() - 1])) unsupported()
            } else if (offset >= count || (index == 0 && gen != 65535)) unsupported()
            Entry(offset, gen, live)
        }
        xref.skip()
        xref.expect("trailer") // another subsection or an xref stream is unsupported
        xref.skip()
        val trailer = xref.value() as? Dict ?: unsupported()
        xref.skip()
        if (xref.pos != trailerEnd || (trailer.values["Size"] as? Atom)?.text?.toIntOrNull() != count) unsupported()

        val live = entries.indices.filter { entries[it].inUse }.sortedBy { entries[it].offset }
        if (live.isEmpty()) unsupported()
        val objects = live.mapIndexed { i, n ->
            val start = entries[n].offset.toInt()
            val end = if (i + 1 < live.size) entries[live[i + 1]].offset.toInt() else xrefAt.toInt()
            if (start >= end) unsupported()
            Obj(n, start, end, entries[n].gen)
        }
        // Nothing but whitespace and comments is allowed outside indexed objects.
        val prefix = Cursor(text, header.value.length, objects.first().start)
        prefix.skip()
        if (prefix.pos != objects.first().start) unsupported()
        return Layout(if (header.groupValues[1] == "2.0") "2.0" else "1.7", entries, objects, trailer)
    }

    private class Parsed(val value: Value, val body: IntRange?)

    private fun readObject(text: String, obj: Obj): Parsed {
        val c = Cursor(text, obj.start, obj.end)
        c.expectHeader(obj)
        c.skip()
        val value = c.value()
        c.skip()
        val stream = if (c.hasToken("stream")) {
            val dict = value as? Dict ?: unsupported()
            if (dict.values.keys.any { it == "F" || it == "FFilter" || it == "FDecodeParms" }) unsupported()
            val length = (dict.values["Length"] as? Atom)?.text?.toLongOrNull() ?: unsupported()
            c.expect("stream")
            c.eol(stream = true)
            val start = c.pos
            if (length !in 0L..(obj.end - start).toLong()) unsupported()
            c.pos += length.toInt()
            val body = start until c.pos
            if (c.peek() == '\r' || c.peek() == '\n') c.eol()
            // The legacy ScanWhirl writer includes its last LF inside /Length for content streams.
            else if (c.pos == start || text[c.pos - 1] != '\n') unsupported()
            c.expect("endstream")
            c.skip()
            body
        } else null
        c.expect("endobj")
        c.skip()
        if (c.pos != obj.end) unsupported()
        return Parsed(value, stream)
    }

    private fun whitespace(c: Char) = c == '\u0000' || c == '\t' || c == '\n' || c == '\u000c' || c == '\r' || c == ' '
    private fun delimiter(c: Char) = c in "()<>[]{}/%"
    private fun boundary(c: Char) = whitespace(c) || delimiter(c)
    private fun hex(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> unsupported()
    }

    /** Token scanner with PDF's byte grammar. Strings are decoded to bytes, not Unicode. */
    private class Cursor(private val text: String, var pos: Int, private val end: Int) {
        private var syntaxStart = pos
        private var nodes = 0

        fun peek(): Char? = if (pos < end) text[pos] else null
        private fun starts(s: String) = end - pos >= s.length && text.startsWith(s, pos)
        fun skip() {
            while (pos < end) {
                when {
                    whitespace(text[pos]) -> pos++
                    text[pos] == '%' -> {
                        while (pos < end && text[pos] != '\r' && text[pos] != '\n') pos++
                        if (pos == end) unsupported()
                    }
                    else -> return
                }
            }
        }

        fun hasToken(s: String): Boolean = starts(s) &&
            (pos + s.length == end || boundary(text[pos + s.length]))

        fun expect(s: String) {
            if (!hasToken(s)) unsupported()
            pos += s.length
        }

        fun expectHeader(obj: Obj) {
            if (word().toIntOrNull() != obj.number) unsupported()
            skip()
            if (word().toIntOrNull() != obj.gen) unsupported()
            skip()
            expect("obj")
        }

        fun eol(stream: Boolean = false) {
            if (starts("\r\n")) pos += 2
            else if (peek() == '\n' || (!stream && peek() == '\r')) pos++
            else unsupported()
        }

        fun word(): String {
            val start = pos
            while (pos < end && !boundary(text[pos])) {
                if (pos - start >= 128) unsupported()
                pos++
            }
            if (pos == start) unsupported()
            return text.substring(start, pos)
        }

        private fun readName(): Name {
            if (peek() != '/') unsupported()
            val start = ++pos
            val decoded = StringBuilder()
            while (pos < end && !boundary(text[pos])) {
                if (pos - start >= 4096) unsupported()
                val c = text[pos++]
                if (c == '#') {
                    if (end - pos < 2) unsupported()
                    val b = hex(text[pos++]) * 16 + hex(text[pos++])
                    if (b == 0) unsupported()
                    decoded.append(b.toChar())
                } else decoded.append(c)
            }
            return Name(text.substring(start, pos), decoded.toString())
        }

        fun value(depth: Int = 0): Value {
            if (depth == 0) syntaxStart = pos
            if (++nodes > MAX_NODES_PER_OBJECT || depth > MAX_NESTING || pos - syntaxStart > MAX_OBJECT_SYNTAX) {
                unsupported()
            }
            return when {
                starts("<<") -> {
                    pos += 2
                    val fields = linkedMapOf<String, Value>()
                    while (true) {
                        skip()
                        if (starts(">>")) {
                            pos += 2
                            break
                        }
                        val key = readName().decoded
                        skip()
                        if (fields.put(key, value(depth + 1)) != null) unsupported()
                    }
                    Dict(fields)
                }
                peek() == '[' -> {
                    pos++
                    val nodes = mutableListOf<Value>()
                    while (true) {
                        skip()
                        if (peek() == ']') {
                            pos++
                            break
                        }
                        nodes += value(depth + 1)
                    }
                    Arr(nodes)
                }
                peek() == '/' -> readName()
                peek() == '(' -> Bytes(literal())
                peek() == '<' -> Bytes(hexString())
                else -> atomOrRef()
            }
        }

        private fun atomOrRef(): Value {
            val word = word()
            if (word == "true" || word == "false" || word == "null") return Atom(word)
            if (!number.matches(word)) unsupported()
            if (!integer.matches(word)) return Atom(word)
            val afterWord = pos
            skip()
            if (peek()?.let { it in '0'..'9' || it == '+' || it == '-' } == true) {
                val second = word()
                skip()
                if (hasToken("R")) {
                    if (!integer.matches(second)) unsupported()
                    pos++
                    return Ref(word.toIntOrNull() ?: unsupported(), second.toIntOrNull() ?: unsupported())
                }
            }
            pos = afterWord
            return Atom(word)
        }

        private fun readByte(): Char {
            if (pos >= end || pos - syntaxStart > MAX_OBJECT_SYNTAX) unsupported()
            return text[pos++]
        }

        private fun hexString(): ByteArray {
            pos++
            val bytes = ByteArrayOutputStream()
            var high = -1
            while (true) {
                val c = readByte()
                if (c == '>') {
                    if (high >= 0) bytes.write(high shl 4)
                    return bytes.toByteArray()
                }
                if (whitespace(c)) continue
                val nibble = hex(c)
                if (high < 0) high = nibble
                else {
                    bytes.write((high shl 4) or nibble)
                    high = -1
                }
            }
        }

        private fun literal(): ByteArray {
            pos++
            val bytes = ByteArrayOutputStream()
            var parentheses = 1
            while (true) {
                when (val c = readByte()) {
                    '(' -> { parentheses++; bytes.write(c.code) }
                    ')' -> if (--parentheses == 0) return bytes.toByteArray() else bytes.write(c.code)
                    '\r', '\n' -> {
                        if (c == '\r' && peek() == '\n') pos++
                        bytes.write('\n'.code)
                    }
                    '\\' -> when (val next = readByte()) {
                        '\r' -> if (peek() == '\n') pos++
                        '\n' -> Unit
                        'n' -> bytes.write('\n'.code)
                        'r' -> bytes.write('\r'.code)
                        't' -> bytes.write('\t'.code)
                        'b' -> bytes.write('\b'.code)
                        'f' -> bytes.write(12)
                        in '0'..'7' -> {
                            var octal = next - '0'
                            repeat(2) {
                                val digit = peek()
                                if (digit != null && digit in '0'..'7') {
                                    octal = octal * 8 + (digit - '0')
                                    pos++
                                }
                            }
                            bytes.write(octal)
                        }
                        else -> bytes.write(next.code) // unrecognised escape: ignore the slash
                    }
                    else -> bytes.write(c.code)
                }
            }
        }
    }

    /** Classic xref regions shared with the legacy decryptor; bad entries are never dropped. */
    internal fun objects(raw: ByteArray): List<Obj> = try {
        val text = String(raw, latin)
        readXref(text).objects.onEach {
            Cursor(text, it.start, it.end).expectHeader(it)
        }
    } catch (_: IOException) {
        emptyList()
    }

    internal fun objectStreamBodies(raw: ByteArray): List<Pair<Int, IntRange>> {
        val text = String(raw, latin)
        return objects(raw).mapNotNull { obj ->
            // Legacy generated encrypted PDFs omit the LF after encrypted content. Keep the
            // existing verifier helper compatible with those fixtures only; Protect never does.
            val marker = "\nstream\n"
            val at = text.indexOf(marker, obj.start)
            if (at !in obj.start until obj.end) return@mapNotNull null
            val from = at + marker.length
            val length = Regex("/Length ([0-9]+)").find(text.substring(obj.start, at))
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            if (from > obj.end || length > obj.end - from) return@mapNotNull null
            obj.number to (from until from + length)
        }
    }
}
