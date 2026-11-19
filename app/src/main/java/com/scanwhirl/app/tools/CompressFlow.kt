package com.scanwhirl.app.tools

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.DialogCompressBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * test26 T2.2 — Compress tool, moved verbatim out of `MainActivity`.
 *
 * @param pickPdf launches the SAF PDF picker (the launcher stays registered
 *   in the Activity); its result comes back through [startFromFile].
 * @param docs the current document list (allDocs on the Activity).
 */
class CompressFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val pickPdf: () -> Unit,
    private val docs: () -> List<ScanDocument>
) {

    private data class Level(
        val nameRes: Int,
        val quality: Int,
        val maxDim: Int?,
        val dpi: Float
    )

    private val levels = listOf(
        Level(R.string.compress_level_low, 80, null, 200f),
        Level(R.string.compress_level_medium, 65, 2000, 150f),
        Level(R.string.compress_level_high, 50, 1600, 130f),
        Level(R.string.compress_level_ultra, 38, 1200, 110f)
    )

    /** The pages chosen in the device-PDF gate (null until a device PDF is picked). */
    private var devicePages: List<Int>? = null

    fun start() {
        devicePages = null
        // No saved scans yet: the source is necessarily an external file.
        if (docs().isEmpty()) {
            pickPdf()
            return
        }
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.compress_title)
            .setOptions(
                listOf(
                    activity.getString(R.string.compress_source_scan),
                    activity.getString(R.string.compress_source_file)
                )
            ) { which, _ ->
                when (which) {
                    0 -> FlowUtil.pickScan(
                        activity, R.string.compress_pick_scan, docs()
                    ) { doc -> estimateForScan(doc) }
                    1 -> pickPdf()
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    /**
     * test26 T5.2 (§4.5): compress straight from a screen that already shows
     * the scan (the document screen's More row) — no source chooser, no SAF
     * picker needed.
     */
    fun startWith(doc: ScanDocument) {
        devicePages = null
        estimateForScan(doc)
    }

    /** Compress sample pages at each level, then show the choice with sizes. */
    private fun estimateForScan(doc: ScanDocument) {
        val progress = host.showProgress(R.string.compress_estimating)
        activity.lifecycleScope.launch {
            try {
                val estimates = withContext(Dispatchers.IO) {
                    val sampleFiles = listOfNotNull(
                        doc.pageFiles.firstOrNull(),
                        doc.pageFiles.getOrNull(doc.pageFiles.size / 2)
                    ).distinct()
                    val samples = sampleFiles.map { it.readBytes() }
                    levels.map { level ->
                        val avg = samples.map {
                            ImageTools.recompressJpeg(it, level.quality, level.maxDim).size.toLong()
                        }.average()
                        (avg * doc.pageCount * 1.02 + 1024).toLong()
                    }
                }
                progress.dismiss()
                pickLevel(estimates,
                    onChosen = { level -> runCompressScan(doc, level) },
                    onTarget = { target -> runCompressScanToTarget(doc, target) })
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Render page 1 of an external PDF at each level, extrapolate, then choose. */
    private fun estimateForPdfFile(uri: Uri) {
        val progress = host.showProgress(R.string.compress_estimating)
        activity.lifecycleScope.launch {
            try {
                val estimates = withContext(Dispatchers.IO) {
                    // One seekable copy: SAF pipes cannot be rendered directly.
                    PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        val count = PdfPageRenderer.pageCount(fd)
                        levels.map { level ->
                            val first = PdfPageRenderer.renderPageJpeg(
                                fd, devicePages?.firstOrNull() ?: 0,
                                level.dpi, level.maxDim ?: 4096, level.quality
                            )
                            val pages = devicePages?.size ?: count
                            (first.size.toDouble() * pages * 1.02 + 1024).toLong()
                        }
                    }
                }
                progress.dismiss()
                pickLevel(estimates,
                    onChosen = { level -> runCompressFile(uri, level) },
                    onTarget = { target -> runCompressFileToTarget(uri, target) })
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Level picker with size estimates, plus an exact target-size field. */
    private fun pickLevel(estimates: List<Long>, onChosen: (Level) -> Unit, onTarget: (Long) -> Unit) {
        val dialogBinding = DialogCompressBinding.inflate(activity.layoutInflater)
        levels.forEachIndexed { i, level ->
            val button = RadioButton(activity).apply {
                id = View.generateViewId()
                text = activity.getString(
                    R.string.compress_est_item,
                    activity.getString(level.nameRes),
                    StatusLine.formatBytes(estimates.getOrElse(i) { 0L })
                )
                // B2-M3 (device sweep 2026-11-19): a bare RadioButton was a 32dp-high row.
                // The rule is 48dp minimum on every touch target; the visual density stays,
                // the row just gets breathing room around the same text.
                minHeight = SwStyle.dp(activity, 48f)
                gravity = Gravity.CENTER_VERTICAL
                val padV = SwStyle.dp(activity, 8f)
                setPadding(SwStyle.dp(activity, 4f), padV, SwStyle.dp(activity, 4f), padV)
            }
            dialogBinding.levelGroup.addView(button)
        }
        (dialogBinding.levelGroup.getChildAt(1) as? RadioButton)?.isChecked = true

        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.compress_level_title)
            .setContent(dialogBinding.root)
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setPrimaryStayOpen(android.R.string.ok) {
            val targetText = dialogBinding.editCompressTarget.text?.toString()
            if (!targetText.isNullOrBlank()) {
                val target = ImageTools.parseSizeInput(targetText)
                if (target == null) {
                    Toast.makeText(activity, R.string.resize_invalid_target, Toast.LENGTH_LONG).show()
                    return@setPrimaryStayOpen
                }
                sheet.dismiss()
                onTarget(target)
                return@setPrimaryStayOpen
            }
            val checked = dialogBinding.levelGroup.checkedRadioButtonId
            val index = dialogBinding.levelGroup.indexOfChild(dialogBinding.levelGroup.findViewById(checked))
            sheet.dismiss()
            onChosen(levels.getOrElse(if (index >= 0) index else 1) { levels[1] })
        }
        sheet.show()
    }

    private fun runCompressScan(doc: ScanDocument, level: Level) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = if (doc.pdfFile.exists()) doc.pdfFile.length() else 0L
                val out = repo.compressScan(doc.id, level.quality, level.maxDim)
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                host.refreshList()
                showCompressResult(out, before, after)
            } catch (e: Exception) {
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Compresses a saved scan so the finished PDF fits the exact target size. */
    private fun runCompressScanToTarget(doc: ScanDocument, target: Long) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) {
                    doc.pdfFile.length()
                } else {
                    doc.pageFiles.sumOf { it.length() }
                }
                val pages = withContext(Dispatchers.IO) { fitFilesToTotal(doc.pageFiles, target) }
                val out = repo.createDocument("${doc.name} (compressed)", pages)
                host.refreshList()
                showCompressResult(out, before, out.pdfFile.length(),
                    if (out.pdfFile.length() > target) {
                        activity.getString(R.string.compress_target_missed, StatusLine.formatBytes(target))
                    } else null)
            } catch (e: Exception) {
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Opens the real result screen (preview + actual before/after + save/share). */
    private fun showCompressResult(out: ScanDocument, before: Long, after: Long, extraWarning: String? = null) {
        val warning = when {
            extraWarning != null -> extraWarning
            before > 0 && after > before -> activity.getString(R.string.compress_grew_warning)
            else -> null
        }
        activity.startActivity(
            ExportResultActivity.intent(
                activity,
                outputPath = out.pdfFile.path,
                previewPath = out.pageFiles.firstOrNull()?.path,
                mime = "application/pdf",
                displayName = DownloadSaver.pdfNameFor(out.name),
                beforeBytes = before,
                afterBytes = after,
                width = 0,
                height = 0,
                title = out.name,
                warning = warning,
                docId = out.id // T4.8: record the saved copy on this document
            )
        )
    }

    fun startFromFile(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { repo.inspectDevicePdf(uri, 2048) }
                val decision = com.scanwhirl.app.ui.tools.DevicePdfGate.decide(activity, plan)
                if (decision == ScanRepository.ImportDecision.Cancel) return@launch
                devicePages = when (decision) {
                    ScanRepository.ImportDecision.All -> (0 until plan.pageCount).toList()
                    is ScanRepository.ImportDecision.Pages -> decision.indices
                    ScanRepository.ImportDecision.Cancel -> return@launch
                }
                estimateForPdfFile(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun runCompressFile(uri: Uri, level: Level) {
        var job: kotlinx.coroutines.Job? = null
        var sheet: com.scanwhirl.app.ui.kit.SwSheet? = null
        job = activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = FlowUtil.devicePdfName(repo, uri)
                sheet = withContext(Dispatchers.Main) {
                    com.scanwhirl.app.ui.tools.DevicePdfGate.progress(activity) { job?.cancel() }
                        .also { it.show() }
                }
                val indices = devicePages.orEmpty()
                val out = repo.importPdfAsScanSelected(
                    uri, name, level.dpi, level.maxDim ?: 4096, level.quality,
                    indices = indices,
                    onPage = { done, total ->
                        activity.runOnUiThread {
                            sheet?.setMessage(
                                com.scanwhirl.app.ui.tools.DevicePdfGate
                                    .progressText(activity, done, total)
                            )
                        }
                    }
                ) ?: return@launch
                sheet?.dismiss()
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                host.refreshList()
                showCompressResult(out, before, after)
            } catch (e: kotlinx.coroutines.CancellationException) {
                sheet?.dismiss()
                throw e
            } catch (e: Exception) {
                sheet?.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Renders an external PDF and re-fits its pages so the new scan hits the exact target. */
    private fun runCompressFileToTarget(uri: Uri, target: Long) {
        var job: kotlinx.coroutines.Job? = null
        var sheet: com.scanwhirl.app.ui.kit.SwSheet? = null
        job = activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val baseName = FlowUtil.devicePdfName(repo, uri)
                sheet = withContext(Dispatchers.Main) {
                    com.scanwhirl.app.ui.tools.DevicePdfGate.progress(activity) { job?.cancel() }
                        .also { it.show() }
                }
                val indices = devicePages.orEmpty()
                val rendered = withContext(Dispatchers.IO) {
                    PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        // One page at a time: never hold a whole PDF's JPEGs in memory.
                        val out = mutableListOf<ByteArray>()
                        indices.forEachIndexed { position, pageIndex ->
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            PdfPageRenderer.renderPageJpeg(
                                fd, pageIndex, 150f, 2048, 85
                            ).let { out.add(it) }
                            activity.runOnUiThread {
                                sheet?.setMessage(
                                    com.scanwhirl.app.ui.tools.DevicePdfGate
                                        .progressText(activity, position + 1, indices.size)
                                )
                            }
                        }
                        out
                    }
                }
                val pages = fitJpegsToTotal(rendered, target)
                val out = repo.createDocument("$baseName (compressed)", pages)
                sheet?.dismiss()
                host.refreshList()
                showCompressResult(out, before, out.pdfFile.length(),
                    if (out.pdfFile.length() > target) {
                        activity.getString(R.string.compress_target_missed, StatusLine.formatBytes(target))
                    } else null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                sheet?.dismiss()
                throw e
            } catch (e: Exception) {
                sheet?.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Exact-target compression helpers (PDF ≈ Σ page JPEGs + ~600 B/page)
    // ------------------------------------------------------------------

    private fun fitFilesToTotal(files: List<File>, target: Long): List<ByteArray> =
        SizeFit.compressPages(files, target)

    private fun fitJpegsToTotal(pages: List<ByteArray>, target: Long): List<ByteArray> =
        SizeFit.compressBytes(pages, target)

    /** Never grows a page: keeps the original bytes when they already fit the share. */
    private fun fitToShare(orig: ByteArray, share: Long): ByteArray = SizeFit.fitToShare(orig, share)
}

/**
 * v2 UI: 03_tools_hub — exact target-size compression shared by the legacy Compress flow and
 * the v2 Compress setup screen. A generated PDF is about the sum of its page JPEGs plus
 * ~600 bytes per page, and a page never grows: bytes that already fit are kept unchanged.
 */
object SizeFit {

    fun compressPages(files: List<File>, target: Long): List<ByteArray> {
        require(files.isNotEmpty()) { "No pages to compress" }
        val budget = (target - (files.size * 600L + 1000L)).coerceAtLeast(2048L)
        val sizes = files.map { it.length().coerceAtLeast(1L) }
        val sum = sizes.sum().coerceAtLeast(1L)
        return files.mapIndexed { i, file ->
            fitToShare(file.readBytes(), (budget * sizes[i] / sum).coerceAtLeast(1024L))
        }
    }

    fun compressBytes(pages: List<ByteArray>, target: Long): List<ByteArray> {
        require(pages.isNotEmpty()) { "No pages to compress" }
        val budget = (target - (pages.size * 600L + 1000L)).coerceAtLeast(2048L)
        val sum = pages.sumOf { it.size.toLong() }.coerceAtLeast(1L)
        return pages.map { orig ->
            fitToShare(orig, (budget * orig.size / sum).coerceAtLeast(1024L))
        }
    }

    fun fitToShare(orig: ByteArray, share: Long): ByteArray {
        if (orig.size.toLong() <= share) return orig
        val bmp = try {
            ImageTools.decodeSampled(orig, 3000)
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("A page could not be decoded")
        val fitted = ImageTools.compressToFit(bmp, share).bytes
        bmp.recycle()
        return if (fitted.size >= orig.size) orig else fitted
    }
}
