package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * L5: imported company stamps ("My stamps"), beside the preset Paid/Approved/... badges.
 *
 * Each stamp is a transparent PNG (background removed by `InkExtract`) plus the same small
 * metadata file as a signature, in `filesDir/stamps/`. Colour is kept (purple/red/blue), and a
 * circular stamp is never squared off: the extracted bitmap keeps its own aspect ratio.
 */
class StampStore(context: android.content.Context) {

    data class SavedStamp(
        val id: String,
        val name: String,
        val createdAt: Long,
        val pngFile: File
    )

    private val dir: File = File(context.filesDir, "stamps")

    companion object {
        const val MAX_STAMPS = 10
        private const val HEADER = "#scanwhirl-stamp-v1"
        private const val EXT = ".stamp"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }

    /** Newest first; a damaged record is skipped. */
    fun list(): List<SavedStamp> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files.mapNotNull { load(it.nameWithoutExtension) }
            .sortedWith(compareByDescending<SavedStamp> { it.createdAt }.thenBy { it.id })
    }

    fun load(id: String): SavedStamp? {
        if (!SAFE_ID.matches(id)) return null
        val manifest = File(dir, id + EXT)
        if (!manifest.isFile) return null
        return try {
            val lines = manifest.readLines()
            if (lines.firstOrNull() != HEADER || lines.size < 3) return null
            val createdAt = lines[1].toLongOrNull() ?: return null
            val name = String(
                android.util.Base64.decode(lines[2], android.util.Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val png = File(dir, id + ".png")
            if (!png.isFile || png.length() == 0L) return null
            SavedStamp(id, name, createdAt, png)
        } catch (_: Exception) {
            null
        }
    }

    fun save(name: String, bitmap: android.graphics.Bitmap): SavedStamp {
        require(!bitmap.isRecycled) { "The stamp image is no longer in memory." }
        require(bitmap.width >= 8 && bitmap.height >= 8) { "No stamp was found in this photo." }
        if (list().size >= MAX_STAMPS) {
            throw IllegalStateException(
                "You can keep up to $MAX_STAMPS stamps. Delete one to save another."
            )
        }
        val id = newId()
        val png = File(dir, id + ".png")
        val out = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
            throw IOException("The stamp image could not be encoded.")
        }
        dir.mkdirs()
        try {
            Durable.writeFile(png, out.toByteArray())
            val manifest = buildString {
                append(HEADER).append('\n')
                append(System.currentTimeMillis()).append('\n')
                append(
                    android.util.Base64.encodeToString(
                        name.trim().ifBlank { "Stamp" }.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                ).append('\n')
            }
            Durable.atomicWriteText(File(dir, id + EXT), manifest)
        } catch (t: Throwable) {
            png.delete()
            File(dir, id + EXT).delete()
            throw t
        }
        return requireNotNull(load(id)) { "The stamp could not be saved." }
    }

    fun delete(id: String) {
        if (!SAFE_ID.matches(id)) return
        File(dir, id + EXT).delete()
        File(dir, id + ".png").delete()
    }

    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            if (!File(dir, id + EXT).exists()) return id
        }
    }
}
