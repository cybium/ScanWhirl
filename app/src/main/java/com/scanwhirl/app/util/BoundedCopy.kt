package com.scanwhirl.app.util

import android.content.Context
import android.net.Uri
import android.os.Looper
import com.scanwhirl.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Private scratch copies only. The caller owns a successful file and must [release] it. */
object BoundedCopy {
    enum class Result { OK, TOO_LARGE, NO_SPACE, UNREADABLE, CANCELLED }
    const val HEADROOM = 16L * 1024 * 1024
    const val IMAGE_CAP = ImageTools.MAX_PAGE_BYTES.toLong()
    private const val CHUNK = 64 * 1024
    private val owned = mutableSetOf<String>()

    // Used by the cold-start sweep too: a later sweep must not unlink a live reader.
    @Synchronized internal fun retain(file: File) { owned.add(file.absolutePath) }
    @Synchronized internal fun isOwned(file: File): Boolean = file.absolutePath in owned
    @Synchronized internal fun removeIfUnowned(file: File): Boolean =
        !isOwned(file) && file.delete()
    @Synchronized fun release(file: File) { file.delete(); owned.remove(file.absolutePath) }

    @android.annotation.SuppressLint("UsableSpace")
    internal fun availableSpace(dir: File): Long = dir.usableSpace

    suspend fun copy(context: Context, uri: Uri, target: File, cap: Long = PdfImport.MAX_BYTES): Result =
        copy(target, cap, open = { context.contentResolver.openInputStream(uri) })

    /**
     * Opens, reads and writes on IO, polling cancellation before AND after each read.
     * runInterruptible helps cooperative providers; an arbitrary provider ignoring interruption
     * can still block its read. No main-thread read or successful partial result is permitted.
     */
    internal suspend fun copy(
        target: File,
        cap: Long,
        space: (File) -> Long = ::availableSpace,
        open: () -> InputStream?
    ): Result {
        require(cap > 0)
        retain(target)
        val result = try {
            withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                runInterruptible {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "Copy must run off main" }
                    val dir = target.absoluteFile.parentFile ?: return@runInterruptible Result.UNREADABLE
                    if (!dir.isDirectory && !dir.mkdirs()) return@runInterruptible Result.UNREADABLE
                    if (space(dir) < HEADROOM + CHUNK) return@runInterruptible Result.NO_SPACE
                    val input = open() ?: return@runInterruptible Result.UNREADABLE
                    input.use { src ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(CHUNK)
                            var total = 0L
                            while (true) {
                                jobContext.ensureActive()
                                // At the cap, read at most one extra byte to distinguish EOF.
                                val n = src.read(buffer, 0, minOf(CHUNK.toLong(), cap - total + 1).toInt())
                                jobContext.ensureActive()
                                if (n < 0) return@runInterruptible if (total > 0) Result.OK else Result.UNREADABLE
                                if (n == 0) continue
                                if (n.toLong() > cap - total) return@runInterruptible Result.TOO_LARGE
                                if (space(dir) < HEADROOM + n) return@runInterruptible Result.NO_SPACE
                                out.write(buffer, 0, n)
                                total += n
                            }
                            @Suppress("UNREACHABLE_CODE")
                            Result.UNREADABLE
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            Result.CANCELLED
        } catch (_: Exception) {
            // A full filesystem may also reject open/write before the next probe.
            if (runCatching { space(target.absoluteFile.parentFile!!) < HEADROOM + CHUNK }.getOrDefault(false))
                Result.NO_SPACE else Result.UNREADABLE
        } catch (t: Throwable) {
            release(target)
            throw t
        }
        if (result != Result.OK) release(target)
        return result
    }

    class Refused(val result: Result, context: Context) : IOException(context.getString(when (result) {
        Result.TOO_LARGE -> R.string.l1_copy_too_large
        Result.NO_SPACE -> R.string.l1_copy_no_space
        else -> R.string.l1_copy_unreadable
    }))

    fun requireOk(result: Result, context: Context) {
        if (result == Result.CANCELLED) throw CancellationException("Private copy cancelled")
        if (result != Result.OK) throw Refused(result, context)
    }
}
