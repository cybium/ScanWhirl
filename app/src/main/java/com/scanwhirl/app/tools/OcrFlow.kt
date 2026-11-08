package com.scanwhirl.app.tools

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * test26 T2.7 — OCR (scan / device PDF / gallery image), moved verbatim out
 * of `MainActivity`. P6 replaces the old read-only result dialog with
 * `OcrEditorActivity`; `chooseProSource` stays on the Activity and hands the
 * source in.
 */
class OcrFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost
) {

    fun runOcrForScan(doc: ScanDocument) {
        host.workStarted()
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val failed = ScanWhirlApp.from(activity).indexer.indexNow(doc.id)
                progress.dismiss()
                // Doc-backed: editor reads/writes repo.ocrDoc(doc.id).
                val intent = Intent(activity, OcrEditorActivity::class.java).apply {
                    putExtra(OcrEditorActivity.EXTRA_DOC_ID, doc.id)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                host.workEnded()
            }
        }
    }

    fun runOcrForPdf(uri: Uri) {
        host.workStarted()
        val progress = host.showProgress(R.string.ocr_running)
        activity.lifecycleScope.launch {
            try {
                // Render + read one page at a time: no whole PDF in memory.
                val text = withContext(Dispatchers.IO) {
                    PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        (0 until count).mapNotNull { i ->
                            val page = PdfPageRenderer.renderPageJpeg(fd, i, 200f, 2400, 92)
                            val t = OcrEngine.recognizeBytes(page).trim()
                            if (t.isEmpty()) null else "--- Page ${i + 1} ---\n$t"
                        }
                    }
                }.joinToString("\n\n")
                progress.dismiss()
                // Temp mode: write to cache, start editor with EXTRA_TEXT_PATH.
                startEditorWithText(text, FlowUtil.devicePdfName(repo, uri))
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                host.workEnded()
            }
        }
    }

    fun runOcrForImage(uri: Uri) {
        host.workStarted()
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
                startEditorWithText(text, title)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                host.workEnded()
            }
        }
    }

    /** Write OCR text to a cache file and launch the editor with EXTRA_TEXT_PATH + EXTRA_TITLE. */
    private fun startEditorWithText(text: String, title: String) {
        val cacheFile = File(activity.cacheDir, "ocr_${System.currentTimeMillis()}.txt")
        cacheFile.writeText(text) // written as-is, possibly empty; never substituted with ocr_empty
        val intent = Intent(activity, OcrEditorActivity::class.java).apply {
            putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, cacheFile.absolutePath)
            putExtra(OcrEditorActivity.EXTRA_TITLE, title)
        }
        activity.startActivity(intent)
    }
}
