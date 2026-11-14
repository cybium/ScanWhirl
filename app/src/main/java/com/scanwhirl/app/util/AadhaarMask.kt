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
    fun find(page: OcrPage): List<PageStamp.Box> =
        page.lines.sortedWith(compareBy({ it.top }, { it.left }))
            .flatMap { hits(it, page) }
            .map { it.box }

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
