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
 * In-memory stand-in for MediaStore (authority `media`), shared by the export
 * tests. Rows behave like real ones:
 *  - queryable by `col=? AND col=?` selections, or by a single-row URI,
 *  - `IS_PENDING` rows are hidden from collection queries (not from their
 *    own URI), like the real provider,
 *  - `_size` and `owner_package_name` columns are answered,
 *  - writable through openFile, where "w" truncates like the real provider,
 *  - deletable, except foreign-owned rows, which refuse with a `SecurityException`,
 *  - an inserted name that collides with any row (pending or invisible
 *    included) is renamed " (1)", " (2)"… like MediaProvider does.
 *
 * M06 failpoints let a test break any provider step: insert, open-for-write,
 * update (publish) and delete (cleanup).
 *
 * Register with `ShadowContentResolver.registerProviderInternal("media", …)`
 * after `attachInfo(context, ProviderInfo(authority = "media"))`. Without
 * attachInfo, the real ContentResolver stream path NPEs (see log T4.7).
 */
class FakeMediaStore : ContentProvider() {

    class Row(
        val id: Long,
        val values: ContentValues,
        val file: File,
        var writable: Boolean = true,
        var writes: Int = 0,
        /** Owner package; null = a test-made row with no owner. */
        var owner: String? = null
    ) {
        val name: String? get() = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        val pending: Boolean get() = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1
        fun bytes(): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)
    }

    val rows = java.util.Collections.synchronizedMap(LinkedHashMap<Long, Row>())
    private val lock = Any()
    private var nextId = 1L
    private val workDir: File =
        File.createTempFile("fake_media", null).let { it.delete(); it.apply { mkdirs() } }

    // --- M06 failpoints -------------------------------------------------
    /** insert() returns null (provider refused to create the row). */
    var failInsert = false
    /** openFile(…, "w*") throws. */
    var failOpenWrite = false
    /** update() throws (e.g. publishing IS_PENDING=0 fails). */
    var failUpdate = false
    /** update() reports 0 rows changed. */
    var updateReturnsZero = false
    /** delete() throws (cleanup failure). */
    var failDelete = false
    /** Mimic MediaProvider's " (n)" rename when an inserted name is taken. */
    var renameOnCollision = true

    fun close() {
        workDir.deleteRecursively()
    }

    private fun relPathOf(row: Row): String? =
        row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trimEnd('/')

    /** Published rows in Documents/ScanWhirl (what a person can see in Files). */
    fun scanwhirlPdfs(): List<Row> = synchronized(lock) {
        rows.values.filter {
            relPathOf(it) == "${Environment.DIRECTORY_DOCUMENTS}/ScanWhirl" && !it.pending
        }
    }

    /** Hidden in-flight / abandoned rows anywhere. */
    fun pendingRows(): List<Row> = synchronized(lock) { rows.values.filter { it.pending } }

    fun totalWrites(): Int = synchronized(lock) { rows.values.sumOf { it.writes } }

    fun rowNamed(name: String): Row = synchronized(lock) {
        rows.values.single { it.name == name && !it.pending }
    }

    /** Seeds a row directly (e.g. another app's file, or a pending orphan from a crash). */
    fun addRow(
        name: String,
        bytes: ByteArray,
        owner: String?,
        pending: Boolean = false,
        relPath: String = "${Environment.DIRECTORY_DOCUMENTS}/ScanWhirl"
    ): Row = synchronized(lock) {
        val id = nextId++
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns._ID, id)
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            owner?.let { put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, it) }
        }
        val row = Row(id, v, File(workDir, "row_$id.bin"), owner = owner)
        row.file.writeBytes(bytes)
        rows[id] = row
        row
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = "application/pdf"

    private fun idOf(uri: Uri): Long? =
        uri.lastPathSegment?.toLongOrNull()

    private fun columnValue(row: Row, col: String): Any? = when (col) {
        MediaStore.MediaColumns._ID -> row.id
        MediaStore.MediaColumns.SIZE -> if (row.file.exists()) row.file.length() else 0L
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> row.owner
        MediaStore.MediaColumns.IS_PENDING -> if (row.pending) 1 else 0
        else -> row.values.getAsString(col)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = synchronized(lock) {
        val proj: Array<String> = projection ?: arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val cursor = MatrixCursor(proj)
        val single = idOf(uri)
        val matches = if (single != null) {
            listOfNotNull(rows[single])
        } else {
            val cols = selection
                ?.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE))
                ?.map { it.substringBefore('=').trim() }
                ?: emptyList()
            val args = selectionArgs ?: emptyArray()
            rows.values.filter { row ->
                !row.pending && cols.withIndex().all { (i, col) ->
                    val want = args.getOrNull(i) ?: return@all false
                    val have = columnValue(row, col)?.toString()
                    if (col == MediaStore.MediaColumns.RELATIVE_PATH) {
                        have?.trimEnd('/') == want.trimEnd('/')
                    } else {
                        have == want
                    }
                }
            }
        }
        for (row in matches) {
            cursor.addRow(proj.map { col -> columnValue(row, col) })
        }
        cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = synchronized(lock) {
        if (failInsert) return@synchronized null
        val v = ContentValues(values ?: ContentValues())
        val id = nextId++
        v.put(MediaStore.MediaColumns._ID, id)
        // MediaStore stamps the owning package on insert.
        if (!v.containsKey(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)) {
            v.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, context?.packageName)
        }
        if (renameOnCollision) {
            val rel = v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trimEnd('/')
            val wanted = v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            if (wanted != null) {
                val taken = { n: String ->
                    rows.values.any { relPathOf(it) == rel && it.name.equals(n, ignoreCase = true) }
                }
                if (taken(wanted)) {
                    val dot = wanted.lastIndexOf('.')
                    val stem = if (dot > 0) wanted.substring(0, dot) else wanted
                    val ext = if (dot > 0) wanted.substring(dot) else ""
                    var n = 1
                    while (taken("$stem ($n)$ext")) n++
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem ($n)$ext")
                }
            }
        }
        rows[id] = Row(
            id, v, File(workDir, "row_$id.bin"),
            owner = v.getAsString(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        )
        ContentUris.withAppendedId(uri, id)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = synchronized(lock) {
        if (failUpdate) throw IllegalStateException("update refused (failpoint)")
        if (updateReturnsZero) return@synchronized 0
        val row = rows[ContentUris.parseId(uri)] ?: return@synchronized 0
        values?.let { row.values.putAll(it) }
        1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = synchronized(lock) {
        if (failDelete) throw IllegalStateException("delete refused (failpoint)")
        val row = rows[ContentUris.parseId(uri)] ?: return@synchronized 0
        // Real MediaStore refuses to delete rows owned by another package.
        val own = context?.packageName
        if (row.owner != null && own != null && row.owner != own) {
            throw SecurityException("Row ${row.id} is owned by ${row.owner}")
        }
        rows.remove(row.id)
        row.file.delete()
        1
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = synchronized(lock) {
        val row = rows[ContentUris.parseId(uri)]
            ?: throw FileNotFoundException("no row for $uri")
        if (mode.contains("w")) {
            if (failOpenWrite) throw FileNotFoundException("open for write refused (failpoint)")
            if (!row.writable) throw FileNotFoundException("row not writable: $uri")
            // "w" / "wt": truncate, then hand out a writable descriptor.
            row.file.writeBytes(ByteArray(0))
            row.writes++
            return@synchronized ParcelFileDescriptor.open(
                row.file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            )
        }
        if (!row.file.exists()) row.file.writeBytes(ByteArray(0))
        ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
