package com.scanwhirl.app.model

/**
 * A single recognised line of text with its bounding box in page-pixel
 * coordinates. [left], [top], [right], [bottom] are the OCR engine's
 * reported geometry for this line.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/**
 * One page's OCR result: the page's pixel dimensions and the recognised
 * lines in reading order.
 */
data class OcrPage(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>
)
