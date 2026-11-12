package com.scanwhirl.app.store

import android.content.Context
import android.net.Uri
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Durable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * M07 P0: signed, per-document proof that a document *created* an exported copy.
 *
 * A file name and `owner_package_name` only say "some ScanWhirl document wrote a
 * file with this title". They cannot tell a deleted document's retained copy from
 * a later document that happens to share the title. So deletion trusts only a
 * provenance record:
 *
 *  - written when a save **created** the file (not when it found an identical
 *    copy already there and adopted it);
 *  - holding the copy's URI and fingerprint (byte length + SHA-256) at creation;
 *  - signed with HMAC-SHA256 over `(document id, URI, fingerprint)` using a random
 *    per-install key kept in `noBackupFilesDir`.
 *
 * The signature binds the record to the one document that made the copy. Copying
 * a meta line into another document, an older build's unsigned `exports=` /
 * `exportsigs=` lines, a record restored onto another install, or a document
 * restored under a different id all fail verification. Those copies are treated as
 * unproven and are kept. Nothing retroactively becomes "owned".
 */
internal object ExportProvenance {

    private const val KEY_FILE = "export_provenance.key"
    private const val KEY_BYTES = 32
    private const val VERSION = "swexport-v1"

    private val LOCK = Any()

    /** Cached key, tied to the file it came from (tests get a fresh app dir). */
    @Volatile private var cached: Pair<String, ByteArray>? = null

    private fun keyFile(context: Context): File = File(context.noBackupFilesDir, KEY_FILE)

    /** The install key, or null when it does not exist and [create] is false. */
    private fun key(context: Context, create: Boolean): ByteArray? = synchronized(LOCK) {
        val file = keyFile(context)
        cached?.let { (path, key) -> if (path == file.path && file.isFile) return key }
        if (file.isFile) {
            val raw = try {
                android.util.Base64.decode(file.readText().trim(), android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
            if (raw != null && raw.size == KEY_BYTES) {
                cached = file.path to raw
                return raw
            }
            // A damaged key proves nothing. Never overwrite it silently: records
            // signed with it simply stay unproven, and no new ones can be signed.
            return null
        }
        if (!create) return null
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        file.parentFile?.mkdirs()
        Durable.atomicWriteText(
            file,
            android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP)
        )
        cached = file.path to fresh
        fresh
    }

    private fun mac(key: ByteArray, docId: String, uri: Uri, fp: DownloadSaver.Fingerprint): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("$VERSION\n$docId\n$uri\n${fp.encode()}".toByteArray(Charsets.UTF_8))
        }

    /** Signature for a copy [docId] just created, or null when no key can be made. */
    fun sign(context: Context, docId: String, uri: Uri, fp: DownloadSaver.Fingerprint): String? {
        val key = try {
            key(context, create = true)
        } catch (_: Exception) {
            null
        } ?: return null
        return hex(mac(key, docId, uri, fp))
    }

    /** True only for a record this install signed for exactly this document, URI and fingerprint. */
    fun verify(
        context: Context,
        docId: String,
        uri: Uri,
        fp: DownloadSaver.Fingerprint,
        signature: String
    ): Boolean {
        val key = try {
            key(context, create = false)
        } catch (_: Exception) {
            null
        } ?: return false
        val expected = hex(mac(key, docId, uri, fp)).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, signature.toByteArray(Charsets.US_ASCII))
    }

    internal fun resetForTests() {
        cached = null
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
