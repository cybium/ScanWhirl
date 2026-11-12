package com.scanwhirl.app.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Saves exports (PDF, text, resized images) into the user's shared folders.
 *
 * **M06 export transaction — the rules every save follows:**
 *
 *  1. An existing file is never opened for writing. A save streams into a *new*
 *     hidden staging entry (an `IS_PENDING` MediaStore row on Android 10+, a
 *     randomly named `.scanwhirl-*.part` file before that), forces it to storage,
 *     closes it, and reads it back to check the byte count and SHA-256 against
 *     the source. Only then is the entry published under its final name. Any
 *     failure removes the staging entry, and it was never visible anyway.
 *  2. Saving bytes that are already there (a same-name family file this app
 *     wrote with the identical checksum) writes nothing and reports
 *     [Outcome.ALREADY_SAVED]. Re-saving an unchanged document makes no duplicate.
 *  3. Saving *different* bytes when the name is taken creates a genuinely new
 *     copy, `Name (2).pdf`. The earlier file is kept, and [SavedFile.keptEarlier]
 *     lets the UI say so. MediaStore has no atomic replace, so this code never
 *     claims one.
 *  4. Undo ([undoSave]) can only remove a file that this save created, and only
 *     while it still holds exactly the bytes that were written.
 *
 * Limits, stated rather than hidden: the new copy is fsynced before it is
 * published, but neither MediaStore nor a directory rename is promised to be
 * power-loss atomic. A power cut mid-save can leave a hidden staging entry, but
 * never a damaged earlier export, because earlier exports are not written to.
 * Pending MediaStore rows expire on their own (Android 11+). Stale pre-Q `.part`
 * files are swept on the next save.
 */
object DownloadSaver {

    const val SUBDIR = "ScanWhirl"

    private const val CHUNK = 64 * 1024

    /** Pre-Q staging files: hidden, uniquely named per save (never a fixed `.part` name). */
    internal const val TEMP_PREFIX = ".scanwhirl-"
    internal const val TEMP_SUFFIX = ".part"

    /** A pre-Q staging file older than this belongs to a save that died; swept on the next save. */
    internal const val STALE_TEMP_AGE_MS = 60L * 60 * 1000

    /** One export at a time per process: concurrent saves cannot race for a name or both write. */
    private val EXPORT_LOCK = Any()

    /** Where in a save a fault was injected (tests only). */
    enum class Stage { STAGED, WRITING, CLOSING, PUBLISHING, DISCARDING }

    /**
     * Fault-injection seam (tests only), called at each [Stage]. It may throw.
     * `WRITING` runs after every chunk, so the first throw happens mid-file.
     */
    @Volatile internal var faultAt: (Stage) -> Unit = {}

    internal fun resetForTests() {
        faultAt = {}
    }

    /** A save could not complete. Earlier exports are untouched. */
    class ExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

    enum class Outcome {
        /** A new file was created at [SavedFile.uri]. */
        CREATED,

        /** An identical copy already existed at [SavedFile.uri]; nothing was written. */
        ALREADY_SAVED
    }

    /** Content identity of a saved file: byte length + lowercase hex SHA-256. */
    data class Fingerprint(val size: Long, val sha256: String) {
        fun encode(): String = "$size:$sha256"

        companion object {
            fun decode(raw: String): Fingerprint? {
                val colon = raw.indexOf(':')
                if (colon <= 0) return null
                val size = raw.substring(0, colon).toLongOrNull() ?: return null
                val sha = raw.substring(colon + 1)
                if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
                return Fingerprint(size, sha)
            }
        }
    }

    /** Result of a save: where the bytes live, the final display name and what happened. */
    data class SavedFile(
        val uri: Uri,
        val name: String,
        val outcome: Outcome = Outcome.CREATED,
        /** The name the caller asked for (before any " (n)"). */
        val requestedName: String = name,
        val fingerprint: Fingerprint? = null
    ) {
        /** True only when this save created [uri]. That is the only case Undo may remove. */
        val created: Boolean get() = outcome == Outcome.CREATED

        /** A different file already used the requested name. It was kept, and this copy got a new name. */
        val keptEarlier: Boolean get() = created && name != requestedName
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** "Document 1 - page 2.jpg" (or "Document 1.jpg" for a single page). */
    fun fileNameFor(docTitle: String, pageNumber: Int, totalPages: Int): String {
        val base = sanitize(docTitle)
        return if (totalPages <= 1) "$base.jpg" else "$base - page $pageNumber.jpg"
    }

    /** Appends " (2)", " (3)", … until [candidate] is free. */
    fun uniqueName(candidate: String, taken: (String) -> Boolean): String {
        if (!taken(candidate)) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var n = 2
        while (taken("$stem ($n)$ext")) n++
        return "$stem ($n)$ext"
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('_')
        return cleaned.ifBlank { "Document" }.take(60)
    }

    /** "Document 1.pdf" */
    fun pdfNameFor(docTitle: String): String = sanitize(docTitle) + ".pdf"

    /** `Name.ext` and `Name (n).ext`, the names a save of [requested] can end up with. */
    internal fun familyRegex(requested: String): Regex {
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        return Regex(
            "^${Regex.escape(stem)}( \\(\\d+\\))?${Regex.escape(ext)}$",
            RegexOption.IGNORE_CASE
        )
    }

    // ------------------------------------------------------------------
    // Public save API
    // ------------------------------------------------------------------

    /**
     * Streams [source] (only ever opened for reading) into Documents/ScanWhirl
     * using the transaction above, in bounded memory.
     */
    fun savePdfFile(context: Context, source: File, displayName: String): SavedFile =
        export(context, { FileInputStream(source) }, displayName, documents("application/pdf"))

    /** Same as the [File] overload, for PDFs that already live in memory. */
    fun savePdfFile(context: Context, bytes: ByteArray, displayName: String): SavedFile =
        export(context, { bytes.inputStream() }, displayName, documents("application/pdf"))

    /** Name-only wrapper around [savePdfFile]. */
    fun savePdf(context: Context, bytes: ByteArray, displayName: String): String =
        savePdfFile(context, bytes, displayName).name

    /**
     * Writes plain text into Documents/ScanWhirl as a `.txt` file with the same
     * transaction as PDFs. The [displayName] is sanitised and forced to end in
     * `.txt` ("Document" when blank).
     */
    fun saveText(context: Context, text: String, displayName: String): SavedFile {
        val name = sanitize(displayName).removeSuffix(".txt") + ".txt"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return export(context, { bytes.inputStream() }, name, documents("text/plain"))
    }

    /**
     * Writes a JPEG into Downloads/ScanWhirl/[displayName] (or a named
     * [subdir]) as a new file, renamed " (n)" if one exists, and returns the
     * final name. Each image save is a deliberate new file, so there is no dedupe.
     */
    fun saveJpeg(context: Context, bytes: ByteArray, displayName: String, subdir: String? = null): String =
        export(context, { bytes.inputStream() }, displayName, downloads(subdir)).name

    /** [saveJpeg] streamed from a file instead of a byte array. */
    fun saveJpeg(context: Context, source: File, displayName: String, subdir: String? = null): String =
        export(context, { FileInputStream(source) }, displayName, downloads(subdir)).name

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    enum class UndoResult {
        /** The copy this save created was removed. */
        REMOVED,

        /** Nothing to undo: the save created nothing (e.g. it was already saved). */
        NOTHING_CREATED,

        /** Kept: the file is no longer exactly what this save wrote, or its identity is unknown. */
        KEPT_CHANGED,

        /** The removal itself failed (e.g. the provider refused). The file stays. */
        FAILED
    }

    /**
     * Undo of a save. It removes [saved] only when this save created it and the
     * file still holds exactly the bytes that were written. It never removes an
     * earlier export, and an already-gone file counts as removed.
     */
    fun undoSave(context: Context, saved: SavedFile): UndoResult = synchronized(EXPORT_LOCK) {
        if (!saved.created) return UndoResult.NOTHING_CREATED
        val expected = saved.fingerprint ?: return UndoResult.KEPT_CHANGED
        val current = try {
            fingerprintOf(context, saved.uri)
        } catch (_: Exception) {
            null
        }
        if (current == null) {
            return if (exists(context, saved.uri)) UndoResult.FAILED else UndoResult.REMOVED
        }
        if (current != expected) return UndoResult.KEPT_CHANGED
        return if (deleteUri(context, saved.uri)) UndoResult.REMOVED else UndoResult.FAILED
    }

    /** Current fingerprint of a saved file, or null when it cannot be read (gone). */
    fun fingerprintOf(context: Context, uri: Uri): Fingerprint? {
        val input: InputStream = if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (!f.isFile) return null
            FileInputStream(f)
        } else {
            context.contentResolver.openInputStream(uri) ?: return null
        }
        return input.use { fingerprint(it) }
    }

    /** Whether [uri] still exists. An unknown answer counts as "still there". */
    internal fun exists(context: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).exists() } ?: false
        } else {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } ?: false
        }
    } catch (_: Exception) {
        true // unknown: assume it is still there, never claim removal
    }

    /** True when the file is gone afterwards (deleted now, or already absent). */
    internal fun deleteUri(context: Context, uri: Uri): Boolean = try {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            f != null && (!f.exists() || f.delete())
        } else {
            context.contentResolver.delete(uri, null, null)
            !exists(context, uri)
        }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // Ownership lookup (delete-with-document, T4.8)
    // ------------------------------------------------------------------

    /**
     * T4.8: best-effort lookup of copies of [docName] this package owns in
     * Documents/ScanWhirl: only `<name>.pdf` / `<name> (n).pdf`, never
     * anything looser, and only rows `owner_package_name` proves we wrote
     * (Android 10+; older installs return nothing rather than guessing).
     */
    fun findCopies(context: Context, docName: String): List<Uri> {
        if (Build.VERSION.SDK_INT >= 29) return findCopiesQ(context, docName)
        return emptyList()
    }

    @androidx.annotation.RequiresApi(29)
    private fun findCopiesQ(context: Context, docName: String): List<Uri> {
        val collection = MediaStore.Files.getContentUri("external")
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR
        val exact = familyRegex(pdfNameFor(docName))
        return try {
            listRows(context.contentResolver, collection, relPath)
                .filter { it.owner == context.packageName && exact.matches(it.name) }
                .map { it.uri }
        } catch (_: Exception) {
            emptyList() // no MediaStore here / no permission → no legacy matches
        }
    }

    // ------------------------------------------------------------------
    // The transaction
    // ------------------------------------------------------------------

    private class Target(
        /** MediaStore collection (Android 10+). */
        val collection: () -> Uri,
        /** MediaStore RELATIVE_PATH without a trailing slash. */
        val relPath: String,
        /** Public directory before Android 10. */
        val legacyDir: () -> File,
        val mime: String,
        /** Reuse an identical existing copy instead of writing a duplicate. */
        val dedupe: Boolean
    )

    private fun documents(mime: String) = Target(
        collection = { MediaStore.Files.getContentUri("external") },
        relPath = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR,
        legacyDir = { publicDir(Environment.DIRECTORY_DOCUMENTS, SUBDIR) },
        mime = mime,
        dedupe = true
    )

    private fun downloads(subdir: String?): Target {
        val sub = if (subdir.isNullOrBlank()) SUBDIR else "$SUBDIR/$subdir"
        return Target(
            collection = { downloadsCollection() },
            relPath = Environment.DIRECTORY_DOWNLOADS + "/" + sub,
            legacyDir = { publicDir(Environment.DIRECTORY_DOWNLOADS, sub) },
            mime = "image/jpeg",
            dedupe = false
        )
    }

    @androidx.annotation.RequiresApi(29)
    private fun downloadsCollection(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    private fun publicDir(type: String, sub: String): File =
        File(Environment.getExternalStoragePublicDirectory(type), sub)

    private fun export(
        context: Context,
        source: () -> InputStream,
        requested: String,
        target: Target
    ): SavedFile = synchronized(EXPORT_LOCK) {
        // Read the source once to learn what a complete copy must look like.
        val expected = source().use { fingerprint(it) }
        if (Build.VERSION.SDK_INT >= 29) {
            exportQ(context, source, requested, target, expected)
        } else {
            exportLegacy(context, source, requested, target, expected)
        }
    }

    private class Row(val uri: Uri, val name: String, val size: Long?, val owner: String?)

    /** Published (visible) rows in [relPath]. */
    @androidx.annotation.RequiresApi(29)
    private fun listRows(resolver: ContentResolver, collection: Uri, relPath: String): List<Row> {
        val out = mutableListOf<Row>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("$relPath/"),
            null
        )?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val ownerIdx = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                val owner = if (ownerIdx >= 0) c.getString(ownerIdx) else null
                out.add(Row(ContentUris.withAppendedId(collection, c.getLong(idIdx)), name, size, owner))
            }
        }
        return out
    }

    @androidx.annotation.RequiresApi(29)
    private fun exportQ(
        context: Context,
        source: () -> InputStream,
        requested: String,
        target: Target,
        expected: Fingerprint
    ): SavedFile {
        val resolver = context.contentResolver
        val collection = target.collection()
        val rows = listRows(resolver, collection, target.relPath)

        if (target.dedupe) {
            val family = familyRegex(requested)
            for (row in rows) {
                if (row.owner != context.packageName || !family.matches(row.name)) continue
                if (row.size != null && row.size != expected.size) continue
                val current = try {
                    resolver.openInputStream(row.uri)?.use { fingerprint(it) }
                } catch (_: Exception) {
                    null
                }
                if (current == expected) {
                    return SavedFile(row.uri, row.name, Outcome.ALREADY_SAVED, requested, expected)
                }
            }
        }

        val name = uniqueName(requested) { candidate -> rows.any { it.name.equals(candidate, ignoreCase = true) } }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw ExportException("Could not create $name")
        try {
            faultAt(Stage.STAGED)
            val pfd = resolver.openFileDescriptor(uri, "w")
                ?: throw ExportException("Could not open $name for writing")
            val written = writeAll(ParcelFileDescriptor.AutoCloseOutputStream(pfd), source)
            verify(name, expected, written) { resolver.openInputStream(uri) }
            faultAt(Stage.PUBLISHING)
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, publish, null, null) != 1) {
                throw ExportException("Could not finish saving $name")
            }
        } catch (e: Throwable) {
            discard(e) { resolver.delete(uri, null, null) }
            throw e
        }
        // The provider may adjust a colliding name (another app's invisible file); report the real one.
        val finalName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: name
        return SavedFile(uri, finalName, Outcome.CREATED, requested, expected)
    }

    private fun exportLegacy(
        context: Context,
        source: () -> InputStream,
        requested: String,
        target: Target,
        expected: Fingerprint
    ): SavedFile {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw ExportException("Storage unavailable")
        }
        val dir = target.legacyDir()
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw ExportException("Could not create ${dir.name}")
        }
        sweepStaleTemps(dir)

        if (target.dedupe) {
            val family = familyRegex(requested)
            dir.listFiles()
                ?.filter { it.isFile && family.matches(it.name) && it.length() == expected.size }
                ?.forEach { file ->
                    val current = try {
                        FileInputStream(file).use { fingerprint(it) }
                    } catch (_: IOException) {
                        null
                    }
                    if (current == expected) {
                        return SavedFile(Uri.fromFile(file), file.name, Outcome.ALREADY_SAVED, requested, expected)
                    }
                }
        }

        // Unique per save: two saves can never share a staging file.
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, dir)
        val dest: File = try {
            faultAt(Stage.STAGED)
            val written = writeAll(FileOutputStream(temp), source)
            verify(requested, expected, written) { FileInputStream(temp) }
            faultAt(Stage.PUBLISHING)
            // No-clobber publish: the name is chosen under EXPORT_LOCK and checked
            // again just before the rename, so this app never renames over a file.
            // (Another app creating that exact name in the same instant is the one
            // window java.io cannot close before API 26.)
            val name = uniqueName(requested) { File(dir, it).exists() }
            val candidate = File(dir, name)
            if (candidate.exists() || !temp.renameTo(candidate)) {
                throw ExportException("Could not finish saving $name")
            }
            candidate
        } catch (e: Throwable) {
            discard(e) { if (temp.exists() && !temp.delete()) throw IOException("Could not remove ${temp.name}") }
            throw e
        }
        Durable.syncDir(dir)
        try {
            MediaScannerConnection.scanFile(context, arrayOf(dest.path), arrayOf(target.mime), null)
        } catch (_: Exception) {
            // Indexing for USB/file browsers is best effort; the file itself is complete.
        }
        return SavedFile(Uri.fromFile(dest), dest.name, Outcome.CREATED, requested, expected)
    }

    /** Removes pre-Q staging files left by a save that died (crash, power loss). */
    private fun sweepStaleTemps(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(TEMP_PREFIX) && f.name.endsWith(TEMP_SUFFIX) &&
                f.lastModified() < cutoff
            ) {
                f.delete()
            }
        }
    }

    /**
     * Copies [source] into [out] in bounded chunks, then flushes, fsyncs and closes.
     * Any failure, including in flush, sync or close, is thrown. The stream is
     * always closed.
     */
    private fun writeAll(out: FileOutputStream, source: () -> InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var failure: Throwable? = null
        try {
            source().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    total += n
                    faultAt(Stage.WRITING)
                }
            }
            out.flush()
            out.fd.sync()
            faultAt(Stage.CLOSING)
        } catch (e: Throwable) {
            failure = e
        }
        try {
            out.close()
        } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        failure?.let { throw it }
        return Fingerprint(total, hex(digest.digest()))
    }

    /** The staged copy must match the source exactly, both as written and as read back. */
    private fun verify(name: String, expected: Fingerprint, written: Fingerprint, reopen: () -> InputStream?) {
        if (written != expected) {
            throw ExportException("The document changed while $name was being saved; try again")
        }
        val readBack = reopen()?.use { fingerprint(it) }
            ?: throw ExportException("Could not re-open $name to check it")
        if (readBack != expected) {
            throw ExportException("$name could not be verified after writing")
        }
    }

    /** Best-effort cleanup that never masks the original failure. */
    private inline fun discard(cause: Throwable, remove: () -> Unit) {
        try {
            faultAt(Stage.DISCARDING)
            remove()
        } catch (e: Throwable) {
            cause.addSuppressed(e)
        }
    }

    private fun fingerprint(input: InputStream): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            total += n
        }
        return Fingerprint(total, hex(digest.digest()))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}
