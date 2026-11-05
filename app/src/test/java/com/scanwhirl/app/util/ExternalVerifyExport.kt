package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExternalVerifyExport {

    @Test
    fun `export sample pdf for external verification`() {
        val dir = File("/tmp/opencode/pdf_verify").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val pages = (1..3).mapIndexed { i, _ ->
            val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(255 - i * 30, 240, 220))
            val paint = Paint().apply { textSize = 80f; color = Color.BLACK }
            c.drawText("ScanWhirl page ${i + 1}", 100f, 200f + i * 400, paint)
            val f = File(dir, "page_$i.jpg")
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            bmp.recycle()
            f
        }

        val pdf = File(dir, "sample.pdf")
        PdfGenerator.imagesToPdf(pages, pdf)
        println("EXPORTED: ${pdf.absolutePath} (${pdf.length()} bytes)")
    }

}
