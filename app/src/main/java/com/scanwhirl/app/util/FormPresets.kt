package com.scanwhirl.app.util

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    /**
     * A form photo is small by definition. A 12000 x 12000 custom entry would allocate ~576 MB
     * before encoding and can kill the process, so the screen refuses anything above this cap
     * with an honest message instead of trying to rasterize it.
     */
    const val MAX_FORM_LONG_SIDE = 4000
    const val MAX_FORM_PIXELS = 12_000_000L

    /**
     * Lane F1: a picked source is always read on a worker with a hard cap. A single huge gallery
     * image must be refused before its bytes are held in memory, never partly decoded.
     */
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024

    fun readBounded(input: InputStream, limit: Long = MAX_SOURCE_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (total + read > limit) return null
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /** Lane F2: a local saved preset. This file lives in app-private storage; nothing is uploaded. */
    const val PREFS_SAVED_PRESET = "form1_saved_preset"
    private const val K_W = "width"
    private const val K_H = "height"
    private const val K_UNIT = "unit"
    private const val K_DPI = "dpi"
    private const val K_MIN = "min_kb"
    private const val K_MAX = "max_kb"
    private const val K_CLEANUP = "cleanup"

    data class Editable(
        val width: Float,
        val height: Float,
        val unit: FormUnit,
        val dpi: Int,
        val minKb: Int,
        val maxKb: Int,
        val cleanup: Boolean
    )

    fun saveEditable(context: Context, value: Editable) {
        context.getSharedPreferences(PREFS_SAVED_PRESET, Context.MODE_PRIVATE).edit()
            .putFloat(K_W, value.width)
            .putFloat(K_H, value.height)
            .putString(K_UNIT, value.unit.name)
            .putInt(K_DPI, value.dpi)
            .putInt(K_MIN, value.minKb)
            .putInt(K_MAX, value.maxKb)
            .putBoolean(K_CLEANUP, value.cleanup)
            .commit()
    }

    fun loadEditable(context: Context): Editable? {
        val p = context.getSharedPreferences(PREFS_SAVED_PRESET, Context.MODE_PRIVATE)
        if (!p.contains(K_W) || !p.contains(K_H)) return null
        return runCatching {
            val unit = FormUnit.valueOf(p.getString(K_UNIT, FormUnit.PX.name).orEmpty())
            val dpi = p.getInt(K_DPI, 0)
            val minKb = p.getInt(K_MIN, 0)
            val maxKb = p.getInt(K_MAX, 0)
            val w = p.getFloat(K_W, 0f)
            val h = p.getFloat(K_H, 0f)
            if (w <= 0f || h <= 0f || !w.isFinite() || !h.isFinite()) return null
            if (dpi !in SUPPORTED_DPI) return null
            if (minKb < 1 || maxKb < minKb) return null
            val pxW = toPixels(w, unit, dpi)
            val pxH = toPixels(h, unit, dpi)
            if (!fitsFormLimit(pxW, pxH)) return null
            Editable(w, h, unit, dpi, minKb, maxKb, p.getBoolean(K_CLEANUP, false))
        }.getOrNull()
    }

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

    /** True when a target is small enough to be a real form photo and safe to rasterize. */
    fun fitsFormLimit(width: Int, height: Int): Boolean =
        width in 1..MAX_FORM_LONG_SIDE && height in 1..MAX_FORM_LONG_SIDE &&
            maxOf(width, height) <= MAX_FORM_LONG_SIDE &&
            width.toLong() * height.toLong() <= MAX_FORM_PIXELS

    fun isSane(spec: Spec): Boolean =
        fitsFormLimit(spec.widthPx, spec.heightPx) &&
            spec.dpi in SUPPORTED_DPI && spec.minKb in 1..10_000 &&
            spec.maxKb >= spec.minKb && spec.maxKb <= 10_000
}
