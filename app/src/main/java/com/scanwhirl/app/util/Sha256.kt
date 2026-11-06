package com.scanwhirl.app.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers for the password-lock index. Never hashes passwords here. */
object Sha256 {

    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun of(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
        return hex(md.digest())
    }

    fun of(file: File): String = file.inputStream().use { of(it) }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
