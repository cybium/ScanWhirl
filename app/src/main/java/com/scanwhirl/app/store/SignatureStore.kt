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
        val createdAt: Long
    )

    private val dir: File = File(context.filesDir, "signatures")

    companion object {
        const val MAX_SIGNATURES = 5
        private const val HEADER = "#scanwhirl-signature-v1"
        private const val EXT = ".sig"
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
    fun save(name: String, strokes: List<PageStamp.Stroke>): SavedSignature {
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
            createdAt = System.currentTimeMillis()
        )
        dir.mkdirs()
        Durable.atomicWriteText(File(dir, id + EXT), encode(saved))
        return saved
    }

    /** Deletes one signature; unknown or unsafe ids are ignored. */
    fun delete(id: String) {
        if (!SAFE_ID.matches(id)) return
        File(dir, id + EXT).delete()
    }

    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().replace("-", "").take(12)
            if (!File(dir, id + EXT).exists()) return id
        }
    }

    private fun encode(saved: SavedSignature): String = buildString {
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
    }

    private fun parse(id: String, text: String): SavedSignature? {
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return null
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
        return SavedSignature(id, name, strokes, createdAt)
    }
}
