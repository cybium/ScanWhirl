package com.scanwhirl.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Removes the standard-security-handler password from a PDF, honestly and in memory.
 *
 * Supported, because real Indian bank statements and government PDFs still ship every one of these:
 *  - **RC4 40/128-bit** (V1/V2, R2/R3/R4) and **V4 with `/CFM /V2`**;
 *  - **AES-128** (V4, R4, `/CFM /AESV2`);
 *  - **AES-256** (V5, R5 with the single SHA-256 derivation and R6 with Algorithm 2.B).
 *
 * Crypt filters are resolved **by name** (`/StmF`, `/StrF`, `/EFF`, explicit `/Crypt`), so a file may
 * encrypt streams with one cipher and strings with another, or leave strings in the clear
 * (`/StrF /Identity`). `/EncryptMetadata false` metadata streams are copied, never "decrypted".
 *
 * **Fail closed.** The rewriter understands exactly the classic-xref shape it can reproduce
 * faithfully: a classic table, no `/Prev` incremental update, no `/XRefStm` hybrid, no xref stream,
 * no `/ObjStm` object stream, direct stream `/Length` values, stream bodies that end exactly where
 * `/Length` says and object headers that match their xref entries. Anything else throws
 * [Unsupported] with a plain message; nothing half-decrypted is ever returned. Work is bounded
 * (object count, dictionary depth, output growth) so a malformed file fails instead of exhausting
 * the phone.
 *
 * The original file is never touched: the caller gets a new plaintext PDF in app storage. Passwords
 * are only ever compared, never stored or logged.
 */
object PdfDecryptor {

    /** The password did not derive the file key (user or owner password). */
    class WrongPassword : IOException("Incorrect password")

    /** Not a PDF shape this offline decoder can rewrite faithfully (or too large to try). */
    class Unsupported(message: String) : IOException(message)

    /**
     * Largest protected PDF this phone can decrypt in memory, in bytes.
     *
     * [decrypt] needs the whole file at once and holds roughly five copies of it (the raw
     * bytes, an ISO-8859-1 `String` at 2 bytes per character, the output stream, and one
     * stream-sized copy per object), so a file may claim at most an eighth of the app's
     * maximum heap. Without this gate a 40-60 MB bank statement on a 3 GB phone throws an
     * [OutOfMemoryError], which kills the process instead of showing a message.
     */
    fun maxInMemoryBytes(heapBytes: Long = maxHeap()): Long =
        (heapBytes / HEAP_COPIES).coerceIn(MIN_IN_MEMORY_BYTES, PdfImport.MAX_BYTES)

    /** The heap the gate reads; replaceable in tests. */
    @Volatile
    internal var maxHeap: () -> Long = { Runtime.getRuntime().maxMemory() }

    internal fun resetForTests() {
        maxHeap = { Runtime.getRuntime().maxMemory() }
    }

    /**
     * Returns a decrypted copy of [raw]. Throws [WrongPassword] when neither the user nor the
     * owner password opens it, and [Unsupported] when the encryption or PDF shape is not handled.
     *
     * An empty [password] is a real user password, not a bypass: owner-only documents have one, and
     * it is verified exactly like any other, so it cannot open a file that needs a typed password.
     */
    fun decrypt(raw: ByteArray, password: String): ByteArray = try {
        decryptChecked(raw, password)
    } catch (e: WrongPassword) {
        throw e
    } catch (e: Unsupported) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw Unsupported(e.message ?: "This PDF cannot be opened offline")
    } catch (e: Exception) {
        // A malformed xref/dictionary must never surface as an IndexOutOfBounds to the UI.
        throw Unsupported("This PDF's password protection could not be read")
    }

    private fun decryptChecked(raw: ByteArray, password: String): ByteArray {
        val xref = readXref(raw)
        if (xref.entries.isEmpty()) throw Unsupported("This PDF has no objects ScanWhirl can read")
        if (xref.entries.size > MAX_OBJECTS) {
            throw Unsupported("This PDF has too many objects to decrypt safely")
        }

        val trailer = xref.trailer
        val trailerDict = trailer.dict ?: throw Unsupported("This PDF has no trailer ScanWhirl can read")
        if (trailerDict.containsKey("Prev")) {
            throw Unsupported("This PDF is an incremental update, which ScanWhirl cannot rewrite safely")
        }
        if (trailerDict.containsKey("XRefStm")) {
            throw Unsupported("This PDF mixes an xref stream with its table, which ScanWhirl cannot rewrite")
        }

        val objects = orderedObjects(raw, xref)

        val encryptionValue = trailerDict["Encrypt"]
            ?: throw Unsupported("This PDF does not use the standard password format")
        val encryptionDict: Pv
        val encryptObject: Int
        if (encryptionValue.dict != null) {
            encryptionDict = encryptionValue
            encryptObject = -1
        } else {
            val number = encryptionValue.refNumber
            val gen = encryptionValue.refGen
            if (number < 0) throw Unsupported("This PDF does not use the standard password format")
            val entry = objects.firstOrNull { it.number == number }
                ?: throw Unsupported("The PDF /Encrypt reference points at a missing object")
            if (entry.gen != gen) {
                throw Unsupported("The PDF /Encrypt reference does not match its object generation")
            }
            encryptionDict = objectDictionary(raw, entry)
            encryptObject = entry.number
        }

        val scheme = securityHandler(encryptionDict, trailer, password)
        val output = rewriter(raw, objects, xref, scheme, encryptObject)
        return output
    }

    // ------------------------------------------------------------------
    // Bounded PDF value parser (dictionaries, arrays, strings, names, refs)
    // ------------------------------------------------------------------

    /** One parsed value with its exact byte span; only the fields that apply are set. */
    private class Pv(val start: Int, var end: Int) {
        var dict: LinkedHashMap<String, Pv>? = null
        var array: List<Pv>? = null
        var name: String? = null
        var number: String? = null
        var bytes: ByteArray? = null
        var refNumber: Int = -1
        var refGen: Int = -1
        var keyword: String? = null
    }

    private class Cursor(val raw: ByteArray, var pos: Int, val limit: Int) {
        fun skip() {
            while (pos < limit) {
                val c = raw[pos].toInt() and 0xFF
                when {
                    c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 -> pos++
                    c == 0x25 -> {
                        while (pos < limit && raw[pos] != 0x0A.toByte() && raw[pos] != 0x0D.toByte()) pos++
                    }
                    else -> return
                }
            }
        }

        fun atWord(word: String): Boolean {
            if (pos + word.length > limit) return false
            for (i in word.indices) if (raw[pos + i] != word[i].code.toByte()) return false
            val after = pos + word.length
            return after >= limit || isDelimiter(raw[after].toInt() and 0xFF)
        }

        fun word(word: String): Boolean {
            if (!atWord(word)) return false
            pos += word.length
            return true
        }
    }

    private fun isDelimiter(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
        c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B || c == 0x5D ||
        c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25

    private fun readUInt(c: Cursor): Int? {
        var value = 0L
        var digits = 0
        while (c.pos < c.limit) {
            val d = c.raw[c.pos].toInt() and 0xFF
            if (d < 0x30 || d > 0x39) break
            value = value * 10 + (d - 0x30)
            if (value > Int.MAX_VALUE) return null
            c.pos++
            digits++
        }
        return if (digits == 0) null else value.toInt()
    }

    private fun parseValue(c: Cursor, depth: Int): Pv {
        if (depth > MAX_DICT_DEPTH) throw Unsupported("This PDF nests dictionaries too deeply to read")
        c.skip()
        if (c.pos >= c.limit) throw Unsupported("This PDF ends in the middle of an object")
        return when (val ch = c.raw[c.pos].toInt() and 0xFF) {
            0x2F -> parseName(c)
            0x28 -> parseLiteral(c)
            0x3C -> if (c.pos + 1 < c.limit && c.raw[c.pos + 1] == 0x3C.toByte()) parseDictionary(c, depth) else parseHex(c)
            0x5B -> parseArray(c, depth)
            0x5D, 0x3E -> throw Unsupported("This PDF has an unbalanced dictionary or array")
            else -> parseNumberOrKeyword(c)
        }
    }

    private fun parseName(c: Cursor): Pv {
        val start = c.pos
        c.pos++ // the solidus
        val text = StringBuilder()
        while (c.pos < c.limit) {
            val ch = c.raw[c.pos].toInt() and 0xFF
            if (isDelimiter(ch)) break
            if (ch == 0x23 && c.pos + 2 < c.limit) {
                val hex = String(c.raw, c.pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) {
                    text.append(hex.toChar())
                    c.pos += 3
                    continue
                }
            }
            text.append(ch.toChar())
            c.pos++
        }
        return Pv(start, c.pos).also { it.name = text.toString() }
    }

    private fun parseLiteral(c: Cursor): Pv {
        val start = c.pos
        c.pos++ // '('
        val out = ByteArrayOutputStream()
        var depth = 1
        while (c.pos < c.limit) {
            val ch = c.raw[c.pos].toInt() and 0xFF
            c.pos++
            when {
                ch == 0x5C -> { // backslash
                    if (c.pos >= c.limit) throw Unsupported("This PDF has an unterminated string")
                    val e = c.raw[c.pos].toInt() and 0xFF
                    c.pos++
                    when {
                        e == 0x6E -> out.write(0x0A)
                        e == 0x72 -> out.write(0x0D)
                        e == 0x74 -> out.write(0x09)
                        e == 0x62 -> out.write(0x08)
                        e == 0x66 -> out.write(0x0C)
                        e == 0x28 -> out.write(0x28)
                        e == 0x29 -> out.write(0x29)
                        e == 0x5C -> out.write(0x5C)
                        e in 0x30..0x37 -> {
                            var value = e - 0x30
                            var count = 1
                            while (count < 3 && c.pos < c.limit) {
                                val d = c.raw[c.pos].toInt() and 0xFF
                                if (d < 0x30 || d > 0x37) break
                                value = value * 8 + (d - 0x30)
                                c.pos++
                                count++
                            }
                            out.write(value and 0xFF)
                        }
                        e == 0x0A -> Unit
                        e == 0x0D -> if (c.pos < c.limit && c.raw[c.pos] == 0x0A.toByte()) c.pos++
                        else -> out.write(e)
                    }
                }
                ch == 0x28 -> {
                    depth++
                    out.write(ch)
                }
                ch == 0x29 -> {
                    depth--
                    if (depth == 0) return Pv(start, c.pos).also { it.bytes = out.toByteArray() }
                    out.write(ch)
                }
                ch == 0x0D -> {
                    if (c.pos < c.limit && c.raw[c.pos] == 0x0A.toByte()) c.pos++
                    out.write(0x0A)
                }
                else -> out.write(ch)
            }
            if (out.size() > MAX_STRING_BYTES) throw Unsupported("This PDF has an impossibly large string")
        }
        throw Unsupported("This PDF has an unterminated string")
    }

    private fun parseHex(c: Cursor): Pv {
        val start = c.pos
        c.pos++ // '<'
        val out = ByteArrayOutputStream()
        var high = -1
        while (c.pos < c.limit) {
            val ch = c.raw[c.pos].toInt() and 0xFF
            c.pos++
            if (ch == 0x3E) {
                if (high >= 0) out.write(high shl 4)
                return Pv(start, c.pos).also { it.bytes = out.toByteArray() }
            }
            if (ch == 9 || ch == 10 || ch == 12 || ch == 13 || ch == 32) continue
            val value = Character.digit(ch.toChar(), 16)
            if (value < 0) throw Unsupported("This PDF has a malformed hexadecimal string")
            if (high < 0) high = value else {
                out.write((high shl 4) or value)
                high = -1
            }
            if (out.size() > MAX_STRING_BYTES) throw Unsupported("This PDF has an impossibly large string")
        }
        throw Unsupported("This PDF has an unterminated hexadecimal string")
    }

    private fun parseArray(c: Cursor, depth: Int): Pv {
        val start = c.pos
        c.pos++ // '['
        val items = ArrayList<Pv>()
        while (true) {
            c.skip()
            if (c.pos >= c.limit) throw Unsupported("This PDF has an unterminated array")
            if (c.raw[c.pos] == 0x5D.toByte()) {
                c.pos++
                return Pv(start, c.pos).also { it.array = items }
            }
            items += parseValue(c, depth + 1)
            if (items.size > MAX_ARRAY_ITEMS) throw Unsupported("This PDF has an impossibly long array")
        }
    }

    private fun parseDictionary(c: Cursor, depth: Int): Pv {
        val start = c.pos
        c.pos += 2 // '<<'
        val map = LinkedHashMap<String, Pv>()
        while (true) {
            c.skip()
            if (c.pos >= c.limit) throw Unsupported("This PDF has an unterminated dictionary")
            if (c.atWord(">>")) {
                c.pos += 2
                return Pv(start, c.pos).also { it.dict = map }
            }
            if (c.raw[c.pos] != 0x2F.toByte()) throw Unsupported("This PDF has a dictionary key that is not a name")
            val key = parseName(c).name ?: throw Unsupported("This PDF has a malformed dictionary")
            map[key] = parseValue(c, depth + 1)
            if (map.size > MAX_DICT_ENTRIES) throw Unsupported("This PDF has an impossibly large dictionary")
        }
    }

    private fun parseNumberOrKeyword(c: Cursor): Pv {
        val start = c.pos
        while (c.pos < c.limit && !isDelimiter(c.raw[c.pos].toInt() and 0xFF)) c.pos++
        val token = String(c.raw, start, c.pos - start, Charsets.ISO_8859_1)
        if (token.isEmpty()) throw Unsupported("This PDF has an unreadable value")
        if (token.length <= 10 && token.all { it in '0'..'9' }) {
            // "number number R" is a reference; otherwise a plain number.
            val afterToken = c.pos
            c.skip()
            val gen = readUInt(c)
            if (gen != null) {
                c.skip()
                if (c.atWord("R")) {
                    c.pos += 1
                    val value = Pv(start, c.pos)
                    value.refNumber = token.toInt()
                    value.refGen = gen
                    return value
                }
            }
            c.pos = afterToken
        }
        val value = Pv(start, c.pos)
        if (token == "true" || token == "false" || token == "null") value.keyword = token else value.number = token
        return value
    }

    // ------------------------------------------------------------------
    // Cross-reference table and trailer
    // ------------------------------------------------------------------

    private class Entry(val number: Int, val gen: Int, val offset: Int) {
        var end: Int = 0
    }

    private class Xref(val entries: List<Entry>, val trailer: Pv, val tableOffset: Int)

    private fun readXref(raw: ByteArray): Xref {
        val marker = lastIndexOf(raw, "startxref")
        if (marker < 0) throw Unsupported("This PDF has no xref pointer ScanWhirl can read")
        val pointer = Cursor(raw, marker + "startxref".length, raw.size)
        pointer.skip()
        val offset = readUInt(pointer) ?: throw Unsupported("This PDF's xref pointer is unreadable")
        if (offset <= 0 || offset >= raw.size) throw Unsupported("This PDF's xref offset is outside the file")

        val c = Cursor(raw, offset, raw.size)
        c.skip()
        if (!c.atWord("xref")) {
            throw Unsupported("This PDF stores its objects in an xref stream, which ScanWhirl cannot rewrite")
        }
        c.pos += 4
        val entries = ArrayList<Entry>()
        while (true) {
            c.skip()
            if (c.pos >= c.limit) throw Unsupported("This PDF's xref table is truncated")
            if (c.atWord("trailer")) {
                c.pos += 7
                break
            }
            val first = readUInt(c) ?: throw Unsupported("This PDF's xref table is malformed")
            c.skip()
            val count = readUInt(c) ?: throw Unsupported("This PDF's xref table is malformed")
            c.skip()
            if (entries.size + count > MAX_XREF_ENTRIES) {
                throw Unsupported("This PDF's xref table is too large to rewrite safely")
            }
            repeat(count) { i ->
                val objectOffset = readUInt(c) ?: throw Unsupported("This PDF's xref table is malformed")
                c.skip()
                val generation = readUInt(c) ?: throw Unsupported("This PDF's xref table is malformed")
                c.skip()
                if (c.pos >= c.limit) throw Unsupported("This PDF's xref table is truncated")
                val type = c.raw[c.pos].toInt().toChar()
                c.pos++
                if (type == 'n') {
                    if (objectOffset <= 0 || objectOffset >= raw.size) {
                        throw Unsupported("This PDF's xref points outside the file")
                    }
                    entries += Entry(first + i, generation, objectOffset)
                } else if (type != 'f') {
                    throw Unsupported("This PDF's xref table has an entry type ScanWhirl cannot read")
                }
                while (c.pos < c.limit) {
                    val ch = c.raw[c.pos].toInt() and 0xFF
                    if (ch == 0x0D || ch == 0x0A || ch == 32 || ch == 9) c.pos++ else break
                }
            }
        }

        c.skip()
        val trailer = parseValue(c, 0)
        if (trailer.dict == null) throw Unsupported("This PDF's trailer is not a dictionary")
        return Xref(entries, trailer, offset)
    }

    private fun lastIndexOf(raw: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in raw.size - needle.size downTo 0) {
            for (j in needle.indices) if (raw[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------
    // Objects in file order, with their exact end
    // ------------------------------------------------------------------

    private fun orderedObjects(raw: ByteArray, xref: Xref): List<Entry> {
        val sorted = xref.entries.sortedBy { it.offset }
        val seen = HashSet<Int>()
        var previous = -1
        for (entry in sorted) {
            if (entry.offset == previous) throw Unsupported("Two PDF objects claim the same position")
            if (!seen.add(entry.number)) throw Unsupported("Two PDF xref entries claim the same object")
            previous = entry.offset
            val c = Cursor(raw, entry.offset, raw.size)
            val number = readUInt(c) ?: throw Unsupported("The PDF xref points at an object that is not there")
            c.skip()
            val gen = readUInt(c) ?: throw Unsupported("The PDF xref points at an object that is not there")
            c.skip()
            if (!c.atWord("obj") || number != entry.number || gen != entry.gen) {
                throw Unsupported("The PDF xref and its object headers disagree")
            }
        }
        for ((i, entry) in sorted.withIndex()) {
            val next = sorted.getOrNull(i + 1)?.offset ?: raw.size
            val limit = if (i + 1 == sorted.size) raw.size else next
            entry.end = if (xref.tableOffset > entry.offset) minOf(limit, xref.tableOffset) else limit
            if (entry.end <= entry.offset) throw Unsupported("A PDF object has an empty region")
        }
        return sorted
    }

    // ------------------------------------------------------------------
    // Standard security handler
    // ------------------------------------------------------------------

    private fun securityHandler(dict: Pv, trailer: Pv, password: String): Scheme {
        val map = dict.dict ?: throw Unsupported("The PDF /Encrypt value is not a dictionary")
        if (map["Filter"]?.name != "Standard") {
            throw Unsupported("This PDF does not use the standard password format")
        }
        val version = intValue(map["V"]) ?: 1
        val revision = intValue(map["R"])
            ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        val compatible = when (version) {
            1 -> revision == 2
            2 -> revision in 2..4
            4 -> revision == 4
            5 -> revision in 5..6
            else -> false
        }
        if (!compatible) {
            throw Unsupported("This PDF uses a password format ScanWhirl cannot open")
        }
        val encryptMetadata = when (val flag = map["EncryptMetadata"]) {
            null -> true
            else -> when (flag.keyword) {
                "true" -> true
                "false" -> false
                else -> throw Unsupported("The PDF encryption dictionary has an invalid metadata flag")
            }
        }

        val keyBytes: Int
        if (version >= 5) {
            val length = intValue(map["Length"])
            if (length != null && length != 256) {
                throw Unsupported("The PDF encryption dictionary declares an inconsistent key length")
            }
            keyBytes = 32
        } else {
            val bits = intValue(map["Length"]) ?: 40
            if (bits < 40 || bits > 128 || bits % 8 != 0) {
                throw Unsupported("The PDF encryption dictionary declares an invalid key length")
            }
            if (revision == 2 && bits != 40) {
                throw Unsupported("The PDF encryption dictionary declares an inconsistent key length")
            }
            keyBytes = bits / 8
        }

        val filters = map["CF"]?.dict
        fun resolve(name: String?): Filter {
            if (name == null || name == "Identity") return IDENTITY
            val entry = filters?.get(name)?.dict
                ?: throw Unsupported("The PDF names a crypt filter it does not define")
            return when (val cfm = entry["CFM"]?.name ?: "None") {
                "None" -> IDENTITY
                "V2" -> {
                    if (version >= 5) throw Unsupported("The PDF mixes an AES-256 handler with RC4 filters")
                    val declared = intValue(entry["Length"])
                    if (declared != null && declared != keyBytes * 8) {
                        throw Unsupported("The PDF crypt filter key length is inconsistent")
                    }
                    Filter(CIPHER_RC4, keyBytes)
                }
                "AESV2" -> {
                    if (version != 4) throw Unsupported("The PDF mixes crypt filters this decoder cannot recombine")
                    val declared = intValue(entry["Length"])
                    if (declared != null && declared != 16 && declared != 128) {
                        throw Unsupported("The PDF crypt filter key length is inconsistent")
                    }
                    if (keyBytes != 16) {
                        throw Unsupported("The PDF declares AES-128 with a different key length")
                    }
                    Filter(CIPHER_AES, 16)
                }
                "AESV3" -> {
                    if (version != 5) throw Unsupported("The PDF mixes crypt filters this decoder cannot recombine")
                    val declared = intValue(entry["Length"])
                    if (declared != null && declared != 32 && declared != 256) {
                        throw Unsupported("The PDF crypt filter key length is inconsistent")
                    }
                    Filter(CIPHER_AES, 32)
                }
                else -> throw Unsupported("This PDF uses an unknown crypt filter ($cfm)")
            }
        }

        val stream = if (version >= 4) resolve(map["StmF"]?.name) else Filter(CIPHER_RC4, keyBytes)
        val string = if (version >= 4) resolve(map["StrF"]?.name) else Filter(CIPHER_RC4, keyBytes)
        val embedded = map["EFF"]?.name
        if (embedded != null && embedded != "Identity") {
            throw Unsupported("This PDF encrypts embedded files with a separate filter ScanWhirl cannot rewrite")
        }

        if (version >= 5) {
            val u = map["U"]?.bytes ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val ue = map["UE"]?.bytes ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val o = map["O"]?.bytes
            val oe = map["OE"]?.bytes
            if (u.size != 48 || ue.size != 32) {
                throw Unsupported("The PDF encryption dictionary has invalid AES-256 entries")
            }
            if ((o != null && o.size != 48) || (oe != null && oe.size != 32)) {
                throw Unsupported("The PDF encryption dictionary has invalid AES-256 entries")
            }
            val key = PdfAes.userKey(password, u, ue, revision)
                ?: o?.let { owner -> oe?.let { ownerEnc -> PdfAes.ownerKey(password, owner, ownerEnc, u, revision) } }
                ?: throw WrongPassword()
            val permissions = intValue(map["P"])
                ?: throw Unsupported("The PDF encryption dictionary is incomplete")
            val perms = map["Perms"]?.bytes
                ?: throw Unsupported("The PDF encryption dictionary has no permission block")
            if (!PdfAes.permsValid(key, perms, permissions, encryptMetadata)) {
                throw Unsupported("The PDF permission block does not match its /P value")
            }
            return Scheme(key, 32, stream, string, encryptMetadata, revision)
        }

        val o = map["O"]?.bytes ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        val u = map["U"]?.bytes ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        val permissions = intValue(map["P"])
            ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        if (o.size < 32 || u.size < 32) {
            throw Unsupported("The PDF encryption dictionary has invalid entry lengths")
        }
        val key = legacyKey(
            password, o.copyOf(32), u, permissions, firstFileId(trailer),
            revision, keyBytes, encryptMetadata
        ) ?: throw WrongPassword()
        return Scheme(key, keyBytes, stream, string, encryptMetadata, revision)
    }

    private class Filter(val cipher: Int, val keyBytes: Int)

    private class Scheme(
        val key: ByteArray,
        val keyBytes: Int,
        val stream: Filter,
        val string: Filter,
        val encryptMetadata: Boolean,
        val revision: Int
    )

    private fun intValue(value: Pv?): Int? {
        val text = value?.number ?: return null
        if (text.isEmpty() || text.length > 11) return null
        return text.toIntOrNull()
    }

    /** The first `/ID` element: a key-derivation input for R2-R4, absent in some files. */
    private fun firstFileId(trailer: Pv): ByteArray {
        val items = trailer.dict?.get("ID")?.array ?: return ByteArray(0)
        return items.firstOrNull()?.bytes ?: ByteArray(0)
    }

    // ------------------------------------------------------------------
    // Revisions 2-4 (RC4 / AES-128 keys)
    // ------------------------------------------------------------------

    /** The 32-byte padding string of Algorithm 2 (ISO 32000-1 7.6.3.3). */
    private val PADDING = byteArrayOf(
        0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
        0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
        0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
        0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
    )

    private val IV_BYTES = 16

    private fun legacyKey(
        password: String,
        o: ByteArray,
        u: ByteArray,
        permissions: Int,
        fileId: ByteArray,
        revision: Int,
        keyBytes: Int,
        encryptMetadata: Boolean
    ): ByteArray? {
        val typed = pdfDocEncoding(password) ?: return null
        val paddedPassword = padded(typed)
        var key = legacyFileKey(paddedPassword, o, permissions, fileId, revision, keyBytes, encryptMetadata)
        if (legacyUserMatches(key, u, revision, fileId)) return key
        // Algorithm 5: /O holds the padded user password, encrypted with the owner key.
        var ownerKey = md5(paddedPassword)
        // Algorithm 3 step (b): 50 rounds over the FULL 16-byte digest (not the key length).
        if (revision >= 3) repeat(50) { ownerKey = md5(ownerKey) }
        ownerKey = ownerKey.copyOf(keyBytes)
        var recovered = o.copyOf(32)
        recovered = if (revision == 2) {
            rc4(ownerKey, recovered)
        } else {
            for (i in 19 downTo 0) recovered = rc4(xorWith(ownerKey, i), recovered)
            recovered
        }
        key = legacyFileKey(recovered, o, permissions, fileId, revision, keyBytes, encryptMetadata)
        return if (legacyUserMatches(key, u, revision, fileId)) key else null
    }

    private fun legacyFileKey(
        paddedPassword: ByteArray,
        o: ByteArray,
        permissions: Int,
        fileId: ByteArray,
        revision: Int,
        keyBytes: Int,
        encryptMetadata: Boolean
    ): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(paddedPassword)
        md.update(o.copyOf(32))
        md.update(
            byteArrayOf(
                (permissions and 0xFF).toByte(),
                ((permissions ushr 8) and 0xFF).toByte(),
                ((permissions ushr 16) and 0xFF).toByte(),
                ((permissions ushr 24) and 0xFF).toByte()
            )
        )
        md.update(fileId)
        if (revision >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
        var hash = md.digest()
        // Algorithm 2 step (h): 50 rounds, each over the first keyBytes bytes only.
        if (revision >= 3) repeat(50) { hash = md5(hash.copyOf(keyBytes)) }
        return hash.copyOf(keyBytes)
    }

    /** Algorithm 4 (R2) / 6 (R3-R4): does [key] reproduce /U from the padding string? */
    private fun legacyUserMatches(key: ByteArray, u: ByteArray, revision: Int, fileId: ByteArray): Boolean {
        if (revision <= 2) {
            return u.size >= 32 && MessageDigest.isEqual(rc4(key, PADDING), u.copyOf(32))
        }
        if (u.size < 16) return false
        val md = MessageDigest.getInstance("MD5")
        md.update(PADDING)
        md.update(fileId)
        var value = rc4(key, md.digest())
        for (i in 1..19) value = rc4(xorWith(key, i), value)
        return MessageDigest.isEqual(value.copyOf(16), u.copyOf(16))
    }

    /**
     * Algorithm 1: MD5 of the file key with the object and generation numbers — plus the literal
     * `sAlT` for AES streams — shortened to `key + 5` bytes (never more than 16).
     * AES-256 (V5) uses the file key directly and never reaches this function.
     */
    private fun objectKey(fileKey: ByteArray, number: Int, generation: Int, keyBytes: Int, aes: Boolean): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(
            byteArrayOf(
                number.toByte(),
                (number ushr 8).toByte(),
                (number ushr 16).toByte(),
                generation.toByte(),
                (generation ushr 8).toByte()
            )
        )
        // "For AES streams the key is extended with the 4 bytes 0x73 0x41 0x6C 0x54 (sAlT)."
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(keyBytes + 5, 16))
    }

    /**
     * PDFDocEncoding for an R2-R4 password (ISO 32000-1 Annex D), or null when a character has no
     * PDFDocEncoding byte: substitution would silently test the wrong password.
     */
    private fun pdfDocEncoding(password: String): ByteArray? {
        val out = ByteArray(password.length)
        for (i in password.indices) {
            val ch = password[i]
            val code = ch.code
            val byte = when {
                code in 0x00..0x7F -> code
                code in 0xA1..0xFF -> code
                else -> PDF_DOC_SPECIAL[ch] ?: return null
            }
            out[i] = byte.toByte()
        }
        return out
    }

    private val PDF_DOC_SPECIAL: Map<Char, Int> = mapOf(
        '\u02D8' to 0x18, '\u02C7' to 0x19, '\u02C6' to 0x1A, '\u02D9' to 0x1B,
        '\u02DD' to 0x1C, '\u02DB' to 0x1D, '\u02DA' to 0x1E, '\u02DC' to 0x1F,
        '\u2022' to 0x80, '\u2020' to 0x81, '\u2021' to 0x82, '\u2026' to 0x83,
        '\u2014' to 0x84, '\u2013' to 0x85, '\u0192' to 0x86, '\u2044' to 0x87,
        '\u2039' to 0x88, '\u203A' to 0x89, '\u2212' to 0x8A, '\u2030' to 0x8B,
        '\u201E' to 0x8C, '\u201C' to 0x8D, '\u201D' to 0x8E, '\u2018' to 0x8F,
        '\u2019' to 0x90, '\u201A' to 0x91, '\u2122' to 0x92, '\uFB01' to 0x93,
        '\uFB02' to 0x94, '\u0141' to 0x95, '\u0152' to 0x96, '\u0160' to 0x97,
        '\u0178' to 0x98, '\u017D' to 0x99, '\u0131' to 0x9A, '\u0142' to 0x9B,
        '\u0153' to 0x9C, '\u0161' to 0x9D, '\u017E' to 0x9E, '\u20AC' to 0xA0
    )

    // ------------------------------------------------------------------
    // Rewriting
    // ------------------------------------------------------------------

    private fun objectDictionary(raw: ByteArray, entry: Entry): Pv {
        val c = Cursor(raw, entry.offset, entry.end)
        if (readUInt(c) == null) throw Unsupported("The PDF /Encrypt object is unreadable")
        c.skip()
        if (readUInt(c) == null) throw Unsupported("The PDF /Encrypt object is unreadable")
        c.skip()
        if (!c.word("obj")) throw Unsupported("The PDF /Encrypt object is unreadable")
        c.skip()
        val value = parseValue(c, 0)
        return if (value.dict != null) value else throw Unsupported("The PDF /Encrypt value is not a dictionary")
    }

    private fun rewriter(
        raw: ByteArray,
        objects: List<Entry>,
        xref: Xref,
        scheme: Scheme,
        encryptObject: Int
    ): ByteArray {
        val first = objects.first().offset
        if (first <= 0) throw Unsupported("This PDF has no header before its first object")
        val cap = minOf(raw.size.toLong() * 4 + (1L shl 20), MAX_OUTPUT_BYTES)
        val out = ByteArrayOutputStream(minOf(raw.size + raw.size / 4 + 4096, 64 * 1024 * 1024))
        out.write(raw, 0, first)
        val offsets = HashMap<Int, Int>()
        var highest = 0

        for (entry in objects) {
            if (out.size().toLong() + (entry.end - entry.offset).toLong() * 2 + 64 > cap) {
                throw Unsupported("This PDF would grow too large to decrypt safely")
            }
            offsets[entry.number] = out.size()
            highest = maxOf(highest, entry.number)
            if (entry.number == encryptObject) {
                out.write(raw, entry.offset, entry.end - entry.offset)
                continue
            }
            writeObject(raw, entry, scheme, out)
        }

        val size = highest + 1
        if (size > MAX_XREF_ENTRIES) throw Unsupported("This PDF has too many object numbers to rewrite")

        val xrefOffset = out.size()
        val table = StringBuilder()
        table.append("xref\n0 ").append(size).append('\n')
        table.append("0000000000 65535 f \n")
        val byNumber = HashMap<Int, Entry>()
        for (entry in objects) byNumber[entry.number] = entry
        for (number in 1 until size) {
            val offset = offsets[number]
            val entry = byNumber[number]
            if (offset == null || entry == null) {
                table.append("0000000000 00000 f \n")
            } else {
                table.append(String.format(Locale.ROOT, "%010d %05d n \n", offset, entry.gen))
            }
        }
        out.write(table.toString().toByteArray(Charsets.ISO_8859_1))

        val root = trailerReference(xref, "Root")
        val info = xref.trailer.dict?.get("Info")
        val infoEntry = info?.let { value ->
            if (value.refNumber < 0) null else xref.entries.firstOrNull {
                it.number == value.refNumber && it.gen == value.refGen
            }
        }
        val tail = StringBuilder("trailer\n<< /Size ").append(size)
        tail.append(" /Root ").append(root.number).append(' ').append(root.gen).append(" R")
        if (infoEntry != null) {
            tail.append(" /Info ").append(infoEntry.number).append(' ').append(infoEntry.gen).append(" R")
        }
        val id = xref.trailer.dict?.get("ID")?.array
        if (id != null && id.isNotEmpty()) {
            tail.append(" /ID [")
            for (element in id) {
                val bytes = element.bytes ?: throw Unsupported("The PDF trailer has an invalid /ID")
                tail.append('<').append(hexOf(bytes)).append('>')
            }
            tail.append(']')
        }
        tail.append(" >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n")
        out.write(tail.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private class TrailerRef(val number: Int, val gen: Int)

    private fun trailerReference(xref: Xref, name: String): TrailerRef {
        val value = xref.trailer.dict?.get(name)
            ?: throw Unsupported("The PDF trailer has no /$name")
        if (value.refNumber < 0) throw Unsupported("The PDF trailer's /$name is not a reference")
        val entry = xref.entries.firstOrNull { it.number == value.refNumber }
            ?: throw Unsupported("The PDF trailer's /$name points at a missing object")
        if (entry.gen != value.refGen) {
            throw Unsupported("The PDF trailer's /$name does not match its object generation")
        }
        return TrailerRef(entry.number, entry.gen)
    }

    private fun writeObject(raw: ByteArray, entry: Entry, scheme: Scheme, out: ByteArrayOutputStream) {
        val c = Cursor(raw, entry.offset, entry.end)
        if (readUInt(c) == null) throw Unsupported("A PDF object has no number")
        c.skip()
        if (readUInt(c) == null) throw Unsupported("A PDF object has no generation")
        c.skip()
        if (!c.word("obj")) throw Unsupported("A PDF object has no obj keyword")
        c.skip()
        val valueStart = c.pos
        val value = parseValue(c, 0)
        val map = value.dict

        if (map != null) {
            val type = map["Type"]?.name
            if (type == "ObjStm") {
                throw Unsupported("This PDF packs its objects into an object stream, which ScanWhirl cannot rewrite")
            }
            if (type == "XRef") {
                throw Unsupported("This PDF stores its objects in an xref stream, which ScanWhirl cannot rewrite")
            }
        }

        val saved = c.pos
        c.skip()
        val isStream = map != null && c.atWord("stream")
        if (!isStream) {
            c.pos = saved
            out.write(raw, entry.offset, valueStart - entry.offset)
            rewriteStrings(raw, valueStart, entry.end, scheme, entry.number, entry.gen, out)
            return
        }

        val dict = map ?: throw Unsupported("A PDF stream object is not a dictionary")
        val length = dict["Length"]
        val lengthText = length?.number
        if (length == null || length.refNumber >= 0 || lengthText == null ||
            lengthText.isEmpty() || lengthText.length > 10 || !lengthText.all { it in '0'..'9' }
        ) {
            throw Unsupported("A stream in this PDF has no direct /Length")
        }
        val bodyLength = lengthText.toIntOrNull()
            ?: throw Unsupported("A stream in this PDF has an invalid /Length")

        c.pos += "stream".length
        if (c.pos < c.limit && c.raw[c.pos] == 0x0D.toByte()) {
            c.pos++
            if (c.pos < c.limit && c.raw[c.pos] == 0x0A.toByte()) c.pos++
        } else if (c.pos < c.limit && c.raw[c.pos] == 0x0A.toByte()) {
            c.pos++
        } else {
            throw Unsupported("A stream in this PDF does not start where its dictionary ends")
        }
        val bodyStart = c.pos
        val bodyEndLong = bodyStart.toLong() + bodyLength
        if (bodyLength < 0 || bodyEndLong > entry.end) {
            throw Unsupported("A stream in this PDF runs past its object")
        }
        val bodyEnd = bodyEndLong.toInt()
        val after = Cursor(raw, bodyEnd, entry.end)
        after.skip()
        if (!after.atWord("endstream")) {
            throw Unsupported("A stream in this PDF does not end where /Length says")
        }

        val filter = streamFilter(dict, scheme)
        val body = raw.copyOfRange(bodyStart, bodyEnd)
        val plain = decryptData(scheme, filter, entry.number, entry.gen, body)

        out.write(raw, entry.offset, valueStart - entry.offset)
        rewriteStrings(raw, valueStart, length.start, scheme, entry.number, entry.gen, out)
        // The text before the token already ends with "/Length ".
        out.write(plain.size.toString().toByteArray(Charsets.ISO_8859_1))
        rewriteStrings(raw, length.end, value.end, scheme, entry.number, entry.gen, out)
        out.write(raw, value.end, bodyStart - value.end)
        out.write(plain)
        out.write(raw, bodyEnd, entry.end - bodyEnd)
    }

    /** The effective filter for one stream, resolved by name (never guessed from the ciphertext). */
    private fun streamFilter(map: LinkedHashMap<String, Pv>, scheme: Scheme): Filter {
        val filterValue = map["Filter"]
        val names = when {
            filterValue == null -> emptyList()
            filterValue.name != null -> listOf(filterValue.name!!)
            filterValue.array != null -> filterValue.array!!.mapNotNull { it.name }
            else -> throw Unsupported("A stream in this PDF has an unreadable /Filter")
        }
        if (names.contains("Crypt")) {
            val name = cryptFilterName(map)
            return if (name == null || name == "Identity") {
                IDENTITY
            } else {
                throw Unsupported("This PDF encrypts individual streams with their own crypt filter, which ScanWhirl cannot rewrite")
            }
        }
        if (map["Type"]?.name == "EmbeddedFile") {
            // /EFF is Identity (a non-Identity /EFF was refused with the encryption dictionary).
            return IDENTITY
        }
        if (!scheme.encryptMetadata && map["Type"]?.name == "Metadata") {
            // /EncryptMetadata false: the document's metadata stream is stored in the clear.
            return IDENTITY
        }
        return scheme.stream
    }

    private fun cryptFilterName(map: LinkedHashMap<String, Pv>): String? {
        val parms = map["DecodeParms"]
        val dict = when {
            parms?.dict != null -> parms.dict
            parms?.array != null -> parms.array!!.firstOrNull { it.dict != null }?.dict
            else -> null
        }
        return dict?.get("Name")?.name
    }

    private fun decryptData(
        scheme: Scheme,
        filter: Filter,
        number: Int,
        generation: Int,
        data: ByteArray
    ): ByteArray {
        if (data.isEmpty()) return data
        return when (filter.cipher) {
            CIPHER_NONE -> data
            CIPHER_RC4 -> rc4(objectKey(scheme.key, number, generation, scheme.keyBytes, aes = false), data)
            else -> {
                val key = if (filter.keyBytes == 32) {
                    scheme.key
                } else {
                    objectKey(scheme.key, number, generation, scheme.keyBytes, aes = true)
                }
                aesCbc(key, data)
            }
        }
    }

    /**
     * Decrypts every string in `[from, to)` and writes it back as a hex string, copying everything
     * else byte for byte. Stream bodies are never passed here: they are decrypted as a whole.
     */
    private fun rewriteStrings(
        raw: ByteArray,
        from: Int,
        to: Int,
        scheme: Scheme,
        number: Int,
        generation: Int,
        out: ByteArrayOutputStream
    ) {
        val filter = scheme.string
        if (filter.cipher == CIPHER_NONE) {
            out.write(raw, from, to - from)
            return
        }
        var i = from
        while (i < to) {
            val ch = raw[i].toInt() and 0xFF
            when {
                ch == 0x25 -> { // comment: copy verbatim
                    val start = i
                    while (i < to && raw[i] != 0x0A.toByte() && raw[i] != 0x0D.toByte()) i++
                    out.write(raw, start, i - start)
                }
                ch == 0x28 -> {
                    val string = literalString(raw, i, to)
                    i = string.end
                    emitHex(out, decryptData(scheme, filter, number, generation, string.bytes))
                }
                ch == 0x3C && i + 1 < to && raw[i + 1] == 0x3C.toByte() -> {
                    out.write(raw, i, 2)
                    i += 2
                }
                ch == 0x3E && i + 1 < to && raw[i + 1] == 0x3E.toByte() -> {
                    out.write(raw, i, 2)
                    i += 2
                }
                ch == 0x3C -> {
                    val string = hexString(raw, i, to)
                    i = string.end
                    emitHex(out, decryptData(scheme, filter, number, generation, string.bytes))
                }
                else -> {
                    out.write(raw, i, 1)
                    i++
                }
            }
        }
    }

    private class ParsedString(val bytes: ByteArray, val end: Int)

    private fun literalString(raw: ByteArray, start: Int, limit: Int): ParsedString {
        var i = start + 1
        var depth = 1
        val out = ByteArrayOutputStream()
        while (i < limit) {
            val ch = raw[i].toInt() and 0xFF
            i++
            when {
                ch == 0x5C -> {
                    if (i >= limit) throw Unsupported("This PDF has an unterminated string")
                    val e = raw[i].toInt() and 0xFF
                    i++
                    when {
                        e == 0x6E -> out.write(0x0A)
                        e == 0x72 -> out.write(0x0D)
                        e == 0x74 -> out.write(0x09)
                        e == 0x62 -> out.write(0x08)
                        e == 0x66 -> out.write(0x0C)
                        e == 0x28 -> out.write(0x28)
                        e == 0x29 -> out.write(0x29)
                        e == 0x5C -> out.write(0x5C)
                        e in 0x30..0x37 -> {
                            var value = e - 0x30
                            var count = 1
                            while (count < 3 && i < limit) {
                                val d = raw[i].toInt() and 0xFF
                                if (d < 0x30 || d > 0x37) break
                                value = value * 8 + (d - 0x30)
                                i++
                                count++
                            }
                            out.write(value and 0xFF)
                        }
                        e == 0x0A -> Unit
                        e == 0x0D -> if (i < limit && raw[i] == 0x0A.toByte()) i++
                        else -> out.write(e)
                    }
                }
                ch == 0x28 -> {
                    depth++
                    out.write(ch)
                }
                ch == 0x29 -> {
                    depth--
                    if (depth == 0) return ParsedString(out.toByteArray(), i)
                    out.write(ch)
                }
                else -> out.write(ch)
            }
            if (out.size() > MAX_STRING_BYTES) throw Unsupported("This PDF has an impossibly large string")
        }
        throw Unsupported("This PDF has an unterminated string")
    }

    private fun hexString(raw: ByteArray, start: Int, limit: Int): ParsedString {
        var i = start + 1
        val out = ByteArrayOutputStream()
        var high = -1
        while (i < limit) {
            val ch = raw[i].toInt() and 0xFF
            i++
            if (ch == 0x3E) {
                if (high >= 0) out.write(high shl 4)
                return ParsedString(out.toByteArray(), i)
            }
            if (ch == 9 || ch == 10 || ch == 12 || ch == 13 || ch == 32) continue
            val value = Character.digit(ch.toChar(), 16)
            if (value < 0) throw Unsupported("This PDF has a malformed hexadecimal string")
            if (high < 0) high = value else {
                out.write((high shl 4) or value)
                high = -1
            }
        }
        throw Unsupported("This PDF has an unterminated hexadecimal string")
    }

    private fun emitHex(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(0x3C)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.write(HEX[v ushr 4].code)
            out.write(HEX[v and 0x0F].code)
        }
        out.write(0x3E)
    }

    private fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    // ------------------------------------------------------------------
    // Ciphers
    // ------------------------------------------------------------------

    /** IV (16 bytes) + AES-CBC/PKCS#7 body, as every AES revision writes streams. */
    private fun aesCbc(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size <= IV_BYTES || (data.size - IV_BYTES) % 16 != 0) {
            throw Unsupported("An AES stream is not IV plus whole blocks")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(data.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
        } catch (e: Exception) {
            // Wrong padding means a truncated or damaged stream, not a wrong password.
            throw Unsupported("An AES stream could not be decrypted")
        }
    }

    /** RC4 (Algorithm 1's cipher) — the whole stream body, no IV, no padding. */
    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        if (key.isEmpty()) throw Unsupported("The PDF encryption key is empty")
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]
            s[i] = s[j]
            s[j] = t
        }
        val out = ByteArray(data.size)
        var a = 0
        var b = 0
        for (k in data.indices) {
            a = (a + 1) and 0xFF
            b = (b + s[a]) and 0xFF
            val t = s[a]
            s[a] = s[b]
            s[b] = t
            out[k] = (data[k].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    private fun xorWith(data: ByteArray, value: Int): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor value).toByte() }

    private fun padded(password: ByteArray): ByteArray {
        val out = ByteArray(32)
        val copy = minOf(password.size, 32)
        password.copyInto(out, 0, 0, copy)
        if (copy < 32) PADDING.copyInto(out, copy, 0, 32 - copy)
        return out
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    // ------------------------------------------------------------------
    // Limits
    // ------------------------------------------------------------------

    /** Copies of the file [decrypt] holds at its peak (see [maxInMemoryBytes]). */
    private const val HEAP_COPIES = 8L

    /** Never refuse a file this small, whatever the heap reports. */
    private const val MIN_IN_MEMORY_BYTES = 1L shl 20

    private const val MAX_OBJECTS = 50_000
    private const val MAX_XREF_ENTRIES = 200_000
    private const val MAX_DICT_DEPTH = 32
    private const val MAX_DICT_ENTRIES = 4096
    private const val MAX_ARRAY_ITEMS = 65_536
    private const val MAX_STRING_BYTES = 32 * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 512L * 1024 * 1024

    private const val CIPHER_NONE = 0
    private const val CIPHER_RC4 = 1
    private const val CIPHER_AES = 2

    private val IDENTITY = Filter(CIPHER_NONE, 0)

    private const val HEX = "0123456789ABCDEF"
}
