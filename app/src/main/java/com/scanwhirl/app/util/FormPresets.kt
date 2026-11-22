package com.scanwhirl.app.util

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
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
 * FX2: form KB is BINARY (1024 bytes), the unit the phone portal verifier and exam notifications
 * use. [SizeUnits] stays decimal for the rest of the app; only this tool's band and readout use
 * [BYTES_PER_KB], so "20 KB" is never accepted at 20,000 bytes (19.5 KiB) again.
 */
object FormPresets {

    const val BYTES_PER_KB = 1_024L

    /** The rounded KB shown to the person, always derived from the SAVED file's byte length. */
    fun formatKb(bytes: Long): String =
        String.format(Locale.ROOT, "%.0f KB", bytes / BYTES_PER_KB.toDouble())

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
        val minBytes: Long get() = minKb * BYTES_PER_KB
        val maxBytes: Long get() = maxKb * BYTES_PER_KB
        val aspect: Float get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)
    }

    val PHOTO = Spec("photo", 200, 230, DPI_200, 20, 50)
    // FX2: cleanup is opt-in. The threshold must never be the default, because a bad threshold
    // destroyed the ink and painted paper shadow black on the owner's phone.
    val SIGNATURE = Spec("signature", 140, 60, DPI_200, 10, 20, signatureCleanup = false)
    val THUMB = Spec("thumb", 240, 240, DPI_200, 20, 50)
    val DECLARATION = Spec("declaration", 800, 400, DPI_200, 50, 100)
    val CUSTOM = Spec("custom", 200, 230, DPI_200, 20, 50)

    /**
     * FORMPIX: exam-named editable examples. The KB band is the band in the public notice; the
     * pixel size is only a SUGGESTION from third-party guides, so the screen marks it as such and
     * leaves both fields editable. Nothing here promises a portal will accept the file.
     */
    val NEET_UG_PHOTO = Spec("neet_ug_photo", 350, 450, DPI_200, 10, 200)
    val NEET_UG_SIGNATURE = Spec("neet_ug_signature", 400, 150, DPI_200, 4, 30)
    val NEET_UG_THUMB = Spec("neet_ug_thumb", 400, 150, DPI_200, 4, 30)
    val NEET_PG_PHOTO = Spec("neet_pg_photo", 350, 450, DPI_200, 5, 80)
    val NEET_PG_SIGNATURE = Spec("neet_pg_signature", 276, 118, DPI_200, 20, 80)
    val NEET_PG_THUMB = Spec("neet_pg_thumb", 276, 118, DPI_200, 5, 80)

    /** The compact exam row, in screen order. */
    val EXAM = listOf(
        NEET_UG_PHOTO, NEET_UG_SIGNATURE, NEET_UG_THUMB,
        NEET_PG_PHOTO, NEET_PG_SIGNATURE, NEET_PG_THUMB
    )

    val ALL = listOf(PHOTO, SIGNATURE, THUMB, DECLARATION, CUSTOM) + EXAM

    fun byId(id: String): Spec = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /** Exam presets mark their pixels as suggested rather than mandatory. */
    fun isExam(id: String): Boolean = id.startsWith("neet_")

    /** Exam signatures behave like the generic signature: ink framing, cleanup stays opt-in. */
    fun isSignatureLike(id: String): Boolean =
        id in setOf("signature", "neet_ug_signature", "neet_pg_signature")

    /** Human file-name role, shared by generic and exam presets. */
    fun outputRole(id: String): String = when {
        isSignatureLike(id) -> "signature"
        id == "thumb" || id == "neet_ug_thumb" || id == "neet_pg_thumb" -> "thumb"
        id == "declaration" -> "declaration"
        else -> "photo"
    }

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
