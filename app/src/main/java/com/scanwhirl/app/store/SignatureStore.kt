package com.scanwhirl.app.store

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PageStamp
import java.io.File
import java.util.UUID

/**
 * L5 Phase A — saved signatures.
 *
 * Up to [MAX_SIGNATURES] signatures live in `filesDir/signatures/<id>.sig` as **vector
 * paths** (normalised points), not bitmaps, so they stay crisp at any placed size and can be
 * rotated by the stamp engine. Nothing here is user-visible outside the app's private storage.
 *
 * The file format is a small line protocol:
 *
 * ```
 * #scanwhirl-signature-v1
 * <id>
 * <createdAtMillis>
 * <name, Base64 UTF-8>
 * <strokeCount>
 * <x>,<y> <x>,<y> ...      one line per stroke
 * ```
 *
 * A damaged file is skipped, never repaired by guessing. It does not consume one of the five
 * slots, so a corrupt file can never lock the owner out of saving a new signature.
 */
class SignatureStore(context: Context) {

    data class SavedSignature(
        val id: String,
        val name: String,
        val strokes: List<PageStamp.Stroke>,
        val createdAt: Long,
        /** Set for an imported bitmap signature (transparent PNG); null for drawn ink. */
        val pngFile: File? = null,
        val aspectRatio: Float = 3f
    ) {
        val isBitmap: Boolean get() = pngFile != null
    }

    private val dir: File = File(context.filesDir, "signatures")

    companion object {
        const val MAX_SIGNATURES = 5
        private const val HEADER = "#scanwhirl-signature-v1"
        private const val HEADER_V2 = "#scanwhirl-signature-v2"
        private const val EXT = ".sig"
        private const val PNG = ".png"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }

    /** Newest first. Damaged files are skipped. */
    fun list(): List<SavedSignature> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files
            .mapNotNull { load(it.nameWithoutExtension) }
            .sortedWith(compareByDescending<SavedSignature> { it.createdAt }.thenBy { it.id })
    }

    fun load(id: String): SavedSignature? {
        if (!SAFE_ID.matches(id)) return null
        val file = File(dir, id + EXT)
        if (!file.isFile) return null
        return try {
            parse(id, file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saves a new signature. Refuses a sixth, and refuses ink that has no point at all.
     * [PageStamp.Stroke]s with no points are dropped.
     */
    fun save(name: String, strokes: List<PageStamp.Stroke>, aspectRatio: Float = 3f): SavedSignature {
        require(aspectRatio.isFinite() && aspectRatio > 0f)
        val clean = strokes.mapNotNull { stroke ->
            val points = stroke.points.filter { it.x.isFinite() && it.y.isFinite() }
            if (points.isEmpty()) null else PageStamp.Stroke(points)
        }
        require(clean.isNotEmpty()) { "Draw or write your signature first." }
        if (list().size >= MAX_SIGNATURES) {
            throw IllegalStateException(
                "You can keep up to $MAX_SIGNATURES signatures. Delete one to save another."
            )
        }
        val id = newId()
        val saved = SavedSignature(
            id = id,
            name = name.trim().ifBlank { "Signature" },
            strokes = clean,
            createdAt = System.currentTimeMillis(),
            aspectRatio = aspectRatio
        )
        dir.mkdirs()
        Durable.atomicWriteText(File(dir, id + EXT), encode(saved))
        return saved
    }

    /** Manifest first is not the commit point here: the PNG is fsynced before this is called. */
    private fun writeManifest(saved: SavedSignature) {
        Durable.atomicWriteText(File(dir, saved.id + EXT), encode(saved))
    }

    /** Deletes one signature (and its PNG when it is a bitmap signature). */
    fun delete(id: String) {
        if (!SAFE_ID.matches(id)) return
        File(dir, id + EXT).delete()
        File(dir, id + PNG).delete()
    }

    /**
     * Saves an imported signature (transparent PNG) beside the drawn ones. The 5-signature limit
     * counts both kinds. The bitmap is stored as PNG, never as a raw bitmap.
     */
    fun saveBitmap(name: String, bitmap: android.graphics.Bitmap): SavedSignature {
        require(!bitmap.isRecycled) { "The signature image is no longer in memory." }
        require(bitmap.width >= 8 && bitmap.height >= 8) { "No ink was found in this photo." }
        if (list().size >= MAX_SIGNATURES) {
            throw IllegalStateException(
                "You can keep up to $MAX_SIGNATURES signatures. Delete one to save another."
            )
        }
        val id = newId()
        val png = File(dir, id + PNG)
        val out = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
            throw IllegalStateException("The signature image could not be encoded.")
        }
        val saved = SavedSignature(
            id = id,
            name = name.trim().ifBlank { "Signature" },
            strokes = emptyList(),
            createdAt = System.currentTimeMillis(),
            pngFile = png
        )
        dir.mkdirs()
        try {
            com.scanwhirl.app.util.Durable.writeFile(png, out.toByteArray())
            writeManifest(saved)
        } catch (t: Throwable) {
            png.delete()
            File(dir, id + EXT).delete()
            throw t
        }
        return saved
    }

    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            if (!File(dir, id + EXT).exists()) return id
        }
    }

    private fun encode(saved: SavedSignature): String {
        if (saved.pngFile != null) {
            return buildString {
                append(HEADER_V2).append('\n')
                append(saved.id).append('\n')
                append(saved.createdAt).append('\n')
                append(
                    Base64.encodeToString(saved.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                ).append('\n')
                append("kind=bitmap\n")
            }
        }
        return buildString {
            append(HEADER).append('\n')
            append(saved.id).append('\n')
            append(saved.createdAt).append('\n')
            append(
                Base64.encodeToString(saved.name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            ).append('\n')
            append(saved.strokes.size).append('\n')
            saved.strokes.forEach { stroke ->
                append(stroke.points.joinToString(" ") { "${it.x},${it.y}" }).append('\n')
            }
            append("aspect=").append(saved.aspectRatio).append('\n')
        }
    }

    private fun parse(id: String, text: String): SavedSignature? {
        val lines = text.lines()
        val header = lines.firstOrNull()
        if (header == HEADER_V2) {
            if (lines.size < 5 || lines[4] != "kind=bitmap") return null
            val createdAt = lines[2].toLongOrNull() ?: return null
            val name = try {
                String(Base64.decode(lines[3], Base64.NO_WRAP), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val png = File(dir, id + PNG)
            if (!png.isFile || png.length() == 0L) return null
            return SavedSignature(id, name, emptyList(), createdAt, png)
        }
        if (header != HEADER) return null
        if (lines.size < 5) return null
        val createdAt = lines[2].toLongOrNull() ?: return null
        val name = try {
            String(Base64.decode(lines[3], Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val count = lines[4].toIntOrNull() ?: return null
        if (count <= 0 || lines.size < 5 + count) return null
        val strokes = ArrayList<PageStamp.Stroke>(count)
        for (i in 0 until count) {
            val tokens = lines[5 + i].trim().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val points = ArrayList<PageStamp.Point>(tokens.size)
            for (token in tokens) {
                val xy = token.split(',')
                if (xy.size != 2) return null
                val x = xy[0].toFloatOrNull() ?: return null
                val y = xy[1].toFloatOrNull() ?: return null
                if (!x.isFinite() || !y.isFinite()) return null
                points += PageStamp.Point(x, y)
            }
            strokes += PageStamp.Stroke(points)
        }
        val aspect = lines.getOrNull(5 + count)?.removePrefix("aspect=")?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0f } ?: 3f
        return SavedSignature(id, name, strokes, createdAt, aspectRatio = aspect)
    }
}
