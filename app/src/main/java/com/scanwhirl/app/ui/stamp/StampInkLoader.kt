package com.scanwhirl.app.ui.stamp

import android.graphics.Bitmap
import com.scanwhirl.app.util.InkExtract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bitmap ownership stays with the worker until both decode and extraction succeed. */
internal object StampInkLoader {
    suspend fun load(
        file: File,
        decode: (File) -> Bitmap? = { InkExtract.decodeSampled(it) },
        extract: (Bitmap) -> InkExtract.Extracted? = { InkExtract.extract(it, InkExtract.Recolor.ORIGINAL) }
    ): Pair<Bitmap, InkExtract.Extracted>? = withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        try {
            source = decode(file)
            val ink = source?.let(extract)
            if (source == null || ink == null) {
                source?.recycle()
                null
            } else source to ink
        } catch (_: OutOfMemoryError) {
            source?.recycle()
            null
        } catch (_: Exception) {
            source?.recycle()
            null
        }
    }

    suspend fun recolor(source: Bitmap, color: InkExtract.Recolor): InkExtract.Extracted? =
        withContext(Dispatchers.IO) {
            try { InkExtract.extract(source, color) }
            catch (_: OutOfMemoryError) { null }
            catch (_: Exception) { null }
        }
}
