package com.scanwhirl.app.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * File-durability helpers for the transactional save (WP-05).
 *
 * Two different guarantees, deliberately kept apart:
 *
 *  - **File contents** ([syncStream], [writeFile], [atomicWrite]): `fsync` of the
 *    file descriptor. A failure is REQUIRED to fail the caller — it is reported
 *    as [SyncException] and never swallowed.
 *  - **Directory entries** ([syncDir]): best effort only. Android has no public
 *    API to fsync a directory; we try `android.system.Os` and report whether it
 *    worked. A `false` result must never fail a save. Process death is covered
 *    by the kernel regardless; only power loss can lose an unsynced directory
 *    entry, and the transaction design tolerates that (the document stays
 *    invisible and the save is replayed).
 */
object Durable {

    /** File contents could not be forced to storage. */
    class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Fault-injection seam (tests only): runs just before every file `fsync`. May throw. */
    @Volatile internal var beforeFileSync: (File) -> Unit = {}

    /** Directory-sync strategy; replaceable in tests. Returns true when the sync really happened. */
    @Volatile internal var dirSyncer: (File) -> Boolean = ::osSyncDirectory

    /** Fault-injection seam (tests only): the file rename used by page replacement. */
    @Volatile internal var renameSeam: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

    internal fun resetForTests() {
        beforeFileSync = {}
        dirSyncer = ::osSyncDirectory
        renameSeam = { from, to -> from.renameTo(to) }
    }

    /** Flushes and `fsync`s [out]; throws [SyncException] when the data cannot be forced to disk. */
    fun syncStream(out: FileOutputStream, file: File) {
        try {
            out.flush()
            beforeFileSync(file)
            out.fd.sync()
        } catch (e: IOException) {
            throw SyncException("Could not sync ${file.name} to storage: ${e.message}", e)
        }
    }

    /** `fsync`s an already written file. */
    fun syncFile(file: File) {
        try {
            beforeFileSync(file)
            FileInputStream(file).use { it.fd.sync() }
        } catch (e: IOException) {
            throw SyncException("Could not sync ${file.name} to storage: ${e.message}", e)
        }
    }

    /** Creates/truncates [file] (which must be one this caller owns), writes and `fsync`s it. */
    fun writeFile(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            syncStream(out, file)
        }
    }

    /**
     * Replaces [target] atomically: `<target>.tmp` is written and fsynced, then
     * renamed over the target (POSIX file rename is atomic). On any failure the
     * tmp file is removed and the existing target is left untouched.
     */
    fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            writeFile(tmp, bytes)
            if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun atomicWriteText(target: File, text: String) = atomicWrite(target, text.toByteArray(Charsets.UTF_8))

    /**
     * Copies [src] to [dst] (a file the caller owns, never a published one) and `fsync`s the copy.
     * Fails when the lengths differ afterwards.
     */
    fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { i ->
            FileOutputStream(dst).use { o ->
                i.copyTo(o, 64 * 1024)
                syncStream(o, dst)
            }
        }
        if (src.length() != dst.length()) throw IOException("Copy of ${src.name} is incomplete")
    }

    /** Best-effort directory fsync. Returns whether it succeeded; never throws. */
    fun syncDir(dir: File): Boolean = try {
        dirSyncer(dir)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun osSyncDirectory(dir: File): Boolean {
        val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
        try {
            android.system.Os.fsync(fd)
        } finally {
            android.system.Os.close(fd)
        }
        return true
    }
}
