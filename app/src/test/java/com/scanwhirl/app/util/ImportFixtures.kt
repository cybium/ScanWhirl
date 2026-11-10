package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Synthetic inputs only; no photographs or user documents. */
object ImportFixtures {
    fun image(format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
              width: Int = 120, height: Int = 80, transparent: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
            val paint = Paint()
            // Distinct quadrants make rotations AND mirror errors observable.
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, color ->
                paint.color = color
                val x = (i % 2) * width / 2f
                val y = (i / 2) * height / 2f
                canvas.drawRect(x, y, x + width / 2f - 8, y + height / 2f - 8, paint)
            }
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 95, it)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun oriented(file: File, orientation: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
                 width: Int = 120, height: Int = 80): ByteArray {
        file.writeBytes(image(format, width, height))
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    fun assertJpeg(bytes: ByteArray): Bitmap {
        assertEquals(0xFF, bytes[0].toInt() and 255)
        assertEquals(0xD8, bytes[1].toInt() and 255)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("JPEG must actually decode", bitmap)
        return bitmap!!
    }

    fun assertColor(expected: Int, actual: Int, tolerance: Int = 25) {
        assertTrue("expected $expected, got $actual", listOf(
            Color.red(expected) - Color.red(actual),
            Color.green(expected) - Color.green(actual),
            Color.blue(expected) - Color.blue(actual)
        ).all { kotlin.math.abs(it) <= tolerance })
    }
}
