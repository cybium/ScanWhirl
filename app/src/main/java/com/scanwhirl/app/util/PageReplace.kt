package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * Non-destructive replacement of page images (M08 P0).
 *
 * Every Rotate / Crop / Filter / corner correction writes its result to a hidden
 * staging file next to the page, forces it to storage and decodes it back before
 * anything is swapped. The swap is a POSIX `rename(2)`, which replaces the target
 * in one step or leaves it exactly as it was. Nothing here ever deletes a target:
 *
 *  - one file: the rename either lands or the old page stays byte-identical;
 *  - several files that must move together (a page and its kept `.orig`): each
 *    target is first copied to a fsynced backup; if a later rename fails, earlier
 *    swaps are renamed back from those backups. Backups are removed only after a
 *    clean commit or a clean rollback — when even the rollback fails they stay on
 *    disk (`.swbak`), so a good copy always exists.
 *
 * Crash semantics (stated, not overstated): process death at any point leaves each
 * target either old or new, never truncated, plus possibly a stray hidden
 * `.swtmp`/`.swbak` sidecar that no page listing reads. A power cut can additionally
 * lose an unsynced directory entry (Android offers no reliable directory fsync), in
 * which case the old page is what survives.
 */
object PageReplace {

    /** A validated replacement waiting to be swapped in. [discard] drops it. */
    class Staged internal constructor(val target: File, internal val tmp: File) {
        fun discard() {
            tmp.delete()
        }
    }

    private val random = SecureRandom()

    private fun nonce(): String = java.lang.Long.toHexString(random.nextLong() and Long.MAX_VALUE)

    private fun sidecar(target: File, kind: String): File =
        File(target.absoluteFile.parentFile ?: throw IOException("No folder for ${target.name}"),
            ".${target.name}.${nonce()}.$kind")

    /** Encodes [bitmap] as JPEG next to [target], fsyncs and verifies it. [target] is not touched. */
    fun stageJpeg(target: File, bitmap: Bitmap, quality: Int = 95): Staged {
        require(!bitmap.isRecycled) { "The edited page is no longer in memory. Try again." }
        val tmp = sidecar(target, "swtmp")
        try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
                    throw IOException("Could not encode ${target.name}. Nothing was changed.")
                }
                Durable.syncStream(out, tmp)
            }
            verify(tmp, bitmap.width, bitmap.height)
            return Staged(target, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Stages an exact copy of [source] as the next content of [target]. [source] must decode. */
    fun stageCopy(target: File, source: File): Staged {
        val size = bounds(source) ?: throw IOException("${source.name} can't be read, so nothing was changed.")
        val tmp = sidecar(target, "swtmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    Durable.syncStream(out, tmp)
                }
            }
            if (tmp.length() != source.length()) throw IOException("Copy of ${source.name} is incomplete.")
            verify(tmp, size.first, size.second)
            return Staged(target, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Stages already encoded JPEG [bytes] (e.g. a corrected capture) as [target]'s content. */
    fun stageBytes(target: File, bytes: ByteArray): Staged {
        val tmp = sidecar(target, "swtmp")
        try {
            Durable.writeFile(tmp, bytes)
            val size = bounds(tmp) ?: throw IOException("The new ${target.name} does not decode.")
            verify(tmp, size.first, size.second)
            return Staged(target, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Runs every stager; if one fails, the ones already staged are discarded. */
    fun stageAll(stagers: List<() -> Staged>): List<Staged> {
        val staged = ArrayList<Staged>(stagers.size)
        try {
            stagers.forEach { staged += it() }
        } catch (t: Throwable) {
            staged.forEach { it.discard() }
            throw t
        }
        return staged
    }

    fun commit(vararg staged: Staged) = commit(staged.toList())

    /** Swaps every staged file in, all or nothing (see class notes). Never deletes a target. */
    fun commit(staged: List<Staged>) {
        if (staged.isEmpty()) return
        val backups = LinkedHashMap<File, File>()
        val swapped = ArrayList<Staged>(staged.size)
        try {
            if (staged.size > 1) {
                for (s in staged) {
                    if (!s.target.exists()) continue
                    val bak = sidecar(s.target, "swbak")
                    FileInputStream(s.target).use { input ->
                        FileOutputStream(bak).use { out ->
                            input.copyTo(out)
                            Durable.syncStream(out, bak)
                        }
                    }
                    backups[s.target] = bak
                }
            }
            for (s in staged) {
                if (!Durable.renameSeam(s.tmp, s.target)) {
                    throw IOException("Could not replace ${s.target.name}. The page was left unchanged.")
                }
                swapped += s
            }
            staged.mapNotNull { it.target.absoluteFile.parentFile }.toSet().forEach { Durable.syncDir(it) }
        } catch (t: Throwable) {
            var rollbackOk = true
            for (s in swapped.asReversed()) {
                val bak = backups[s.target]
                if (bak == null || !Durable.renameSeam(bak, s.target)) rollbackOk = false
            }
            staged.forEach { it.discard() }
            if (rollbackOk) backups.values.forEach { it.delete() }
            throw if (rollbackOk) t else IOException(
                "The edit was only partly applied; a backup of the page was kept next to it.", t
            )
        }
        backups.values.forEach { it.delete() }
    }

    /** Width/height from the header, or null when [file] is not a decodable image. */
    fun bounds(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() == 0L) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun verify(tmp: File, width: Int, height: Int) {
        val got = bounds(tmp)
        if (got != width to height) {
            throw IOException("The new page did not read back correctly (got $got). Nothing was changed.")
        }
    }
}
