package com.scanwhirl.app.util

import android.content.ClipData
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

    /** Shares any file with an explicit MIME type (long image, ZIP, …). */
    fun shareFile(context: Context, file: File, mime: String) {
        shareSingle(context, file, mime)
    }

    /** Public alias of the private sanitizer for reuse by export jobs. */
    fun sanitizeName(name: String): String = sanitize(name)

    private fun shareSingle(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
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
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            val clip = ClipData.newRawUri("pages", uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
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
