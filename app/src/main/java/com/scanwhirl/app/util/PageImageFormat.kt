package com.scanwhirl.app.util

import java.util.zip.CRC32

/** SOF markers the JPEG reader understands, shared with the PDF writer so both agree. */
internal object JpegSof {
    /** Baseline, sequential Huffman and progressive Huffman — the dimensions parse identically. */
    val SUPPORTED = setOf(0xC0, 0xC1, 0xC2, 0xC3)

    /** Every SOF marker (excluding the Huffman/DHT and JPEG extensions C4, C8, CC). */
    val ALL: Set<Int> = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

    fun isSupported(marker: Int): Boolean = marker in SUPPORTED

    fun description(marker: Int): String = when (marker) {
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> "arithmetic-coded"
        0xC5, 0xC6, 0xC7 -> "differential"
        else -> "unsupported"
    }
}

/** Container checks supplement Android decoders, which can accept truncated images. */
internal object PageImageFormat {
    enum class Format { JPEG, PNG, WEBP, HEIF }

    private fun invalid(): Nothing = throw IllegalArgumentException(
        "This image is damaged or incomplete. Download it again or choose another PNG, JPEG or WebP image."
    )

    fun check(bytes: ByteArray): Format = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> {
            checkJpeg(bytes)
            Format.JPEG
        }
        ImageTools.isPng(bytes) -> {
            checkPng(bytes)
            Format.PNG
        }
        bytes.size >= 12 && text(bytes, 0) == "RIFF" && text(bytes, 8) == "WEBP" -> {
            checkWebp(bytes)
            Format.WEBP
        }
        isHeif(bytes) -> Format.HEIF
        else -> throw IllegalArgumentException("Cannot read this image. Choose a complete PNG, JPEG or WebP image.")
    }

    /**
     * HEIC/HEIF is the default camera format on recent iPhones and some Androids. The bytes are
     * an ISO-BMFF `ftyp` box; the platform decoder decides whether this phone can render them.
     * The box length is validated so a truncated file is not offered as a supported image.
     */
    fun isHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 16 || text(bytes, 4) != "ftyp") return false
        val size = u32(bytes, 0, false)
        if (size < 16 || size > bytes.size) return false
        val major = text(bytes, 8)
        val brands = HEIF_BRANDS.toMutableSet().apply { add(major) }
        var pos = 16
        while (pos + 4 <= minOf(size.toInt(), bytes.size)) {
            brands += text(bytes, pos)
            pos += 4
        }
        return HEIF_BRANDS.any { it in brands }
    }

    private val HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1"
    )

    /** Walk segments AND scan data; a SOF header alone does not prove a complete JPEG. */
    fun checkJpeg(bytes: ByteArray) {
        if (bytes.size < 4 || u8(bytes, 0) != 255 || u8(bytes, 1) != 216) invalid()
        var pos = 2
        var inScan = false
        var sawScan = false
        var sawFrame = false
        while (pos < bytes.size) {
            if (u8(bytes, pos++) != 255) {
                if (!inScan) invalid()
                continue
            }
            while (pos < bytes.size && u8(bytes, pos) == 255) pos++
            if (pos == bytes.size) invalid()
            val marker = u8(bytes, pos++)
            if (inScan && (marker == 0 || marker in 0xD0..0xD7)) continue
            inScan = false
            if (marker == 0xD9) {
                if (!sawFrame || !sawScan) invalid()
                return // Camera metadata/trailers after EOI are permitted.
            }
            if (marker == 0x01) continue
            if (marker == 0 || marker == 0xD8 || marker in 0xD0..0xD7 || pos + 2 > bytes.size) invalid()
            val length = (u8(bytes, pos) shl 8) or u8(bytes, pos + 1)
            if (length < 2 || length > bytes.size - pos) invalid()
            if (marker in JpegSof.ALL) {
                if (length < 8) invalid()
                val components = u8(bytes, pos + 7)
                if (components == 0 || length != 8 + 3 * components) invalid()
                if (!JpegSof.isSupported(marker)) {
                    throw IllegalArgumentException(
                        "This JPEG uses ${JpegSof.description(marker)} coding, which this device cannot save as a page. " +
                            "Choose a baseline or progressive JPEG and try again."
                    )
                }
                sawFrame = true
            }
            if (marker == 0xDA) {
                if (!sawFrame || length < 6 || length != 6 + 2 * u8(bytes, pos + 2)) invalid()
                sawScan = true
                inScan = true
            }
            pos += length
        }
        invalid() // Missing EOI: Android may otherwise return a partly grey bitmap.
    }

    private fun checkPng(bytes: ByteArray) {
        var pos = 8
        var sawData = false
        while (pos <= bytes.size - 12) {
            val length = u32(bytes, pos, false)
            if (length > bytes.size.toLong() - pos - 12) invalid()
            val size = length.toInt()
            val type = text(bytes, pos + 4)
            if (pos == 8 && (type != "IHDR" || size != 13)) invalid()
            val crc = CRC32().apply { update(bytes, pos + 4, size + 4) }.value
            if (crc != u32(bytes, pos + 8 + size, false)) invalid()
            if (type == "IDAT") sawData = true
            pos += size + 12
            if (type == "IEND") {
                if (size != 0 || !sawData || pos != bytes.size) invalid()
                return
            }
        }
        invalid()
    }

    private fun checkWebp(bytes: ByteArray) {
        if (u32(bytes, 4, true) + 8 != bytes.size.toLong()) invalid()
        var pos = 12
        var sawImage = false
        while (pos <= bytes.size - 8) {
            val type = text(bytes, pos)
            val size = u32(bytes, pos + 4, true)
            val padded = size + (size and 1)
            if (padded > bytes.size.toLong() - pos - 8) invalid()
            if (type == "VP8 " || type == "VP8L") sawImage = true
            if (type == "ANIM" || type == "ANMF") {
                throw IllegalArgumentException("Animated WebP is not a page. Export a still image as PNG or JPEG and try again.")
            }
            pos += 8 + padded.toInt()
        }
        if (pos != bytes.size || !sawImage) invalid()
    }

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255
    private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    private fun u32(bytes: ByteArray, at: Int, little: Boolean): Long {
        var result = 0L
        for (i in 0..3) result = (result shl 8) or u8(bytes, at + if (little) 3 - i else i).toLong()
        return result
    }
}
