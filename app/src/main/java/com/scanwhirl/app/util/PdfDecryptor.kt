package com.scanwhirl.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Removes the standard-security-handler password from a PDF that ScanWhirl can parse
 * (classic xref, AES-256 V5/R6 AESV3 — the format [PdfEncryptor] writes, and the same
 * handler used by other modern producers that use a classic xref and inline `/Encrypt`).
 *
 * The original file is never touched: the caller gets a new plaintext PDF in app storage and
 * imports its pages; only that temporary copy is deleted afterwards.
 *
 * The parser deliberately reuses [PdfEncryptor.objects], the writer's own xref walk, so a JPEG
 * that contains `endstream` cannot confuse object boundaries. Unsupported shapes throw
 * [Unsupported] with an honest message instead of guessing.
 */
object PdfDecryptor {

    /** The password did not derive the file key (user or owner password). */
    class WrongPassword : IOException("Incorrect password")

    /** Not a PDF shape this offline decoder can open (e.g. xref streams, RC4). */
    class Unsupported(message: String) : IOException(message)

    /**
     * Returns a decrypted copy of [raw]. Throws [WrongPassword] when neither the user nor the
     * owner password opens it, and [Unsupported] when the encryption or PDF shape is not handled.
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
        require(password.isNotEmpty()) { "Password must not be empty" }
        val latin = String(raw, Charsets.ISO_8859_1)
        val trailer = trailerDictionary(latin)
            ?: throw Unsupported("This PDF has no trailer ScanWhirl can read")
        val encryptAt = Regex("/Encrypt\\s*<<").find(trailer)?.range?.first
            ?: throw Unsupported("This PDF does not use the standard password format")
        val dict = balancedDict(trailer, trailer.indexOf("<<", encryptAt))
            ?: throw Unsupported("The PDF encryption dictionary is damaged")
        val version = number(dict, "V")
        val revision = number(dict, "R")
        if (version != 5 || revision == null || revision < 5) {
            throw Unsupported("This PDF uses an older password format")
        }
        val u = hex(dict, "U") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        val ue = hex(dict, "UE") ?: throw Unsupported("The PDF encryption dictionary is incomplete")
        val o = hex(dict, "O")
        val oe = hex(dict, "OE")
        val key = PdfAes.userKey(password, u, ue)
            ?: o?.let { oe?.let { owner -> PdfAes.ownerKey(password, owner, it, u) } }
            ?: throw WrongPassword()

        val objects = PdfEncryptor.objects(raw)
        if (objects.isEmpty()) throw Unsupported("Not a PDF ScanWhirl can open")
        val decryptor = PdfAes.decryptor(key)
        val out = ByteArrayOutputStream(raw.size)
        out.write(raw, 0, objects.minOf { it.start })
        val offsets = HashMap<Int, Int>()
        for (obj in objects.sortedBy { it.start }) {
            offsets[obj.number] = out.size()
            val marker = latin.indexOf(">>\nstream\n", obj.start)
            if (marker < 0 || marker >= obj.end) {
                out.write(raw, obj.start, obj.end - obj.start)
                continue
            }
            val dictEnd = marker + ">>\nstream\n".length
            if (dictEnd > obj.end) throw Unsupported("Object ${obj.number} has a broken dictionary")
            val dictText = latin.substring(obj.start, dictEnd)
            val lengthMatch = Regex("/Length\\s+(\\d+)").find(dictText)
                ?: throw Unsupported("Stream ${obj.number} has no /Length")
            val oldLength = lengthMatch.groupValues[1].toIntOrNull()
                ?: throw Unsupported("Stream ${obj.number} has an invalid /Length")
            val bodyEnd = dictEnd + oldLength
            if (bodyEnd > obj.end) throw Unsupported("Stream ${obj.number} runs past its object")
            val plain = decryptor.decryptStream(raw.copyOfRange(dictEnd, bodyEnd))
            val newDict = dictText.substring(0, lengthMatch.range.first) +
                "/Length ${plain.size}" +
                dictText.substring(lengthMatch.range.last + 1)
            out.write(newDict.toByteArray(Charsets.ISO_8859_1))
            out.write(plain)
            out.write(raw, bodyEnd, obj.end - bodyEnd)
        }

        val xrefOffset = out.size()
        val size = objects.maxOf { it.number } + 1
        val xref = StringBuilder()
        xref.append("xref\n0 $size\n0000000000 65535 f \n")
        for (number in 1 until size) {
            val offset = offsets[number]
            if (offset == null) {
                xref.append("0000000000 00000 f \n")
            } else {
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
            }
        }
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        val root = Regex("/Root\\s+(\\d+)\\s+0\\s+R").find(trailer)
            ?.groupValues?.get(1)
            ?: throw Unsupported("The PDF trailer has no /Root")
        val tail = "trailer\n<< /Size $size /Root $root 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n"
        out.write(tail.toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------

    private fun trailerDictionary(latin: String): String? {
        val at = latin.lastIndexOf("\ntrailer\n")
        if (at < 0) return null
        val from = at + "\ntrailer\n".length
        val to = latin.indexOf("\nstartxref\n", from)
        return if (to < 0) latin.substring(from) else latin.substring(from, to)
    }

    /** The `<< … >>` starting at [start] (nested dictionaries included). */
    private fun balancedDict(text: String, start: Int): String? {
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            when {
                text.startsWith("<<", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith(">>", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return text.substring(start, i)
                }
                else -> i++
            }
        }
        return null
    }

    private fun number(dict: String, name: String): Int? =
        Regex("/$name\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull()

    private fun hex(dict: String, name: String): ByteArray? {
        val hex = Regex("/$name\\s*<([0-9A-Fa-f]+)>").find(dict)?.groupValues?.get(1)
            ?: return null
        if (hex.length % 2 != 0) return null
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
