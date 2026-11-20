package com.scanwhirl.app.store.vault

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Frozen pre-S6B writer. Only fictional bytes; never used by production. */
object StreamIntegrityFixture {
    fun legacy(plain: ByteArray, key: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(0x53575643)
        out.writeByte(1)
        out.writeInt(VaultCrypto.CHUNK_BYTES)
        out.writeLong(plain.size.toLong())
        plain.asList().chunked(VaultCrypto.CHUNK_BYTES).forEachIndexed { index, chunk ->
            val iv = ByteArray(12) { (it + index).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(chunk.toByteArray())
            out.writeByte(12)
            out.write(iv)
            out.writeInt(encrypted.size)
            out.write(encrypted)
        }
        return bytes.toByteArray()
    }

    fun mutations(blob: ByteArray, other: ByteArray): Map<String, ByteArray> {
        val version = blob[4].toInt()
        val headerSize = if (version == 1) 17 else 33
        fun chunks(bytes: ByteArray): List<ByteArray> {
            val input = DataInputStream(bytes.inputStream())
            input.skipBytes(headerSize)
            val result = mutableListOf<ByteArray>()
            while (input.available() > 0) {
                val start = bytes.size - input.available()
                if (version != 1) input.skipBytes(9) // index and authenticated final flag
                val ivSize = input.readUnsignedByte()
                input.skipBytes(ivSize)
                val size = input.readInt()
                input.skipBytes(size)
                result += bytes.copyOfRange(start, bytes.size - input.available())
            }
            return result
        }
        val parts = chunks(blob)
        fun join(parts: List<ByteArray>) = blob.copyOf(headerSize) + parts.fold(byteArrayOf()) { a, b -> a + b }
        val swapped = parts.toMutableList().apply { val a = this[1]; this[1] = this[2]; this[2] = a }
        val duplicated = parts.toMutableList().apply { this[2] = this[1] }
        val spliced = parts.toMutableList().apply { this[1] = chunks(other)[1] }
        val flag = parts.map { it.copyOf() }.toMutableList()
        if (version != 1) flag.last()[8] = (flag.last()[8].toInt() xor 1).toByte()
        else flag.last()[0] = 0 // old format has no final flag
        // Attacker also adjusts the unauthenticated old length to hide a removed final chunk.
        val truncated = join(parts.dropLast(1))
        java.nio.ByteBuffer.wrap(truncated).putLong(9, (3L * VaultCrypto.CHUNK_BYTES))
        return linkedMapOf("swap" to join(swapped), "duplicate" to join(duplicated),
            "truncate" to truncated, "trailing" to (blob + byteArrayOf(0)),
            "final flag" to join(flag), "splice" to join(spliced))
    }
}
