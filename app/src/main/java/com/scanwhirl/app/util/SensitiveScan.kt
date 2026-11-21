package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrPage
import kotlin.math.max
import kotlin.math.min

/**
 * L5 "Hide private info": one scan over OCR element boxes that finds the sensitive items a
 * person expects a redaction tool to catch.
 *
 * Every detector works on **element boxes** (ML Kit words), never guesses from the page image:
 *  - a line is words that share a vertical band, sorted left to right;
 *  - a hit is the union of the characters it covers, padded 15% of the glyph height;
 *  - ID numbers hide the whole number by default; "Keep last 4 digits" switches Aadhaar/VID to
 *    the leading-digits mask;
 *  - a card keeps its last 4 digits, stopping in the gap before them;
 *  - the box is clipped vertically so it can never cover the OCR line above or below;
 *  - overlapping candidates are resolved by specificity (email/IBAN/PAN/DL/passport/SSN/phone/
 *    card/Aadhaar).
 *
 * Dates ("2024-11-15", "15/11/2024"), prices ("1,299.00") and short order numbers never match:
 * the digit-run detector needs 9+ digits and does not cross commas, dots or slashes.
 */
object SensitiveScan {

    enum class Kind {
        AADHAAR, VID, PAN, DRIVING_LICENCE, PASSPORT, CARD, PHONE, EMAIL, IBAN, SSN, ACCOUNT
    }

    data class Hit(val kind: Kind, val text: String, val box: PageStamp.Box)

    /** A word/element box, in the decoded bitmap's pixels. */
    data class Word(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class Candidate(
        val kind: Kind,
        val start: Int,
        val end: Int,
        val maskEnd: Int,
        val text: String,
        val digits: Int = 0,
        /** Lower wins an overlap; bank-specific detections outrank a plain phone/account. */
        val priority: Int = PRIORITY[kind] ?: 99
    )

    private val PRIORITY = mapOf(
        Kind.EMAIL to 0, Kind.IBAN to 1, Kind.PAN to 2, Kind.DRIVING_LICENCE to 3, Kind.PASSPORT to 4, Kind.SSN to 5, Kind.PHONE to 6,
        Kind.CARD to 7, Kind.AADHAAR to 8, Kind.VID to 9, Kind.ACCOUNT to 10
    )

    // Generic account/card/VID runs keep the original single-separator rule. Dots, commas and
    // repeated separators are handled only by the shape-specific detectors below, so a decimal
    // amount ("Rs 1234567.89") or an IPv4 address ("192.168.1.100") is never an ACCOUNT.
    private val DIGIT_RUN = Regex("(?<!\\d)\\d(?:[ \\t-]?\\d){8,18}(?!\\d)")
    // Aadhaar only: 4-4-4 with space/double-space/dot/hyphen, or the comma-grouped 4-4-4 and
    // 2-2-2-2-2-2 forms. lineHits() also rejects a match that touches another digit group, so a
    // 16-digit VID is never reported as two Aadhaar numbers.
    private val AADHAAR_RUN = Regex(
        "(?<!\\d)(?:\\d{4}(?:[ .\\t-]+\\d{4}){2}|\\d{4}(?:,\\d{4}){2}|\\d{2}(?:,\\d{2}){5})(?!\\d)"
    )
    private val GROUP_SEPARATORS = setOf(' ', '\t', '.', '-', ',')
    // +91 (98765) 43210, 091 98765 43210, +91-98765-43210, +91.98765.43210, plain 98765 43210.
    private val INDIAN_PHONE = Regex("(?<!\\d)(?:\\+?0?91)?[ \\t().-]*[6-9]\\d{4}[ \\t().-]*\\d{5}(?!\\d)")
    private val INTL_PHONE = Regex("(?<!\\d)\\+\\d{1,3}(?:[ \\t.-]?\\(?\\d{1,4}\\)?){2,4}(?!\\d)")
    private val LOCAL_PHONE = Regex("(?<!\\d)\\(?\\d{3}\\)?[ \\t.-]\\d{3}[ \\t.-]\\d{4}(?!\\d)")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val PAN = Regex("(?<![A-Z0-9])[A-Z]{5}\\d{4}[A-Z](?![A-Z0-9])")
    private val SSN = Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")
    private val SSN_SHAPE = Regex("\\d{3}-\\d{2}-\\d{4}")
    private val DRIVING_LICENCE =
        Regex("(?<![A-Z0-9])[A-Z]{2}[- ]?\\d{2}[- ]?\\d{4}[- ]?\\d{7}(?![A-Z0-9])")
    private val PASSPORT = Regex("(?<![A-Z0-9])[A-Z]\\d{7}(?![A-Z0-9])")
    private val IBAN = Regex("(?<![A-Z0-9])[A-Z]{2}\\d{2}[A-Z0-9 ]{10,30}(?![A-Z0-9])")
    // IFSC: four letters, a literal zero, six alphanumerics (SBIN0001234). Matched on the
    // upper-cased original text: normalize() would turn the letter I into a 1.
    private val IFSC = Regex("(?<![A-Z0-9])[A-Z]{4}[ \\t]*0[A-Z0-9]{6}(?![A-Z0-9])")
    // UPI VPA: handle@psp with no TLD dot (ravi@oksbi, name.surname@ybl, 9876543210@paytm).
    // The lookaheads keep a.b@example.com with its .com TLD as an ordinary EMAIL.
    private val UPI = Regex(
        "(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]+@[A-Za-z][A-Za-z0-9]*(?![A-Za-z0-9\\-])(?!\\.[A-Za-z]{2,})"
    )

    // ------------------------------------------------------------------ public API

    /**
     * Sensitive items in one page of OCR lines, limited to [kinds]. [fullMask] makes every box
     * cover the whole match; the Redact "Suggest" path uses it so no digits stay visible on the
     * hidden copy. The Find path leaves [fullMask] off and keeps its documented last-4 UX.
     */
    fun find(
        page: OcrPage,
        kinds: Set<Kind> = Kind.values().toSet(),
        keepLast4Ids: Boolean = false,
        fullMask: Boolean = false
    ): List<Hit> {
        if (page.width <= 0 || page.height <= 0) return emptyList()
        val words = page.lines.map { line ->
            Word(line.text, line.left, line.top, line.right, line.bottom)
        }
        return find(words, page.width, page.height, kinds, keepLast4Ids, fullMask)
    }

    /** Sensitive items in ML Kit element boxes, limited to [kinds]. */
    fun find(
        words: List<Word>,
        pageWidth: Int,
        pageHeight: Int,
        kinds: Set<Kind> = Kind.values().toSet(),
        keepLast4Ids: Boolean = false,
        fullMask: Boolean = false
    ): List<Hit> {
        if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
        val valid = words.filter {
            it.right > it.left && it.bottom > it.top && it.text.isNotBlank()
        }.sortedWith(compareBy({ it.top }, { it.left }))
        val lines = groupLines(valid)
        val out = ArrayList<Hit>()
        for (line in lines) {
            out += lineHits(line, lines, pageWidth, pageHeight, kinds, keepLast4Ids, fullMask)
        }
        return out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    // ------------------------------------------------------------------ detection

    private fun groupLines(words: List<Word>): List<List<Word>> {
        val lines = ArrayList<MutableList<Word>>()
        for (word in words) {
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
            lines += mutableListOf(word)
        }
        return lines
    }

    private fun lineHits(
        line: List<Word>,
        allLines: List<List<Word>>,
        pageWidth: Int,
        pageHeight: Int,
        kinds: Set<Kind>,
        keepLast4Ids: Boolean,
        fullMask: Boolean
    ): List<Hit> {
        val sorted = line.sortedBy { it.left }
        val builder = StringBuilder()
        val starts = IntArray(sorted.size)
        sorted.forEachIndexed { index, word ->
            if (index > 0) builder.append(' ')
            starts[index] = builder.length
            builder.append(word.text)
        }
        val text = builder.toString()
        if (text.isBlank()) return emptyList()
        val digits = AadhaarMask.normalize(text)
        val upper = text.uppercase()
        val candidates = ArrayList<Candidate>()

        fun add(kind: Kind, start: Int, endExclusive: Int, maskEnd: Int, priority: Int = PRIORITY[kind] ?: 99) {
            if (start < 0 || endExclusive <= start || start >= text.length) return
            val end = min(endExclusive, text.length)
            val masked = if (fullMask) end else min(maskEnd, end)
            candidates += Candidate(kind, start, end, masked, text.substring(start, end).trim(), priority = priority)
        }

        // Phone formats first: they must win over "long account number".
        for (m in INDIAN_PHONE.findAll(digits)) {
            add(Kind.PHONE, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in INTL_PHONE.findAll(digits)) {
            val count = m.value.count { it.isDigit() }
            if (count in 8..15) add(Kind.PHONE, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in LOCAL_PHONE.findAll(digits)) {
            if (m.value.count { it.isDigit() } == 10) {
                add(Kind.PHONE, m.range.first, m.range.last + 1, m.range.last + 1)
            }
        }

        // Bank identifiers: IFSC and UPI VPAs belong to the existing "Card & bank numbers"
        // category (Kind.ACCOUNT), so the Find sheet picks them up without a new choice.
        // A dedicated priority keeps a numeric VPA from being mistaken for a plain phone.
        for (m in IFSC.findAll(upper)) {
            add(Kind.ACCOUNT, m.range.first, m.range.last + 1, m.range.last + 1, priority = 5)
        }
        for (m in UPI.findAll(text)) {
            add(Kind.ACCOUNT, m.range.first, m.range.last + 1, m.range.last + 1, priority = 5)
        }

        // Emails, PAN, IBAN and SSN need the original letters, not the digit-normalised copy.
        for (m in EMAIL.findAll(text)) {
            add(Kind.EMAIL, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in PAN.findAll(upper)) {
            add(Kind.PAN, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in IBAN.findAll(upper)) {
            val iban = m.value.trimEnd()
            if (ibanValid(iban)) add(Kind.IBAN, m.range.first, m.range.first + iban.length, m.range.first + iban.length)
        }
        for (m in DRIVING_LICENCE.findAll(upper)) {
            add(Kind.DRIVING_LICENCE, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in PASSPORT.findAll(upper)) {
            add(Kind.PASSPORT, m.range.first, m.range.last + 1, m.range.last + 1)
        }
        for (m in SSN.findAll(digits)) {
            if (ssnValid(m.value)) add(Kind.SSN, m.range.first, m.range.last + 1, m.range.last + 1)
        }

        // Aadhaar in the widened shapes (double space, dot, hyphen, comma grouping). This runs
        // before the generic digit run; DIGIT_RUN may also match the plain 4-4-4 form, but the
        // overlap resolution keeps just one Aadhaar candidate.
        fun touchesDigitGroup(from: Int, step: Int): Boolean {
            var i = from
            while (i in digits.indices && digits[i] in GROUP_SEPARATORS) i += step
            return i in digits.indices && digits[i].isDigit()
        }
        for (m in AADHAAR_RUN.findAll(digits)) {
            val start = m.range.first
            val end = m.range.last + 1
            // A 4-4-4 tail inside a 16-digit VID or a longer run is not an Aadhaar.
            if (touchesDigitGroup(start - 1, -1) || touchesDigitGroup(end, 1)) continue
            val maskEnd = if (keepLast4Ids) digitBoundary(digits, start, end, 8) else end
            add(Kind.AADHAAR, start, end, maskEnd)
        }

        // Numbered formats: Aadhaar (12), VID (16), cards (Luhn), accounts (9-18).
        for (m in DIGIT_RUN.findAll(digits)) {
            val start = m.range.first
            val end = m.range.last + 1
            val raw = digits.substring(start, end)
            val count = raw.count { it.isDigit() }
            when {
                count == 12 -> {
                    val maskEnd = if (keepLast4Ids) digitBoundary(digits, start, end, 8) else end
                    add(Kind.AADHAAR, start, end, maskEnd)
                }
                count == 16 -> {
                    if (luhn(raw)) {
                        add(Kind.CARD, start, end, digitBoundary(digits, start, end, count - 4))
                    } else {
                        val maskEnd = if (keepLast4Ids) {
                            digitBoundary(digits, start, end, 12)
                        } else {
                            end
                        }
                        add(Kind.VID, start, end, maskEnd)
                    }
                }
                count in 13..19 -> {
                    if (luhn(raw)) {
                        add(Kind.CARD, start, end, digitBoundary(digits, start, end, count - 4))
                    } else if (!SSN_SHAPE.matches(raw.trim())) {
                        add(Kind.ACCOUNT, start, end, end)
                    }
                }
                count in 9..11 -> {
                    // A 3-2-4 hyphenated run is an SSN, valid or not; never an account.
                    if (!SSN_SHAPE.matches(raw.trim())) add(Kind.ACCOUNT, start, end, end)
                }
            }
        }

        // Specific detectors win an overlap (a card is not also an account; SSN is not an account).
        val chosen = ArrayList<Candidate>()
        candidates.sortedWith(
            compareBy({ it.priority }, { it.start })
        ).forEach { candidate ->
            if (chosen.none { it.start < candidate.end && candidate.start < it.end }) {
                chosen += candidate
            }
        }
        chosen.sortBy { it.start }

        val lineTop = sorted.minOf { it.top }
        val lineBottom = sorted.maxOf { it.bottom }
        val lineHeight = (lineBottom - lineTop).toFloat().coerceAtLeast(1f)
        val padX = lineHeight * 0.15f
        val padY = lineHeight * 0.10f
        val topBound = lineTop - padY
        val bottomBound = lineBottom + padY

        fun xAt(index: Int): Float {
            for (i in sorted.indices) {
                val wordStart = starts[i]
                val wordEnd = wordStart + sorted[i].text.length
                if (index in wordStart..wordEnd) {
                    val word = sorted[i]
                    val width = (word.right - word.left).toFloat()
                    val frac = if (word.text.isEmpty()) 0f
                    else (index - wordStart).toFloat() / word.text.length
                    return word.left + frac * width
                }
            }
            return sorted.last().right.toFloat()
        }

        fun firstDigitAtOrAfter(from: Int): Int {
            for (i in from until digits.length) if (digits[i].isDigit()) return i
            return -1
        }

        val out = ArrayList<Hit>()
        for (c in chosen) {
            var left = xAt(c.start) - padX
            var right = xAt(c.end) + padX
            if (c.maskEnd < c.end) {
                val next = firstDigitAtOrAfter(c.maskEnd)
                if (next in 0 until digits.length) {
                    val nextX = xAt(next)
                    right = min((xAt(c.maskEnd) + nextX) / 2f, nextX)
                } else {
                    right = xAt(c.maskEnd) + padX
                }
            }
            // Vertical: never cross into another OCR line.
            var top = topBound
            var bottom = bottomBound
            for (other in allLines) {
                if (other === line) continue
                val otherTop = other.minOf { it.top }.toFloat()
                val otherBottom = other.maxOf { it.bottom }.toFloat()
                val horizontal = other.any { it.left < right && it.right > left }
                if (!horizontal) continue
                if (otherBottom <= lineTop) top = max(top, otherBottom)
                if (otherTop >= lineBottom) bottom = min(bottom, otherTop)
            }
            out += Hit(
                c.kind,
                c.text,
                PageStamp.Box(
                    (left / pageWidth).coerceIn(0f, 1f),
                    (top / pageHeight).coerceIn(0f, 1f),
                    (right / pageWidth).coerceIn(0f, 1f),
                    (bottom / pageHeight).coerceIn(0f, 1f)
                )
            )
        }
        return out.filter { it.kind in kinds }
    }

    /** The character index just after the [n]th digit inside a matched run. */
    private fun digitBoundary(text: String, start: Int, end: Int, n: Int): Int {
        if (n <= 0) return start
        var seen = 0
        for (i in start until end) {
            if (text[i].isDigit()) {
                seen++
                if (seen == n) return i + 1
            }
        }
        return end
    }

    // ------------------------------------------------------------------ checks

    fun luhn(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var value = digits[i] - '0'
            if (double) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }

    fun ibanValid(text: String): Boolean {
        val compact = text.replace(" ", "").uppercase()
        if (compact.length < 15 || compact.length > 34) return false
        if (!compact.substring(0, 2).all { it in 'A'..'Z' }) return false
        if (!compact.substring(2, 4).all { it.isDigit() }) return false
        val rearranged = compact.substring(4) + compact.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val value = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> return false
            }
            remainder = if (value >= 10) (remainder * 100 + value) % 97
            else (remainder * 10 + value) % 97
        }
        return remainder == 1
    }

    private fun ssnValid(text: String): Boolean {
        val parts = text.split('-')
        if (parts.size != 3 || parts[0].length != 3 || parts[1].length != 2 || parts[2].length != 4) {
            return false
        }
        val area = parts[0].toIntOrNull() ?: return false
        val group = parts[1].toIntOrNull() ?: return false
        val serial = parts[2].toIntOrNull() ?: return false
        if (area == 0 || area == 666 || area >= 900) return false
        if (group == 0 || serial == 0) return false
        return true
    }
}
