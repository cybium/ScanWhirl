package com.scanwhirl.app.util

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISO 32000-2 Standard Security Handler, **V5 / R6, AESV3** (AES-256).
 *
 * This replaces RC4-128 for new files (`70_APP_DESIGN.md` §4.2): every stream is encrypted with
 * AES-256-CBC, a random 16-byte IV prefixed and PKCS#7 padding appended, so ciphertext streams are
 * longer than their plaintext and a PDF that is rewritten this way needs a fresh xref table.
 *
 * The algorithms are implemented exactly as the standard describes them:
 *  - **2.A** — retrieving the file key from /U or /O (`userKey`, `ownerKey`);
 *  - **2.B** — the iterated AES-128-CBC + SHA-2 hash (`hash`);
 *  - **8** — the user entry /U and /UE, **9** — the owner entry /O and /OE (`create`);
 *  - **10** — /Perms, the AES-256-ECB encrypted permission block (`create`).
 *
 * Passwords are SASLprep'd in the part that matters here (Unicode NFKC), encoded as UTF-8 and cut
 * at 127 bytes, so any Unicode password works — this is what fixes the old Latin-1 limit.
 *
 * RC4 files stay readable in any PDF viewer and the old unlock path is untouched; this object only
 * ever *writes* modern encryption.
 */
object PdfAes {

    const val VERSION = 5
    const val REVISION = 6
    const val KEY_BYTES = 32
    const val SALT_BYTES = 8
    const val IV_BYTES = 16
    const val MAX_PASSWORD_BYTES = 127

    /** Same permission policy the RC4 lock used (annotate allowed; print, copy and edit not). */
    const val P_DEFAULT: Int = -3392 // 0xFFFFF2C0

    private val ZERO_IV = ByteArray(IV_BYTES)
    private val RANDOM = SecureRandom()
    private val EMPTY = ByteArray(0)

    /** What a reader needs to open a stream: the 32-byte file encryption key. */
    interface Decryption {
        fun decryptStream(data: ByteArray): ByteArray
    }

    /** Everything the /Encrypt dictionary and the stream cipher need for one document. */
    class Encryption(
        val fileKey: ByteArray,
        val o: ByteArray,
        val u: ByteArray,
        val oe: ByteArray,
        val ue: ByteArray,
        val perms: ByteArray,
        val permissions: Int,
        val fileId: ByteArray,
        val encryptMetadata: Boolean = true
    ) : Decryption {

        init {
            require(fileKey.size == KEY_BYTES) { "AES-256 file key must be $KEY_BYTES bytes" }
            require(u.size == 48 && o.size == 48) { "/U and /O are 48 bytes in R6" }
            require(ue.size == KEY_BYTES && oe.size == KEY_BYTES) { "/UE and /OE are 32 bytes in R6" }
            require(perms.size == 16) { "/Perms is 16 bytes" }
            require(fileId.size == 16) { "the file id is 16 bytes" }
        }

        /** IV (16 random bytes) + AES-256-CBC/PKCS#7 ciphertext. */
        fun encryptStream(plain: ByteArray): ByteArray {
            val iv = ByteArray(IV_BYTES).also { RANDOM.nextBytes(it) }
            return iv + aesCbc(true, fileKey, iv, plain, "AES/CBC/PKCS5Padding")
        }

        override fun decryptStream(data: ByteArray): ByteArray {
            if (data.size <= IV_BYTES || (data.size - IV_BYTES) % 16 != 0) {
                throw IOException("AES stream is not IV + whole AES blocks")
            }
            val iv = data.copyOfRange(0, IV_BYTES)
            val body = data.copyOfRange(IV_BYTES, data.size)
            return aesCbc(false, fileKey, iv, body, "AES/CBC/PKCS5Padding")
        }

        /** The complete `/Encrypt` dictionary, `<< … >>` included, ready for the trailer. */
        fun encryptDict(): String {
            val md = if (encryptMetadata) "true" else "false"
            return "<< /Filter /Standard /V $VERSION /R $REVISION /Length 256 " +
                "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length $KEY_BYTES >> >> " +
                "/StmF /StdCF /StrF /StdCF " +
                "/O <${hex(o)}> /U <${hex(u)}> /OE <${hex(oe)}> /UE <${hex(ue)}> " +
                "/Perms <${hex(perms)}> /P $permissions /EncryptMetadata $md >>"
        }
    }

    /** A decryption that only knows the file key (verification, tests, recovery tools). */
    fun decryptor(fileKey: ByteArray): Decryption {
        require(fileKey.size == KEY_BYTES) { "AES-256 file key must be $KEY_BYTES bytes" }
        return object : Decryption {
            override fun decryptStream(data: ByteArray): ByteArray {
                if (data.size <= IV_BYTES || (data.size - IV_BYTES) % 16 != 0) {
                    throw IOException("AES stream is not IV + whole AES blocks")
                }
                return aesCbc(
                    false, fileKey, data.copyOfRange(0, IV_BYTES),
                    data.copyOfRange(IV_BYTES, data.size), "AES/CBC/PKCS5Padding"
                )
            }
        }
    }

    /**
     * Builds a complete V5/R6 encryption for [password]: a random 256-bit file key, /U + /UE
     * (Algorithm 8), /O + /OE (Algorithm 9) and /Perms (Algorithm 10).
     *
     * Owner and user password are the same here: ScanWhirl writes files a person opens, it does not
     * model a separate document owner.
     */
    fun create(
        password: String,
        permissions: Int = P_DEFAULT,
        encryptMetadata: Boolean = true,
        fileKey: ByteArray = randomBytes(KEY_BYTES),
        fileId: ByteArray = randomBytes(16)
    ): Encryption {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val pw = normalizePassword(password)
        try {

        val uValidation = randomBytes(SALT_BYTES)
        val uKeySalt = randomBytes(SALT_BYTES)
        val u = hash(pw, uValidation) + uValidation + uKeySalt
        val ue = aesCbc(true, hash(pw, uKeySalt), ZERO_IV, fileKey, "AES/CBC/NoPadding")

        // Algorithm 9: the owner entry hashes the user entry /U as its user data.
        val oValidation = randomBytes(SALT_BYTES)
        val oKeySalt = randomBytes(SALT_BYTES)
        val o = hash(pw, oValidation, u) + oValidation + oKeySalt
        val oe = aesCbc(true, hash(pw, oKeySalt, u), ZERO_IV, fileKey, "AES/CBC/NoPadding")

        val permsBlock = ByteArray(16)
        permsBlock[0] = (permissions and 0xFF).toByte()
        permsBlock[1] = ((permissions ushr 8) and 0xFF).toByte()
        permsBlock[2] = ((permissions ushr 16) and 0xFF).toByte()
        permsBlock[3] = ((permissions ushr 24) and 0xFF).toByte()
        permsBlock[4] = 0xFF.toByte()
        permsBlock[5] = 0xFF.toByte()
        permsBlock[6] = 0xFF.toByte()
        permsBlock[7] = 0xFF.toByte()
        permsBlock[8] = if (encryptMetadata) 'T'.code.toByte() else 'F'.code.toByte()
        permsBlock[9] = 'a'.code.toByte()
        permsBlock[10] = 'd'.code.toByte()
        permsBlock[11] = 'b'.code.toByte()
        randomBytes(4).copyInto(permsBlock, 12)
        val perms = aesEcb(true, fileKey, permsBlock)

        return Encryption(fileKey, o, u, oe, ue, perms, permissions, fileId, encryptMetadata)
        } finally {
            pw.fill(0)
        }
    }

    /**
     * Algorithm 2.A for the user password: null when the password is wrong, otherwise the 32-byte
     * file key (Algorithm 10's authentication, then /UE decrypted with the key salt).
     *
     * [revision] selects the hash: R5 uses one SHA-256 of password + salt + user data, R6 uses the
     * iterated Algorithm 2.B. Passing R6's loop at an R5 file would reject its own password.
     */
    fun userKey(password: String, u: ByteArray, ue: ByteArray, revision: Int = REVISION): ByteArray? {
        if (u.size != 48 || ue.size != KEY_BYTES) return null
        if (revision !in 5..6) return null
        val pw = normalizePassword(password)
        return try {
            val validationSalt = u.copyOfRange(32, 40)
            if (!MessageDigest.isEqual(hashFor(revision, pw, validationSalt), u.copyOfRange(0, 32))) return null
            val intermediate = hashFor(revision, pw, u.copyOfRange(40, 48))
            aesCbc(false, intermediate, ZERO_IV, ue, "AES/CBC/NoPadding")
        } finally {
            pw.fill(0)
        }
    }

    /** Algorithm 2.A for the owner password (Algorithm 11): /U is the owner hash's user data. */
    fun ownerKey(
        password: String,
        o: ByteArray,
        oe: ByteArray,
        u: ByteArray,
        revision: Int = REVISION
    ): ByteArray? {
        if (o.size != 48 || oe.size != KEY_BYTES || u.size != 48) return null
        if (revision !in 5..6) return null
        val pw = normalizePassword(password)
        return try {
            val validationSalt = o.copyOfRange(32, 40)
            if (!MessageDigest.isEqual(hashFor(revision, pw, validationSalt, u), o.copyOfRange(0, 32))) return null
            val intermediate = hashFor(revision, pw, o.copyOfRange(40, 48), u)
            aesCbc(false, intermediate, ZERO_IV, oe, "AES/CBC/NoPadding")
        } finally {
            pw.fill(0)
        }
    }

    /**
     * Algorithm 13: /Perms is the AES-256-ECB encryption of /P (little endian), FF FF FF FF, the
     * metadata flag ('T'/'F'), "adb" and four random bytes. False means the block was altered or
     * does not belong to this key, so the file must be refused rather than trusted.
     */
    fun permsValid(
        fileKey: ByteArray,
        perms: ByteArray,
        permissions: Int,
        encryptMetadata: Boolean
    ): Boolean {
        if (fileKey.size != KEY_BYTES || perms.size != 16) return false
        val block = try {
            aesEcb(false, fileKey, perms)
        } catch (e: IOException) {
            return false
        }
        if (block.size != 16) return false
        for (i in 0..3) {
            if (block[i] != ((permissions ushr (8 * i)) and 0xFF).toByte()) return false
        }
        // ISO 32000-2 Algorithm 13: bytes 4-7 are fixed at FF FF FF FF. A block that decrypts with
        // the right key and /P but a different constant was rewritten by something else: refuse it.
        for (i in 4..7) {
            if (block[i] != 0xFF.toByte()) return false
        }
        if (block[9] != 'a'.code.toByte() || block[10] != 'd'.code.toByte() || block[11] != 'b'.code.toByte()) {
            return false
        }
        val flag = block[8]
        return if (encryptMetadata) flag == 'T'.code.toByte() else flag == 'F'.code.toByte()
    }

    /** NFKC, UTF-8, at most [MAX_PASSWORD_BYTES] bytes — the standard's password preparation. */
    fun normalizePassword(password: String): ByteArray {
        val bytes = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return if (bytes.size <= MAX_PASSWORD_BYTES) bytes else bytes.copyOf(MAX_PASSWORD_BYTES)
    }

    /**
     * Password hash for [revision]: R5 is a single SHA-256 of password + salt + user data
     * (ISO 32000-2 2.A step 2, the deprecated revision real files still carry); R6 is the
     * iterated Algorithm 2.B.
     */
    internal fun hashFor(
        revision: Int,
        password: ByteArray,
        salt: ByteArray,
        userData: ByteArray = EMPTY
    ): ByteArray {
        if (revision <= 5) return sha("SHA-256", password + salt + userData)
        var k = sha("SHA-256", password + salt + userData)
        var e = ByteArray(0)
        var rounds = 0
        while (rounds < 64 || (e[e.size - 1].toInt() and 0xFF) > rounds - 32) {
            val unit = password + k + userData
            val k1 = ByteArray(unit.size * 64)
            for (r in 0 until 64) unit.copyInto(k1, r * unit.size)
            // AES-128-CBC with no padding: key = K[0..16], IV = K[16..32], never applied to nothing.
            if (k1.isEmpty()) break
            e = aesCbc(true, k.copyOfRange(0, 16), k.copyOfRange(16, 32), k1, "AES/CBC/NoPadding")
            // The first 16 bytes of E as a big-endian integer mod 3 (256 ≡ 1 mod 3, so a byte sum works).
            val selector = (0 until 16).sumOf { e[it].toInt() and 0xFF } % 3
            k = when (selector) {
                0 -> sha("SHA-256", e)
                1 -> sha("SHA-384", e)
                else -> sha("SHA-512", e)
            }
            rounds++
        }
        return k.copyOf(KEY_BYTES)
    }

    /** Algorithm 2.B (Revision 6). */
    internal fun hash(password: ByteArray, salt: ByteArray, userData: ByteArray = EMPTY): ByteArray =
        hashFor(REVISION, password, salt, userData)

    // ------------------------------------------------------------------
    // primitives
    // ------------------------------------------------------------------

    private fun sha(algorithm: String, data: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(data)

    private fun aesCbc(
        encrypt: Boolean,
        key: ByteArray,
        iv: ByteArray,
        data: ByteArray,
        transformation: String
    ): ByteArray = try {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        cipher.doFinal(data)
    } catch (e: GeneralSecurityException) {
        throw IOException("AES failed: ${e.message}", e)
    }

    private fun aesEcb(encrypt: Boolean, key: ByteArray, data: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(data)
    } catch (e: GeneralSecurityException) {
        throw IOException("AES failed: ${e.message}", e)
    }

    internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also { RANDOM.nextBytes(it) }

    internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789ABCDEF"[v ushr 4])
            append("0123456789ABCDEF"[v and 0x0F])
        }
    }
}
