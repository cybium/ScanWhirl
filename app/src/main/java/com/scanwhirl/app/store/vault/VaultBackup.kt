package com.scanwhirl.app.store.vault

import com.scanwhirl.app.util.Durable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The `.swvault` backup container: the Vault key wrapped by the recovery code plus every
 * already-encrypted file of every entry, plus the small Vault-owned files (the remembered PDF
 * passwords). It can be saved anywhere (Drive, a PC) because the contents are unreadable without
 * the recovery code, and it restores on a new phone with the code alone.
 *
 * Layout (streamed, never the whole backup in memory):
 *   "SWVAULT1" | int version | int recoveryLen | recovery blob
 *   | int entryCount | { UTF id | int fileCount | { UTF name | long len | bytes } }
 *   | int extraCount | { UTF name | long len | bytes }          (version 2 and later)
 *
 * Version 1 files (written before remembered passwords existed) still restore.
 */
object VaultBackup {

    const val EXTENSION = "swvault"
    const val MIME = "application/octet-stream"

    private val MAGIC = "SWVAULT1".toByteArray(Charsets.UTF_8)
    private const val VERSION = 2
    private const val MIN_VERSION = 1
    private const val MAX_ENTRIES = 10_000
    private const val MAX_FILES = 10_000
    private const val MAX_EXTRAS = 32
    private const val MAX_RECOVERY_BLOB = 4_096
    private const val MAX_NAME = 255
    private const val COPY_BUFFER = 64 * 1024

    data class Contents(
        val recoveryBlob: ByteArray,
        val entryIds: List<String>,
        /** Unencrypted-by-the-container files such as `passwords.bin`, extracted into the staging root. */
        val extras: List<File> = emptyList()
    )

    /**
     * Writes [entryDirs] (each `vault2/<id>` directory) and [extraFiles] (already encrypted with
     * the vault key) into [dest], atomically.
     */
    fun write(
        dest: File,
        recoveryBlob: ByteArray,
        entryDirs: List<File>,
        extraFiles: List<File> = emptyList()
    ) {
        if (recoveryBlob.isEmpty() || recoveryBlob.size > MAX_RECOVERY_BLOB) {
            throw IOException("The Vault key file is not usable for a backup")
        }
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            dest.parentFile?.mkdirs()
            FileOutputStream(part).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos))
                out.write(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(recoveryBlob.size)
                out.write(recoveryBlob)

                val dirs = entryDirs.filter { it.isDirectory }
                out.writeInt(dirs.size)
                dirs.forEach { dir ->
                    val files = dir.listFiles()
                        ?.filter { it.isFile && !it.name.startsWith(".") }
                        ?.sortedBy { it.name }
                        .orEmpty()
                    out.writeUTF(dir.name)
                    out.writeInt(files.size)
                    files.forEach { file ->
                        out.writeUTF(file.name)
                        out.writeLong(file.length())
                        FileInputStream(file).use { input -> copy(input, out, file.length()) }
                    }
                }

                val extras = extraFiles.filter { it.isFile }
                out.writeInt(extras.size)
                extras.forEach { file ->
                    out.writeUTF(file.name)
                    out.writeLong(file.length())
                    FileInputStream(file).use { input -> copy(input, out, file.length()) }
                }
                out.flush()
                Durable.syncStream(fos, part)
            }
            if (!part.renameTo(dest)) throw IOException("Could not write the backup file")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    /**
     * Extracts a backup into [into] (a private staging directory), never trusting names.
     * The caller verifies every entry with the unwrapped key before publishing it.
     */
    fun extract(src: File, into: File): Contents {
        into.deleteRecursively()
        if (!into.mkdirs()) throw IOException("Could not prepare the restore folder")
        val root = into.canonicalFile

        DataInputStream(BufferedInputStream(FileInputStream(src))).use { input ->
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a ScanWhirl Vault backup")
            val version = input.readInt()
            if (version !in MIN_VERSION..VERSION) throw VaultAuthException("Unsupported Vault backup")
            val recoveryLength = input.readInt()
            if (recoveryLength <= 0 || recoveryLength > MAX_RECOVERY_BLOB) {
                throw VaultAuthException("Damaged Vault backup")
            }
            val recoveryBlob = ByteArray(recoveryLength).also { input.readFully(it) }

            val entryCount = input.readInt()
            if (entryCount !in 0..MAX_ENTRIES) throw VaultAuthException("Damaged Vault backup")
            val ids = ArrayList<String>(entryCount)
            repeat(entryCount) {
                val id = input.readUTF()
                requireToken(id, "entry")
                val dir = File(root, id).apply { mkdirs() }
                val fileCount = input.readInt()
                if (fileCount !in 0..MAX_FILES) throw VaultAuthException("Damaged Vault backup")
                repeat(fileCount) {
                    val name = input.readUTF()
                    requireToken(name, "file")
                    val length = input.readLong()
                    if (length < 0) throw VaultAuthException("Damaged Vault backup")
                    val dest = File(dir, name)
                    if (!dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        throw VaultAuthException("Damaged Vault backup")
                    }
                    FileOutputStream(dest).use { out -> copy(input, out, length) }
                }
                ids += id
            }

            val extras = ArrayList<File>()
            if (version >= 2) {
                val extraCount = input.readInt()
                if (extraCount !in 0..MAX_EXTRAS) throw VaultAuthException("Damaged Vault backup")
                repeat(extraCount) {
                    val name = input.readUTF()
                    requireToken(name, "file")
                    val length = input.readLong()
                    if (length < 0) throw VaultAuthException("Damaged Vault backup")
                    val dest = File(root, name)
                    if (!dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                        throw VaultAuthException("Damaged Vault backup")
                    }
                    FileOutputStream(dest).use { out -> copy(input, out, length) }
                    extras += dest
                }
            }
            return Contents(recoveryBlob, ids, extras)
        }
    }

    private fun copy(input: java.io.InputStream, out: java.io.OutputStream, length: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, COPY_BUFFER.toLong()).toInt())
            if (n < 0) throw VaultAuthException("The Vault backup is incomplete")
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun requireToken(value: String, kind: String) {
        if (value.isEmpty() || value.length > MAX_NAME ||
            value == "." || value == ".." ||
            value.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }
        ) {
            throw VaultAuthException("Damaged Vault backup ($kind name)")
        }
    }
}
