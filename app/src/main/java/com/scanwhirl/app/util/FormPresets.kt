package com.scanwhirl.app.util

import kotlin.math.roundToInt

/** The four editable size units shown on the form-ready screen. */
enum class FormUnit {
    PX, CM, MM, INCH
}

/**
 * FORM1: editable starting points for Indian exam/government form uploads.
 *
 * These are EXAMPLES, not a promise of portal acceptance. The screen always tells the person to
 * check the current notification. The numbers come from the researched IBPS-style ranges; every
 * field is editable, including the pixel dimensions, DPI and the KB band.
 *
 * KB is decimal everywhere ([SizeUnits], 1000 bytes), so a 20 KB minimum means 20,000 bytes.
 */
object FormPresets {

    const val DPI_100 = 100
    const val DPI_200 = 200
    const val DPI_300 = 300
    val SUPPORTED_DPI = intArrayOf(DPI_100, DPI_200, DPI_300)

    data class Spec(
        val id: String,
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val minKb: Int,
        val maxKb: Int,
        val signatureCleanup: Boolean = false
    ) {
        val minBytes: Long get() = minKb * SizeUnits.BYTES_PER_KB
        val maxBytes: Long get() = maxKb * SizeUnits.BYTES_PER_KB
        val aspect: Float get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)
    }

    val PHOTO = Spec("photo", 200, 230, DPI_200, 20, 50)
    val SIGNATURE = Spec("signature", 140, 60, DPI_200, 10, 20, signatureCleanup = true)
    val THUMB = Spec("thumb", 240, 240, DPI_200, 20, 50)
    val DECLARATION = Spec("declaration", 800, 400, DPI_200, 50, 100)
    val CUSTOM = Spec("custom", 200, 230, DPI_200, 20, 50)

    val ALL = listOf(PHOTO, SIGNATURE, THUMB, DECLARATION, CUSTOM)

    fun byId(id: String): Spec = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /**
     * Physical size to pixels at [dpi]. Pixel entry ignores DPI: the pixel count is the ground
     * truth a form asks for, while cm/mm/inch are a convenience that derives pixels from DPI.
     */
    fun toPixels(value: Float, unit: FormUnit, dpi: Int): Int {
        require(value > 0f && value.isFinite()) { "Size must be positive" }
        require(dpi in 1..65535) { "DPI must be positive" }
        val px = when (unit) {
            FormUnit.PX -> value
            FormUnit.CM -> value / 2.54f * dpi
            FormUnit.MM -> value / 25.4f * dpi
            FormUnit.INCH -> value * dpi
        }
        return px.roundToInt().coerceIn(1, 12000)
    }

    /** The inverse of [toPixels], used when a preset fills the editable fields. */
    fun fromPixels(px: Int, unit: FormUnit, dpi: Int): Float = when (unit) {
        FormUnit.PX -> px.toFloat()
        FormUnit.CM -> px * 2.54f / dpi
        FormUnit.MM -> px * 25.4f / dpi
        FormUnit.INCH -> px.toFloat() / dpi
    }

    fun isSane(spec: Spec): Boolean =
        spec.widthPx in 1..12000 && spec.heightPx in 1..12000 &&
            spec.dpi in SUPPORTED_DPI && spec.minKb in 1..10_000 &&
            spec.maxKb >= spec.minKb && spec.maxKb <= 10_000
}
