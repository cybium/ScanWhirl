package com.scanwhirl.app.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.charset.StandardCharsets

/** Saves page images into the user's own Downloads/ScanWhirl folder. */
object DownloadSaver {

    const val SUBDIR = "ScanWhirl"

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

    /**
     * Writes JPEG bytes to Downloads/ScanWhirl/[displayName] (or into a named
     * subfolder when [subdir] is given) and returns the final name
     * (renamed with " (n)" if one already exists).
     * Throws on missing storage permission / unavailable storage.
     */
    fun saveJpeg(context: Context, bytes: ByteArray, displayName: String, subdir: String? = null): String {
        if (Build.VERSION.SDK_INT >= 29) {
            return saveQ(context, bytes, displayName, subdir)
        }
        return savePreQ(bytes, displayName, subdir)
    }

    @androidx.annotation.RequiresApi(29)
    private fun saveQ(context: Context, bytes: ByteArray, displayName: String, subdir: String?): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = if (subdir.isNullOrBlank()) {
            Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR
        } else {
            Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR + "/" + subdir
        }
        val name = uniqueName(displayName) { candidate ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relPath/", candidate),
                null
            )?.use { it.count > 0 } ?: false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create download entry")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }

    @Suppress("DEPRECATION")
    private fun savePreQ(bytes: ByteArray, displayName: String, subdir: String? = null): String {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IllegalStateException("Storage unavailable")
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            if (subdir.isNullOrBlank()) SUBDIR else "$SUBDIR/$subdir"
        ).apply { mkdirs() }
        val name = uniqueName(displayName) { File(dir, it).exists() }
        File(dir, name).writeBytes(bytes)
        return name
    }

    /**
     * A saved export: the URI of the file that was written (for Open/Undo) and
     * the final display name.
     */
    data class SavedFile(val uri: Uri, val name: String)

    /**
     * Writes PDF bytes into the user's Documents/ScanWhirl folder, **replacing**
     * an existing file of the same name (T4.7: re-saving must not create
     * " (2)" duplicates). On Android 10+ the existing MediaStore row is
     * overwritten with `openOutputStream(uri, "wt")`; if that row cannot be
     * written (e.g. it belongs to an older install) a fresh unique-named entry
     * is created instead. Pre-Q overwrites the File when it exists.
     * Throws on missing storage permission / unavailable storage.
     */
    fun savePdfFile(context: Context, bytes: ByteArray, displayName: String): SavedFile {
        if (Build.VERSION.SDK_INT >= 29) {
            return savePdfQ(context, bytes, displayName, "application/pdf")
        }
        return savePdfPreQ(bytes, displayName, "application/pdf")
    }

    /**
     * Writes plain text into the user's Documents/ScanWhirl folder as a `.txt`
     * file, replacing an existing file of the same name. The [displayName] is
     * sanitised and forced to end in `.txt` ("Document" when blank).
     */
    fun saveText(context: Context, text: String, displayName: String): SavedFile {
        val name = sanitize(displayName).removeSuffix(".txt") + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            return savePdfQ(context, text.toByteArray(StandardCharsets.UTF_8), name, "text/plain")
        }
        return savePdfPreQ(text.toByteArray(StandardCharsets.UTF_8), name, "text/plain")
    }

    /**
     * Name-only wrapper around [savePdfFile] for callers that do not need the
     * URI (keeps the old String API, so `DownloadSaverTest` stays green).
     */
    fun savePdf(context: Context, bytes: ByteArray, displayName: String): String =
        savePdfFile(context, bytes, displayName).name

    /** Undo for a saved export (T4.7): removes the file this app just wrote. */
    fun deleteSaved(context: Context, uri: Uri) {
        try {
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
            // Best effort: an already-gone file is the desired end state anyway.
        }
    }

    /**
     * T4.8: best-effort lookup of copies of [docName] this package owns in
     * Documents/ScanWhirl — only `<name>.pdf` / `<name> (n).pdf`, never
     * anything looser, and only rows `owner_package_name` proves we wrote
     * (Android 10+; older installs return nothing rather than guessing).
     */
    fun findCopies(context: Context, docName: String): List<Uri> {
        if (Build.VERSION.SDK_INT >= 29) return findCopiesQ(context, docName)
        return emptyList()
    }

    @androidx.annotation.RequiresApi(29)
    private fun findCopiesQ(context: Context, docName: String): List<Uri> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR
        val stem = pdfNameFor(docName).removeSuffix(".pdf")
        val exact = Regex("^${Regex.escape(stem)}( \\(\\d+\\))?\\.pdf$")
        val out = mutableListOf<Uri>()
        // Best effort: no MediaStore here / no permission → no legacy matches.
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf("$relPath/", context.packageName),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    if (exact.matches(name)) {
                        out.add(ContentUris.withAppendedId(collection, c.getLong(idIdx)))
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    @androidx.annotation.RequiresApi(29)
    private fun savePdfQ(context: Context, bytes: ByteArray, displayName: String, mime: String): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/" + SUBDIR

        // T4.7: replace an existing file with the same name instead of "(2)"-ing it.
        val existingId = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$relPath/", displayName),
            null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (existingId != null) {
            val existingUri = ContentUris.withAppendedId(collection, existingId)
            try {
                resolver.openOutputStream(existingUri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not open output stream")
                return SavedFile(existingUri, displayName)
            } catch (_: Exception) {
                // Row not writable (older install / foreign owner): fall through
                // and create a fresh entry with a unique name below.
            }
        }

        val name = uniqueName(displayName) { candidate ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relPath/", candidate),
                null
            )?.use { it.count > 0 } ?: false
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create document entry")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return SavedFile(uri, name)
    }

    @Suppress("DEPRECATION")
    private fun savePdfPreQ(bytes: ByteArray, displayName: String, mime: String): SavedFile {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IllegalStateException("Storage unavailable")
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            SUBDIR
        ).apply { mkdirs() }
        // T4.7: overwrite the file when it already exists (replace, not duplicate).
        val file = File(dir, displayName)
        file.writeBytes(bytes)
        return SavedFile(Uri.fromFile(file), displayName)
    }
}
