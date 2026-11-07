package com.scanwhirl.app.tools

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * test26 T2.6 — Long Image tool (from a saved scan or a device PDF),
 * moved verbatim out of `MainActivity`. No picker seam: the source Uri is
 * handed in by the Activity's `chooseProSource`.
 */
class LongImageFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost
) {

    fun runLongImageForScan(doc: ScanDocument) {
        if (doc.pageFiles.isEmpty()) return
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "documents/share").apply { mkdirs() }
                    val file = File(dir, ShareUtils.sanitizeName(doc.name) + " - long.jpg")
                    LongImage.stitch(doc.pageFiles, file)
                    file
                }
                progress.dismiss()
                val dims = ImageTools.imageSize(out.readBytes()) ?: (0 to 0)
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = out.path,
                        previewPath = out.path,
                        mime = "image/jpeg",
                        displayName = out.name,
                        beforeBytes = doc.pageFiles.sumOf { it.length() },
                        afterBytes = out.length(),
                        width = dims.first,
                        height = dims.second,
                        title = doc.name,
                        warning = null
                    )
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

    fun runLongImageForPdf(uri: Uri) {
        val progress = host.showProgress()
        val tempDir = File(activity.cacheDir, "longpdf_${System.nanoTime()}")
        activity.lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.IO) {
                    tempDir.mkdirs()
                    val pages = PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        (0 until count).map { i ->
                            val file = File(
                                tempDir, String.format(Locale.ROOT, "page_%03d.jpg", i + 1)
                            )
                            file.writeBytes(PdfPageRenderer.renderPageJpeg(fd, i, 150f, 2048, 90))
                            file
                        }
                    }
                    val shareDir = File(activity.cacheDir, "documents/share").apply { mkdirs() }
                    val file = File(shareDir, ShareUtils.sanitizeName(FlowUtil.devicePdfName(repo, uri)) + " - long.jpg")
                    LongImage.stitch(pages, file)
                    file
                }
                progress.dismiss()
                val dims = ImageTools.imageSize(out.readBytes()) ?: (0 to 0)
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = out.path,
                        previewPath = out.path,
                        mime = "image/jpeg",
                        displayName = out.name,
                        beforeBytes = before,
                        afterBytes = out.length(),
                        width = dims.first,
                        height = dims.second,
                        title = FlowUtil.devicePdfName(repo, uri),
                        warning = null
                    )
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
