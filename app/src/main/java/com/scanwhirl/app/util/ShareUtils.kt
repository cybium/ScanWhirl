package com.scanwhirl.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Shares a scan's PDF (or its pages) under the document's own name. */
object ShareUtils {

    /**
     * How long a share copy must stay readable. A recipient app reads its content:// URI
     * lazily, and Android can restart our process to serve it, so a copy is never deleted
     * while a recent share may still be in flight. The cold-start sweep follows the same rule.
     */
    internal const val SHARE_KEEP_MS = 24L * 60 * 60 * 1000

    /** FileProvider cache tree for outgoing files (registered as "cache_docs"). */
    private const val SHARE_ROOT = "documents/share"

    /** Per-share folder names start with this marker, then the creation time. */
    private const val TOKEN_PREFIX = "x3-"

    fun share(context: Context, doc: ScanDocument) {
        when {
            doc.pdfFile.exists() -> {
                shareCopy(context, doc.pdfFile, "application/pdf", documentFileName(doc))
            }
            doc.pageFiles.isNotEmpty() -> sharePages(context, doc)
            else -> Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    /** The name a document's PDF carries when it leaves the app: its own name, never `scan.pdf`. */
    private fun documentFileName(doc: ScanDocument): String =
        ExportNames.fileName(doc.name, "pdf", "scan")

    /** (stored file, outgoing name) for each document that has a PDF, for shares and ZIPs. */
    fun documentItems(docs: List<ScanDocument>): List<Pair<File, String>> =
        docs.filter { it.pdfFile.isFile }.map { it.pdfFile to documentFileName(it) }

    /** (file, outgoing name) for files that already carry the name they should be handed out under. */
    fun fileItems(files: List<File>): List<Pair<File, String>> =
        files.filter { it.isFile }.map { it to it.name }

    /**
     * H2-1: copies every item into ONE fresh share folder under its own display name and returns
     * the FileProvider URIs in the same order. Names that collide (ignoring case) are numbered
     * ("Rent.pdf", "Rent (2).pdf"). The stored library files are never exposed or renamed. An
     * item that cannot be copied is skipped, like an unreadable one always was.
     */
    fun namedUris(context: Context, items: List<Pair<File, String>>): List<Uri> {
        val usable = items.filter { it.first.isFile }
        if (usable.isEmpty()) return emptyList()
        val dir = newShareDirectory(context)
        val names = ExportNames.Allocator()
        val uris = ArrayList<Uri>(usable.size)
        for ((file, displayName) in usable) {
            val out = File(dir, names.file(shareFileName(displayName)))
            val uri = runCatching {
                file.copyTo(out, overwrite = false)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
            }.getOrNull()
            if (uri != null) uris += uri else out.delete()
        }
        return uris
    }

    /** Library multi-select Share: every document's PDF under its own name in one chooser. */
    fun shareDocuments(context: Context, docs: List<ScanDocument>) {
        val uris = namedUris(context, documentItems(docs))
        if (uris.isEmpty()) {
            Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = multiShareIntent(context, uris, "application/pdf")
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    /** Shares any file with an explicit MIME type (ZIP, PDF, image, …). */
    fun shareFile(context: Context, file: File, mime: String) {
        shareCopy(context, file, mime, file.name)
    }

    /**
     * A tool result may be removed by Undo or the next cold start. Never grant that scratch
     * path. Publish only a complete, independent copy, with all disk work on IO.
     *
     * Vault-open plaintext is deliberately excluded by FileProvider's configured roots.
     * Do not bypass that boundary by making a 24-hour plaintext copy.
     */
    private fun shareCopy(context: Context, source: File, mime: String, displayName: String) {
        val vaultOpen = runCatching {
            source.canonicalFile.startsWith(File(context.cacheDir, "vault_open").canonicalFile)
        }.getOrElse {
            Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        if (vaultOpen) {
            Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val scope = (context as? LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main.immediate)
        scope.launch {
            var target: File? = null
            var issued = false
            try {
                withContext(Dispatchers.IO) {
                    val out = newShareTarget(context, displayName)
                    target = out
                    val staging = File(out.parentFile, ".copying")
                    try {
                        val size = source.length()
                        check(source.isFile && size > 0) { "No shareable file" }
                        BoundedCopy.requireOk(
                            BoundedCopy.copy(staging, size, open = { source.inputStream() }), context
                        )
                        check(staging.length() == size && staging.renameTo(out)) { "Incomplete share copy" }
                    } finally {
                        BoundedCopy.release(staging)
                    }
                }
                if (context is Activity && (context.isFinishing || context.isDestroyed)) return@launch
                shareSingle(context, checkNotNull(target), mime)
                issued = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No chooser, URI grant or misleading success after a failed/partial copy.
                Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            } finally {
                if (!issued) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    target?.parentFile?.deleteRecursively()
                }
            }
        }
    }

    /** Shares a chosen set of page images (Organize selection). */
    fun shareImages(context: Context, files: List<File>, docName: String) {
        if (files.isEmpty()) {
            Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(files.size)
        files.forEachIndexed { i, page ->
            val named = namedCopy(context, page, "${sanitize(docName)} - page ${i + 1}.jpg")
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named))
        }
        val intent = multiShareIntent(context, uris, "image/*")
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    /**
     * The one multi-file share intent. The Android 10+ URI-grant model — and the sharesheet —
     * follows the intent's ClipData, so ClipData must carry EVERY URI: with only the first one
     * in it (the old ToolResultActivity bug) most receivers could read the first file and the
     * rest failed silently (a batch of PDFs shared to WhatsApp arrived as one file). EXTRA_STREAM
     * keeps the same list, the flag is the read grant only (sharing never needs write).
     */
    fun multiShareIntent(
        context: Context,
        uris: List<Uri>,
        type: String,
        label: CharSequence = "pages"
    ): Intent {
        require(uris.isNotEmpty()) { "a multi share needs at least one Uri" }
        val clip = ClipData.newUri(context.contentResolver, label, uris.first())
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Public alias of the private sanitizer for reuse by export jobs. */
    fun sanitizeName(name: String): String = sanitize(name)

    private fun shareSingle(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // G2: EXTRA_STREAM alone is not enough for the Android 12+ sharesheet — it
            // reads the grant from ClipData to preview the file, and without it logs
            // "Permission Denial ... call Intent#setClipData()". The ClipData carries the
            // same URI and MIME as EXTRA_STREAM so the chooser (which migrates both the
            // ClipData and the read-grant flag onto its own intent) keeps read access.
            clipData = ClipData(
                ClipDescription(file.name, arrayOf(mime)),
                ClipData.Item(uri)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    private fun sharePages(context: Context, doc: ScanDocument) {
        val uris = ArrayList<Uri>(doc.pageFiles.size)
        doc.pageFiles.forEachIndexed { i, page ->
            val named = namedCopy(context, page, "${sanitize(doc.name)} - page ${i + 1}.jpg")
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", named))
        }
        val intent = multiShareIntent(context, uris, "image/*")
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf)))
    }

    /**
     * A fresh folder for one outgoing share, inside the FileProvider cache tree. Every share
     * gets its own folder, so two documents with the same display name never touch the same
     * file: the recipient still sees the human file name, and a slow recipient keeps reading
     * its own bytes even after another document with the same name is shared.
     */
    fun newShareDirectory(context: Context): File {
        pruneShareCopies(context)
        val root = File(context.cacheDir, SHARE_ROOT).apply { mkdirs() }
        val token = "$TOKEN_PREFIX${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        return File(root, token).apply { mkdirs() }
    }

    /** A unique outgoing file path under its display name; long names keep their extension. */
    fun newShareTarget(context: Context, displayName: String): File =
        File(newShareDirectory(context), shareFileName(displayName))

    /**
     * Sanitises the stem (60 characters, as [sanitize] always did) but keeps a short, plain
     * extension, so a long document name still reaches the recipient as a `.pdf`/`.jpg`.
     */
    internal fun shareFileName(displayName: String): String =
        ExportNames.sanitizeFileName(displayName, "scan")

    /**
     * Age-based cleanup of old share copies. Never deletes a copy younger than
     * [SHARE_KEEP_MS], because a recipient (or Android restarting our provider) may still
     * read a recent URI. Returns how many entries were removed.
     */
    internal fun pruneShareCopies(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Int {
        val root = File(context.cacheDir, SHARE_ROOT)
        if (!root.isDirectory) return 0
        val entries = root.listFiles() ?: return 0
        var removed = 0
        entries.forEach { entry ->
            val age = now - createdOf(entry)
            if (age < SHARE_KEEP_MS) return@forEach
            val gone = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (gone) removed++
        }
        return removed
    }

    /** A per-share folder carries its creation time as a token prefix; legacy files use mtime. */
    private fun createdOf(entry: File): Long {
        if (entry.name.startsWith(TOKEN_PREFIX)) {
            entry.name.removePrefix(TOKEN_PREFIX).substringBefore('-').toLongOrNull()
                ?.let { return it }
        }
        return entry.lastModified()
    }

    /** Copies [src] into a fresh share folder under its display name. */
    private fun namedCopy(context: Context, src: File, displayName: String): File {
        val out = newShareTarget(context, displayName)
        src.copyTo(out, overwrite = false)
        return out
    }

    /** A name without an extension: illegal characters replaced, 60 code points at most (H2-3). */
    private fun sanitize(name: String): String = ExportNames.sanitizeBase(name, "scan")
}
