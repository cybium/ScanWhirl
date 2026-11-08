package com.scanwhirl.app.util

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/**
 * In-memory stand-in for MediaStore (authority `media`), shared by
 * [ExportHygieneTest] and [DeleteExportsTest]: rows under
 * `Documents/ScanWhirl` behave like real ones — queryable by
 * relative_path + display_name (or owner_package_name), writable through
 * openFile, and deletable. Foreign-owned rows refuse deletion with a
 * `SecurityException`, like the real provider.
 *
 * Register with `ShadowContentResolver.registerProviderInternal("media", …)`
 * after `attachInfo(context, ProviderInfo(authority = "media"))` — without
 * attachInfo the real ContentResolver stream path NPEs (see log T4.7).
 */
class FakeMediaStore : ContentProvider() {

    class Row(
        val id: Long,
        val values: ContentValues,
        val file: File,
        var writable: Boolean = true,
        var writes: Int = 0,
        /** Owner package; null = this app's row (or a test-made row). */
        var owner: String? = null
    )

    val rows = LinkedHashMap<Long, Row>()
    private var nextId = 1L
    private val workDir: File =
        File.createTempFile("fake_media", null).let { it.delete(); it.apply { mkdirs() } }

    fun close() {
        workDir.deleteRecursively()
    }

    /** Rows that live in Documents/ScanWhirl (the folder T4.7/T4.8 guard). */
    fun scanwhirlPdfs(): List<Row> = rows.values.filter {
        it.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trimEnd('/') ==
            "${Environment.DIRECTORY_DOCUMENTS}/ScanWhirl"
    }

    fun totalWrites(): Int = rows.values.sumOf { it.writes }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val proj: Array<String> = projection ?: arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val args = selectionArgs ?: emptyArray()
        val cursor = MatrixCursor(proj)
        val matches = rows.values.filter { row ->
            when {
                // T4.8 legacy copy lookup: relative_path + owner package.
                selection != null &&
                    selection.contains("owner_package_name") &&
                    args.size >= 2 ->
                    row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
                        ?.trimEnd('/') == args[0].trimEnd('/') &&
                        row.owner == args[1]

                // T4.7 replace-not-duplicate lookup: relative_path + name.
                selection != null &&
                    selection.contains("relative_path") &&
                    args.size >= 2 ->
                    row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
                        ?.trimEnd('/') == args[0].trimEnd('/') &&
                        row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ==
                        args[1]

                else -> true
            }
        }
        for (row in matches) {
            cursor.addRow(
                proj.map { col ->
                    if (col == MediaStore.MediaColumns._ID) row.id
                    else row.values.getAsString(col)
                }
            )
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val v = ContentValues(values ?: ContentValues())
        val id = nextId++
        v.put(MediaStore.MediaColumns._ID, id)
        // MediaStore stamps the owning package on insert.
        if (!v.containsKey(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) {
            v.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, context?.packageName)
        }
        rows[id] = Row(
            id, v, File(workDir, "row_$id.pdf"),
            owner = v.getAsString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        )
        return ContentUris.withAppendedId(uri, id)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        values?.let { row.values.putAll(it) }
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        // Real MediaStore refuses to delete rows owned by another package.
        val own = context?.packageName
        if (row.owner != null && own != null && row.owner != own) {
            throw SecurityException("Row ${row.id} is owned by ${row.owner}")
        }
        rows.remove(row.id)
        row.file.delete()
        return 1
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = rows[ContentUris.parseId(uri)]
            ?: throw FileNotFoundException("no row for $uri")
        if (mode.contains("w")) {
            if (!row.writable) throw FileNotFoundException("row not writable: $uri")
            // "wt": truncate, then hand out a writable descriptor.
            row.file.writeBytes(ByteArray(0))
            row.writes++
            return ParcelFileDescriptor.open(
                row.file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
        }
        if (!row.file.exists()) row.file.writeBytes(ByteArray(0))
        return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
