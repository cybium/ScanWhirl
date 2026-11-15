package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import kotlin.math.max
import kotlin.math.min

/**
 * L5 "Mask Aadhaar": finds Aadhaar numbers (12 digits) and VIDs (16 digits) in OCR text and
 * returns redaction boxes over their **first 8 digits**, leaving the last 4 visible (UIDAI's
 * recommended masked Aadhaar).
 *
 * OCR noise is normalised before matching (O/o -> 0, l/I/| -> 1). Numbers may be grouped with
 * spaces ("1234 5678 9012") or ungrouped. Boxes are estimated inside each OCR line box by
 * character position, since the crop only needs the leading digits; the caller lets the user
 * adjust them before burning.
 */
object AadhaarMask {

    /** A word/element box from ML Kit, in the decoded bitmap's pixels. */
    data class Word(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /** A number found in one line, with the box covering its first 8 digits. */
    data class Hit(val digits: Int, val box: PageStamp.Box)

    /** One token of digits, possibly grouped by single spaces/tabs. */
    private val TOKEN = Regex("\\d(?:[ \\t]?\\d)*")

    /** OCR confusion characters that are digits in context. */
    fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    'O', 'o' -> '0'
                    'l', 'I', '|' -> '1'
                    else -> c
                }
            )
        }
    }

    /** Aadhaar (12) and VID (16) numbers in the line's text, in reading order. */
    fun hits(line: OcrLine, page: OcrPage): List<Hit> {
        if (page.width <= 0 || page.height <= 0) return emptyList()
        val text = normalize(line.text)
        if (text.length <= 0) return emptyList()
        val hits = ArrayList<Hit>()
        for (match in TOKEN.findAll(text)) {
            val token = match.value
            val digitCount = token.count { it.isDigit() }
            if (digitCount != 12 && digitCount != 16) continue
            // The x of the first digit and of the 8th digit inside the line.
            var eighthEnd = -1
            var seen = 0
            for (i in token.indices) {
                if (token[i].isDigit()) {
                    seen++
                    if (seen == 8) {
                        eighthEnd = i + 1
                        break
                    }
                }
            }
            if (eighthEnd < 0) continue
            val startFrac = match.range.first.toFloat() / text.length
            val endFrac = (match.range.first + eighthEnd).toFloat() / text.length
            val lineW = (line.right - line.left).toFloat().coerceAtLeast(1f)
            val lineH = (line.bottom - line.top).toFloat().coerceAtLeast(1f)
            val padY = lineH * 0.06f
            val leftPx = line.left + startFrac * lineW
            val rightPx = line.left + endFrac * lineW
            hits += Hit(
                digitCount,
                PageStamp.Box(
                    left = (leftPx / page.width).coerceIn(0f, 1f),
                    top = ((line.top - padY) / page.height).coerceIn(0f, 1f),
                    right = (rightPx / page.width).coerceIn(0f, 1f),
                    bottom = ((line.bottom + padY) / page.height).coerceIn(0f, 1f)
                )
            )
        }
        return hits
    }

    /** Every Aadhaar/VID box on one page. */
    fun find(page: OcrPage, maskVid: Boolean = true): List<PageStamp.Box> =
        page.lines.sortedWith(compareBy({ it.top }, { it.left }))
            .flatMap { hits(it, page) }
            .filter { maskVid || it.digits == 12 }
            .map { it.box }

    /**
     * The device path (ML Kit elements): groups words into lines, finds the 12/16-digit run and
     * masks exactly its first two 4-digit groups. Three separate words "9233" "6926" "4947" yield
     * two whole word boxes; one merged "9233 6926 4947" element is split proportionally by
     * character count. The last 4 digits are never covered.
     */
    fun findWords(
        words: List<Word>,
        pageWidth: Int,
        pageHeight: Int,
        maskVid: Boolean = true
    ): List<PageStamp.Box> {
        if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
        val valid = words.filter { it.right > it.left && it.bottom > it.top && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val lines = ArrayList<MutableList<Word>>()
        for (word in valid) {
            val line = lines.lastOrNull()
            if (line != null) {
                val ref = line.last()
                val overlap = min(ref.bottom, word.bottom) - max(ref.top, word.top)
                val minH = min(ref.bottom - ref.top, word.bottom - word.top)
                if (overlap > minH * 0.5f) {
                    line.add(word)
                    continue
                }
            }
            lines.add(mutableListOf(word))
        }
        val out = ArrayList<PageStamp.Box>()
        for (line in lines) {
            line.sortBy { it.left }
            val builder = StringBuilder()
            val starts = ArrayList<Int>(line.size)
            line.forEachIndexed { index, word ->
                if (index > 0) builder.append(' ')
                starts.add(builder.length)
                builder.append(word.text)
            }
            val text = normalize(builder.toString())
            for (match in TOKEN.findAll(text)) {
                val digitCount = match.value.count { it.isDigit() }
                if (digitCount != 12 && digitCount != 16) continue
                if (digitCount == 16 && !maskVid) continue
                // Aadhaar: hide the first 8 digits; VID: hide its first 12.
                val maskDigits = if (digitCount == 16) 12 else 8
                var seen = 0
                var maskEnd = -1
                for (i in match.value.indices) {
                    if (match.value[i].isDigit()) {
                        seen++
                        if (seen == maskDigits) {
                            maskEnd = match.range.first + i + 1
                            break
                        }
                    }
                }
                if (maskEnd < 0) continue
                val start = match.range.first

                // The x of the next digit (the first one NOT masked), to stop in the middle of
                // the gap before it. Never inside it.
                var nextDigitX = -1f
                var nextDigitIndex = -1
                for (idx in maskEnd until text.length) {
                    if (text[idx].isDigit()) {
                        nextDigitIndex = idx
                        break
                    }
                }
                if (nextDigitIndex >= 0) {
                    for (index in line.indices) {
                        val wordStart = starts[index]
                        val wordEnd = wordStart + line[index].text.length
                        if (nextDigitIndex in wordStart until wordEnd) {
                            val width = (line[index].right - line[index].left).toFloat()
                            val frac = (nextDigitIndex - wordStart).toFloat() /
                                line[index].text.length
                            nextDigitX = line[index].left + frac * width
                            break
                        }
                    }
                }

                val boxes = ArrayList<PageStamp.Box>()
                var firstWordIndex = -1
                var lastMaskedWord = -1
                line.forEachIndexed { index, word ->
                    val wordStart = starts[index]
                    val wordEnd = wordStart + word.text.length
                    if (max(start, wordStart) < min(maskEnd, wordEnd)) lastMaskedWord = index
                }
                line.forEachIndexed { index, word ->
                    val wordStart = starts[index]
                    val wordEnd = wordStart + word.text.length
                    val overlapStart = max(start, wordStart)
                    val overlapEnd = min(maskEnd, wordEnd)
                    if (overlapEnd <= overlapStart) return@forEachIndexed
                    if (firstWordIndex < 0) firstWordIndex = index
                    val width = (word.right - word.left).toFloat().coerceAtLeast(1f)
                    val height = (word.bottom - word.top).toFloat().coerceAtLeast(1f)
                    val padY = height * 0.10f
                    val fracStart = (overlapStart - wordStart).toFloat() / word.text.length
                    val fracEnd = (overlapEnd - wordStart).toFloat() / word.text.length
                    var left = word.left + fracStart * width
                    var right = word.left + fracEnd * width
                    // Outer side: 15% of the glyph height, so the first digit cannot leak.
                    if (index == firstWordIndex && overlapStart == start) left -= height * 0.15f
                    // Inner edge: the middle of the gap before the next (unmasked) digit.
                    if (index == lastMaskedWord && maskEnd == overlapEnd && nextDigitX > 0f) {
                        right = ((right + nextDigitX) / 2f).coerceAtMost(nextDigitX)
                    }
                    var top = word.top - padY
                    var bottom = word.bottom + padY
                    // Vertical: never overlap another OCR line's box.
                    for (other in valid) {
                        if (other === word) continue
                        if (line.any { it === other }) continue
                        val horizontal = other.left < right && other.right > left
                        if (!horizontal) continue
                        if (other.bottom <= word.top) top = max(top, other.bottom.toFloat())
                        if (other.top >= word.bottom) bottom = min(bottom, other.top.toFloat())
                    }
                    boxes += PageStamp.Box(
                        (left / pageWidth).coerceIn(0f, 1f),
                        (top / pageHeight).coerceIn(0f, 1f),
                        (right / pageWidth).coerceIn(0f, 1f),
                        (bottom / pageHeight).coerceIn(0f, 1f)
                    )
                }
                out += boxes
            }
        }
        return out
    }

    /**
     * Optional QR mask. No barcode-bounds API is available without a new library, so this is a
     * density heuristic: the largest square window with dense dark/light transitions in both
     * axes. The user can adjust the box afterwards.
     */
    fun findQr(source: android.graphics.Bitmap): PageStamp.Box? {
        if (source.isRecycled || source.width < 32 || source.height < 32) return null
        val w = source.width
        val h = source.height
        val step = max(1, min(w, h) / 90)
        val sw = w / step
        val sh = h / step
        if (sw < 24 || sh < 24) return null
        val dark = BooleanArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val p = source.getPixel(x * step, y * step)
                val l = 0.299f * android.graphics.Color.red(p) +
                    0.587f * android.graphics.Color.green(p) +
                    0.114f * android.graphics.Color.blue(p)
                dark[y * sw + x] = l < 128f
            }
        }
        fun score(x0: Int, y0: Int, size: Int): Float {
            if (x0 < 0 || y0 < 0 || x0 + size > sw || y0 + size > sh) return 0f
            var ink = 0
            var hTrans = 0
            var vTrans = 0
            for (y in y0 until y0 + size) {
                for (x in x0 until x0 + size) {
                    if (dark[y * sw + x]) ink++
                    if (x > x0 && dark[y * sw + x] != dark[y * sw + x - 1]) hTrans++
                    if (y > y0 && dark[y * sw + x] != dark[(y - 1) * sw + x]) vTrans++
                }
            }
            val n = size.toFloat() * size
            val inkFrac = ink / n
            if (inkFrac < 0.18f || inkFrac > 0.75f) return 0f
            val pairs = size.toFloat() * (size - 1)
            val density = min(hTrans / pairs, vTrans / pairs)
            if (density < 0.10f) return 0f
            // Square-ish QR blocks score highest; text lines have almost no vertical transitions.
            return density * (1f - kotlin.math.abs(inkFrac - 0.45f))
        }
        var best = 0f
        var bestX = -1
        var bestY = -1
        var bestSize = 0
        val minSize = max(8, min(sw, sh) / 8)
        val maxSize = max(minSize, min(sw, sh) * 2 / 3)
        var size = maxSize
        while (size >= minSize) {
            val stride = max(2, size / 4)
            var y = 0
            while (y + size <= sh) {
                var x = 0
                while (x + size <= sw) {
                    val s = score(x, y, size)
                    if (s > best) {
                        best = s
                        bestX = x
                        bestY = y
                        bestSize = size
                    }
                    x += stride
                }
                y += stride
            }
            size = (size * 3) / 4
        }
        if (bestX < 0 || best < 0.02f) return null
        val pad = (bestSize * 0.04f).toInt()
        val left = ((bestX - pad).coerceAtLeast(0) * step).toFloat() / w
        val top = ((bestY - pad).coerceAtLeast(0) * step).toFloat() / h
        val right = ((bestX + bestSize + pad).coerceAtMost(sw) * step).toFloat() / w
        val bottom = ((bestY + bestSize + pad).coerceAtMost(sh) * step).toFloat() / h
        return PageStamp.Box(left, top, right, bottom)
    }

    /** The first 8 digits' character span inside a token (for tests and the preview). */
    internal fun firstEightSpan(token: String): IntRange? {
        var seen = 0
        for (i in token.indices) {
            if (token[i].isDigit()) {
                seen++
                if (seen == 8) return 0..i
            }
        }
        return null
    }

    /** Clamp helper shared with the preview UI. */
    fun clamp(box: PageStamp.Box): PageStamp.Box = PageStamp.Box(
        left = min(box.left, box.right).coerceIn(0f, 1f),
        top = min(box.top, box.bottom).coerceIn(0f, 1f),
        right = max(box.left, box.right).coerceIn(0f, 1f),
        bottom = max(box.top, box.bottom).coerceIn(0f, 1f)
    )
}
