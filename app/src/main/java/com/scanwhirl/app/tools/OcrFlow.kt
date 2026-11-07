package com.scanwhirl.app.tools

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * test26 T2.7 — OCR (scan / device PDF / gallery image), moved verbatim out
 * of `MainActivity`. The old result dialog moves along and stays until P6
 * (redesign); `chooseProSource` stays on the Activity and hands the source
 * in.
 */
class OcrFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost
) {

    fun runOcrForScan(doc: ScanDocument) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    if (!repo.hasOcr(doc.id) || repo.ocrDoc(doc.id).values.all { it.isBlank() }) {
                        repo.buildOcr(doc.id)
                    }
                    repo.ocrDoc(doc.id).entries
                        .sortedBy { it.key }
                        .mapIndexedNotNull { idx, entry ->
                            val clean = entry.value.trim()
                            if (clean.isEmpty()) null else "--- Page ${idx + 1} ---\n$clean"
                        }
                        .joinToString("\n\n")
                }
                progress.dismiss()
                showOcrTextDialog(
                    doc.name,
                    text.ifBlank { activity.getString(R.string.ocr_empty) }
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun runOcrForPdf(uri: Uri) {
        val progress = host.showProgress(R.string.ocr_running)
        activity.lifecycleScope.launch {
            try {
                // Render + read one page at a time: no whole PDF in memory.
                val parts = withContext(Dispatchers.IO) {
                    PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        (0 until count).mapNotNull { i ->
                            val page = PdfPageRenderer.renderPageJpeg(fd, i, 200f, 2400, 92)
                            val text = OcrEngine.recognizeBytes(page).trim()
                            if (text.isEmpty()) null else "--- Page ${i + 1} ---\n$text"
                        }
                    }
                }
                progress.dismiss()
                showOcrTextDialog(
                    FlowUtil.devicePdfName(repo, uri),
                    parts.joinToString("\n\n").ifBlank { activity.getString(R.string.ocr_empty) }
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun runOcrForImage(uri: Uri) {
        val progress = host.showProgress(R.string.ocr_running)
        activity.lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("could not read the image")
                    // EXIF rotation only — the photo is never warped or cropped.
                    OcrEngine.recognizeBytes(ImageTools.normalizePage(bytes)).trim()
                }
                progress.dismiss()
                val title = repo.displayNameOf(uri)?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.source_gallery_image)
                showOcrTextDialog(title, text.ifBlank { activity.getString(R.string.ocr_empty) })
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Shared OCR result dialog: every line, copyable and shareable. */
    private fun showOcrTextDialog(title: String, shown: String) {
        SwDialog.sheet(activity)
            .setTitle(title)
            .setMessage(shown)
            .setPositiveButton(R.string.ocr_copy) { _, _ ->
                val cb = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cb?.setPrimaryClip(android.content.ClipData.newPlainText(title, shown))
                Toast.makeText(activity, R.string.ocr_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.share_pdf) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, shown)
                }
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_pdf)))
            }
            .setNegativeButton(R.string.close, null)
            .showSw(isSheet = true)
    }
}
