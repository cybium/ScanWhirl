package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Lenient, bounded image preparation for "OCR a gallery image".
 *
 * This is deliberately NOT the page-import contract ([ImageTools.normalizePage]):
 * the picture is only read, never saved, so there is no PNG/JPEG/WebP-only rule,
 * no container strictness, no re-encode and no 6 MP page budget. It accepts
 * whatever this Android version can decode (BMP, GIF, HEIC where supported, ...),
 * applies EXIF orientation when it is readable, and keeps full resolution up to
 * [MAX_OCR_PIXELS] so small print stays legible.
 */
object OcrImagePrep {
    /** Memory ceiling for the decoded bitmap: 4096 x 4096 ARGB_8888 is 64 MiB. */
    internal const val MAX_OCR_PIXELS = 16_777_216L

    /** Refuse absurd declared sizes before the decoder is asked to do any work. */
    internal const val MAX_SOURCE_PIXELS = 150_000_000L

    private const val UNREADABLE = "Cannot read this image. Choose a different image file."

    /** Bounded read of the encoded file (32 MB), with an error the user can act on. */
    fun readBytes(input: InputStream): ByteArray = ImageTools.readBounded(
        input, "This image exceeds 32 MB. Choose a smaller photo or a lower-resolution copy and try again."
    )

    /** Power-of-two sample so the decoded bitmap has at most [MAX_OCR_PIXELS] pixels. */
    internal fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        var sample = 1
        fun side(size: Int) = (size.toLong() + sample - 1) / sample
        while (side(width) * side(height) > MAX_OCR_PIXELS) sample *= 2
        return sample
    }

    /**
     * Decodes [bytes] for text recognition. The caller owns (and recycles) the bitmap.
     * Never modifies the source bytes.
     */
    fun decode(bytes: ByteArray): Bitmap {
        require(bytes.isNotEmpty()) { UNREADABLE }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { UNREADABLE }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
            "This image is too large to read (over 150 megapixels). Choose a smaller copy and try again."
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IllegalArgumentException(UNREADABLE)
            val matrix = ImageTools.orientationMatrix(orientationOf(bytes))
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            return bitmap!!
        } catch (e: OutOfMemoryError) {
            bitmap?.recycle()
            throw IllegalStateException(
                "Not enough memory to read this image. Close other apps or choose a smaller copy and try again.", e
            )
        } catch (e: Throwable) {
            bitmap?.recycle()
            throw e
        }
    }

    /** Unreadable, absent or out-of-range orientation (and non-EXIF formats) is identity. */
    private fun orientationOf(bytes: ByteArray): Int = try {
        bytes.inputStream().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
}
