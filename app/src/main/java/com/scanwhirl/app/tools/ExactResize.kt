package com.scanwhirl.app.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * FF-R — pure, UI-free geometry and encoding for exact crop-to-size Resize.
 *
 * The old Resize path derived the missing axis from the source ratio or stretched
 * the whole picture to the requested canvas. Neither is a photo/signature crop:
 * a 4:3 source asked for 200 x 230 became a 150-px-tall strip or a distorted face.
 * Here the target aspect is fixed first, a region of that aspect is chosen inside
 * the EXIF-normalised source, and that region is resampled once onto the exact
 * canvas. An optional byte target only searches JPEG quality — it never changes
 * the pixel dimensions, so a miss is reported instead of silently shipping
 * 140 x 161 and calling it 200 x 230.
 */
object ExactResize {

    enum class Mode {
        /** Movable aspect-locked frame: keep only the selected region. Default. */
        CROP,

        /** Aspect-fit the whole source on the exact canvas with white bars. */
        BORDERS
    }

    data class Options(
        val width: Int,
        val height: Int,
        val mode: Mode = Mode.CROP,
        val maxBytes: Long? = null,
        /** Source-space crop frame in EXIF-normalised pixels; null = centred largest. */
        val cropRect: RectF? = null
    )

    data class Plan(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: RectF,
        val targetWidth: Int,
        val targetHeight: Int,
        val mode: Mode
    )

    data class Result(
        val bytes: ByteArray,
        val mime: String,
        /** Read back from the encoded bytes, not copied from the request. */
        val width: Int,
        val height: Int,
        /** False when a byte target was set and the final file did not meet it. */
        val bytesMet: Boolean,
        val plan: Plan
    ) {
        override fun equals(other: Any?): Boolean = other is Result &&
            bytes.contentEquals(other.bytes) && width == other.width && height == other.height &&
            bytesMet == other.bytesMet && plan == other.plan
        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    data class Encoded(val bytes: ByteArray, val bytesMet: Boolean)

    /** A source whose raw pixels cannot be decoded within the phone's memory budget. */
    class TooLargeException : IllegalArgumentException(
        "This image is too large to resize safely. Choose a smaller picture."
    )

    /** A source too thin to yield a usable region at the requested aspect. */
    class InsufficientSourceException : IllegalArgumentException(
        "This picture is too narrow to crop to the requested shape. Choose another image."
    )

    const val MAX_SOURCE_SIDE = 12_000
    const val MAX_SOURCE_PIXELS = 16_000_000L
    const val MIN_CROP_SIDE = 16

    /** Keys for the "Minimum and maximum KB" handoff; Form photo (lane f2) reads them. */
    const val EXTRA_WIDTH = "ffr_form_width"
    const val EXTRA_HEIGHT = "ffr_form_height"
    const val EXTRA_MAX_KB = "ffr_form_max_kb"

    // ---- bounds and decode ------------------------------------------------

    private fun orientation(bytes: ByteArray): Int = try {
        bytes.inputStream().use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
    } catch (e: java.io.IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Bounds in the space the crop works in: EXIF rotation is applied before any crop. */
    fun normalizedSize(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }
        val o = orientation(bytes)
        return if (o in 5..8) bounds.outHeight to bounds.outWidth
        else bounds.outWidth to bounds.outHeight
    }

    /**
     * Decodes at [maxDim] and applies the EXIF transform. BitmapFactory does not
     * auto-rotate, so the explicit matrix is correct on every API level and keeps
     * the preview and the final crop in the same source space.
     */
    fun decodeNormalized(bytes: ByteArray, maxDim: Int): Bitmap {
        val decoded = ImageTools.decodeSampled(bytes, maxOf(1, maxDim))
        val matrix = ImageTools.orientationMatrix(orientation(bytes))
        if (matrix.isIdentity) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    // ---- pure geometry ----------------------------------------------------

    private fun centeredRect(sourceW: Int, sourceH: Int, aspect: Float): RectF {
        var cw = min(sourceW.toFloat(), sourceH * aspect)
        var ch = cw / aspect
        if (ch > sourceH) {
            ch = sourceH.toFloat()
            cw = ch * aspect
        }
        return RectF((sourceW - cw) / 2f, (sourceH - ch) / 2f,
            (sourceW + cw) / 2f, (sourceH + ch) / 2f)
    }

    /** Clamp the source and the frame, then fit an exact target-aspect integer crop. */
    fun plan(sourceWidth: Int, sourceHeight: Int, options: Options): Plan {
        require(options.width in 1..MAX_SOURCE_SIDE && options.height in 1..MAX_SOURCE_SIDE) {
            "Invalid target size"
        }
        if (sourceWidth !in 1..MAX_SOURCE_SIDE || sourceHeight !in 1..MAX_SOURCE_SIDE ||
            sourceWidth.toLong() * sourceHeight.toLong() > MAX_SOURCE_PIXELS
        ) throw TooLargeException()
        if (options.mode == Mode.BORDERS) {
            return Plan(sourceWidth, sourceHeight,
                RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat()),
                options.width, options.height, Mode.BORDERS)
        }

        val aspect = options.width.toFloat() / options.height
        val frame = options.cropRect ?: centeredRect(sourceWidth, sourceHeight, aspect)
        val left = frame.left.coerceIn(0f, sourceWidth.toFloat())
        val top = frame.top.coerceIn(0f, sourceHeight.toFloat())
        val right = frame.right.coerceIn(left, sourceWidth.toFloat())
        val bottom = frame.bottom.coerceIn(top, sourceHeight.toFloat())
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f

        var cw = (right - left).coerceAtMost(sourceWidth.toFloat())
        var ch = cw / aspect
        if (ch > sourceHeight) {
            ch = sourceHeight.toFloat()
            cw = ch * aspect
        }
        if (cw < MIN_CROP_SIDE || ch < MIN_CROP_SIDE) throw InsufficientSourceException()

        val cropW = cw.roundToInt().coerceIn(1, sourceWidth)
        val cropH = (cropW / aspect).roundToInt().coerceIn(1, sourceHeight)
        val cropLeft = (cx - cropW / 2f).roundToInt().coerceIn(0, sourceWidth - cropW)
        val cropTop = (cy - cropH / 2f).roundToInt().coerceIn(0, sourceHeight - cropH)
        return Plan(sourceWidth, sourceHeight,
            RectF(cropLeft.toFloat(), cropTop.toFloat(),
                (cropLeft + cropW).toFloat(), (cropTop + cropH).toFloat()),
            options.width, options.height, Mode.CROP)
    }

    /** One resample onto an opaque white canvas; alpha is composited, never left black. */
    fun render(source: Bitmap, plan: Plan): Bitmap {
        val out = Bitmap.createBitmap(plan.targetWidth, plan.targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (plan.mode == Mode.BORDERS) {
            val scale = min(
                plan.targetWidth.toFloat() / source.width,
                plan.targetHeight.toFloat() / source.height
            )
            val dw = source.width * scale
            val dh = source.height * scale
            canvas.drawBitmap(source, null, RectF(
                (plan.targetWidth - dw) / 2f, (plan.targetHeight - dh) / 2f,
                (plan.targetWidth + dw) / 2f, (plan.targetHeight + dh) / 2f
            ), paint)
        } else {
            val r = plan.cropRect
            val crop = Bitmap.createBitmap(source,
                r.left.roundToInt(), r.top.roundToInt(),
                r.width().roundToInt(), r.height().roundToInt())
            try {
                canvas.drawBitmap(crop, null,
                    RectF(0f, 0f, plan.targetWidth.toFloat(), plan.targetHeight.toFloat()), paint)
            } finally {
                if (crop !== source) crop.recycle()
            }
        }
        return out
    }

    // ---- fixed-pixel encoding ---------------------------------------------

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Resize cancelled")
    }

    /** Quality-only search: the bitmap dimensions are never touched here. */
    fun encodeFixed(bitmap: Bitmap, maxBytes: Long?, isCancelled: () -> Boolean = { false }): Encoded {
        if (maxBytes == null) {
            checkCancelled(isCancelled)
            return Encoded(ImageTools.encodeJpeg(bitmap, 95), true)
        }
        require(maxBytes > 0) { "Target size must be positive" }
        var smallest: ByteArray? = null
        for (quality in 95 downTo 12) {
            checkCancelled(isCancelled)
            val out = ImageTools.encodeJpeg(bitmap, quality)
            if (smallest == null || out.size < smallest.size) smallest = out
            if (out.size <= maxBytes) return Encoded(out, true)
        }
        return Encoded(smallest!!, false)
    }

    /** EXIF-normalise -> plan -> crop/pad once -> fixed-pixel encode -> measure. */
    fun process(
        bytes: ByteArray,
        options: Options,
        isCancelled: () -> Boolean = { false }
    ): Result {
        checkCancelled(isCancelled)
        val (sourceW, sourceH) = normalizedSize(bytes)
        val plan = plan(sourceW, sourceH, options) // budget guard runs before any full decode
        checkCancelled(isCancelled)
        val source = decodeNormalized(bytes, maxOf(sourceW, sourceH))
        try {
            checkCancelled(isCancelled)
            val framed = render(source, plan)
            try {
                checkCancelled(isCancelled)
                val encoded = encodeFixed(framed, options.maxBytes, isCancelled)
                val decoded = ImageTools.imageSize(encoded.bytes)
                    ?: throw IllegalStateException("Could not read back the resized image")
                return Result(encoded.bytes, "image/jpeg", decoded.first, decoded.second,
                    encoded.bytesMet, plan)
            } finally {
                framed.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    /** Max-only sizing lives in Resize; a true min/max band belongs in Form photo. */
    fun formPhotoIntent(context: Context, width: Int, height: Int, maxKb: Int?): Intent =
        FormReadyActivity.intent(context).apply {
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
            if (maxKb != null) putExtra(EXTRA_MAX_KB, maxKb)
        }
}
