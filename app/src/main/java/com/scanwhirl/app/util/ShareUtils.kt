package com.scanwhirl.app.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import java.io.File

/** Shares a scan's PDF (or its pages) under the document's own name. */
object ShareUtils {

    fun share(context: Context, doc: ScanDocument) {
        when {
            doc.pdfFile.exists() -> {
                val named = namedCopy(context, doc.pdfFile, sanitize(doc.name) + ".pdf")
                shareSingle(context, named, "application/pdf")
            }
            doc.pageFiles.isNotEmpty() -> sharePages(context, doc)
            else -> Toast.makeText(context, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shares any file with an explicit MIME type (ZIP, PDF, image, …). */
    fun shareFile(context: Context, file: File, mime: String) {
        shareSingle(context, file, mime)
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
     * Copies [src] under its display name inside the registered FileProvider cache tree
     * (cacheDir/documents/… matches the "cache_docs" path).
     */
    private fun namedCopy(context: Context, src: File, displayName: String): File {
        val dir = File(context.cacheDir, "documents/share").apply { mkdirs() }
        val out = File(dir, displayName)
        src.copyTo(out, overwrite = true)
        return out
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('_')
        return cleaned.ifBlank { "scan" }.take(60)
    }
}
