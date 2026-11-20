package com.scanwhirl.app.tools

import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.RadioButton
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.DialogCompressBinding
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.home.DocThumbBinder
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * test26 T2.2 — Compress tool, moved verbatim out of `MainActivity`.
 *
 * W2 (winner spec lane 2): the set sheet opens at once and says which document it is about —
 * exact name, page count and size, with a real first page only for an ordinary document. The
 * four level estimates are measured in the background and fill in row by row; the "Aim for a
 * file size" field and the Compress button work from the first frame. A late estimate from a
 * sheet for another document is discarded, never painted into the current one.
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

    /** The estimate job of this flow's sheet; cancelled the moment that sheet closes. */
    private var estimateJob: Job? = null

    /** The sheet this flow last opened. A fresh open replaces it. */
    private var activeSheet: SwSheet? = null

    /** Bumped for every sheet this flow opens; an older sheet's callbacks are ignored. */
    private var sheetSeq: Long = 0L

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
                    ) { doc -> startWith(doc) }
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
        openSheet(
            identity = CompressIdentity.of(doc),
            onChosen = { level -> runCompressScan(doc, level) },
            onTarget = { target -> runCompressScanToTarget(doc, target) },
            startEstimate = { session, onLevel, onFailed ->
                startScanEstimate(doc, session, onLevel, onFailed)
            }
        )
    }

    /**
     * W2: the set sheet. Built and shown before a single estimate runs: identity first, then
     * the two aims, the four level rows (each starting as "estimating…") and the target field.
     *
     * [startEstimate] is called after the sheet is on screen and reports each level separately
     * through its callbacks; both callbacks are ignored once this sheet is no longer the active
     * one, so a slow estimate can never land in a sheet for another document.
     */
    private fun openSheet(
        identity: CompressIdentity,
        onChosen: (Level) -> Unit,
        onTarget: (Long) -> Unit,
        startEstimate: (Long, (Int, Long?) -> Unit, () -> Unit) -> Unit
    ) {
        val dialogBinding = DialogCompressBinding.inflate(activity.layoutInflater)
        bindIdentity(dialogBinding, identity)
        // A new sheet replaces any earlier one in this flow: its estimates are no longer wanted.
        estimateJob?.cancel()
        estimateJob = null

        val levelNames = levels.map { activity.getString(it.nameRes) }
        val rows = levels.indices.map { index ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                text = activity.getString(R.string.compress_est_pending, levelNames[index])
                // B2-M3 (device sweep 2026-11-19): a bare RadioButton was a 32dp-high row.
                // The rule is 48dp minimum on every touch target; the visual density stays,
                // the row just gets breathing room around the same text.
                minHeight = SwStyle.dp(activity, 48f)
                gravity = Gravity.CENTER_VERTICAL
                val padV = SwStyle.dp(activity, 8f)
                setPadding(SwStyle.dp(activity, 4f), padV, SwStyle.dp(activity, 4f), padV)
            }
        }
        rows.forEach { dialogBinding.levelGroup.addView(it) }
        (dialogBinding.levelGroup.getChildAt(1) as? RadioButton)?.isChecked = true

        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.compress_level_title)
            .setContent(dialogBinding.root)
            .setSecondary(android.R.string.cancel)
            .build()
        val session = ++sheetSeq
        activeSheet = sheet

        // B3: the job runs at most once per sheet, however it is started.
        var submitted = false
        val measured = BooleanArray(levels.size)

        fun showError(res: Int) {
            dialogBinding.compressTargetError.text = activity.getString(res)
            dialogBinding.compressTargetError.isVisible = true
        }

        fun clearError() {
            dialogBinding.compressTargetError.text = null
            dialogBinding.compressTargetError.isVisible = false
        }

        fun targetMode(): Boolean =
            dialogBinding.compressModeGroup.checkedRadioButtonId == R.id.compressModeTarget

        fun applyMode() {
            val toSize = targetMode()
            dialogBinding.levelGroup.isVisible = !toSize
            dialogBinding.compressTargetBlock.isVisible = toSize
            clearError()
        }

        /** True while this sheet is still the one on screen (never a replaced or closed one). */
        fun current(): Boolean = session == sheetSeq && activeSheet?.isShowing == true

        fun showEstimate(index: Int, bytes: Long?) {
            if (!current()) return
            measured[index] = true
            rows[index].text = if (bytes == null) {
                activity.getString(R.string.compress_est_failed, levelNames[index])
            } else {
                activity.getString(
                    R.string.compress_est_item,
                    levelNames[index],
                    StatusLine.formatBytes(bytes)
                )
            }
        }

        fun showFailedEstimates() {
            if (!current()) return
            rows.indices.forEach { index ->
                if (!measured[index]) {
                    rows[index].text = activity.getString(
                        R.string.compress_est_failed, levelNames[index]
                    )
                }
            }
        }

        fun submit() {
            if (submitted) return
            if (!targetMode()) {
                submitted = true
                val checked = dialogBinding.levelGroup.checkedRadioButtonId
                val index = dialogBinding.levelGroup.indexOfChild(
                    dialogBinding.levelGroup.findViewById(checked)
                )
                sheet.dismiss()
                onChosen(levels.getOrElse(if (index >= 0) index else 1) { levels[1] })
                return
            }
            when (
                val parsed = CompressTarget.parse(
                    dialogBinding.editCompressTarget.text?.toString()
                )
            ) {
                CompressTarget.Result.Blank -> showError(R.string.compress_target_required)
                CompressTarget.Result.Invalid -> showError(R.string.resize_invalid_target)
                CompressTarget.Result.Zero -> showError(R.string.compress_target_zero)
                CompressTarget.Result.TooLarge -> showError(R.string.compress_target_too_large)
                is CompressTarget.Result.Bytes -> {
                    submitted = true
                    sheet.dismiss()
                    onTarget(parsed.value)
                }
            }
        }

        dialogBinding.compressModeGroup.setOnCheckedChangeListener { _, _ -> applyMode() }
        dialogBinding.editCompressTarget.addTextChangedListener(object :
            android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = clearError()
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        dialogBinding.editCompressTarget.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                // A Done that did not run (an invalid size) stays in the field to be fixed.
                true
            } else {
                false
            }
        }
        // The keyboard's Done is the same action as the button; it never starts a second run.
        dialogBinding.editCompressTarget.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                submit()
                true
            } else {
                false
            }
        }
        // B3: with the keyboard up the sheet resizes and scrolls the field into view instead of
        // hiding it behind the IME at 200% type.
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialogBinding.editCompressTarget.setOnFocusChangeListener { _, focused ->
            if (focused) {
                dialogBinding.compressScroll.post {
                    val rect = android.graphics.Rect()
                    dialogBinding.editCompressTarget.getDrawingRect(rect)
                    dialogBinding.compressScroll.requestChildRectangleOnScreen(
                        dialogBinding.editCompressTarget, rect, false
                    )
                }
            }
        }
        sheet.setOnDismissListener {
            // Closing the sheet ends its estimates too: nothing may paint or run afterwards.
            estimateJob?.cancel()
            estimateJob = null
            if (activeSheet === sheet) activeSheet = null
        }
        applyMode()
        sheet.setPrimaryStayOpen(R.string.compress_action) { submit() }
        sheet.show()
        startEstimate(
            session,
            { index, bytes -> showEstimate(index, bytes) },
            { showFailedEstimates() }
        )
    }

    /** W2: the exact source, before any measurement. */
    private fun bindIdentity(binding: DialogCompressBinding, identity: CompressIdentity) {
        binding.compressSourceName.text = identity.name
        binding.compressSourceMeta.text = activity.resources.getQuantityString(
            R.plurals.page_count_and_date,
            identity.pageCount,
            identity.pageCount,
            StatusLine.formatBytes(identity.sizeBytes)
        )
        val doc = identity.doc
        if (doc != null) {
            // DocThumbBinder is the app's one privacy decision: page image only for an ordinary
            // document, a lock/private placeholder for a locked or Vault document.
            DocThumbBinder.bind(
                binding.compressSourceThumb,
                binding.compressSourceThumbGlyph,
                doc
            )
        } else {
            binding.compressSourceThumb.setImageResource(R.drawable.bg_thumb_vault)
            binding.compressSourceThumbGlyph.setImageResource(R.drawable.ic_sw_pdf_file)
            binding.compressSourceThumbGlyph.isVisible = true
        }
    }

    /**
     * Measures each level on two sample pages and reports it as soon as it is known. The seam
     * ([estimateForTest]) lets a test hold a level back or fail it without touching the UI.
     */
    private fun startScanEstimate(
        doc: ScanDocument,
        session: Long,
        onLevel: (Int, Long?) -> Unit,
        onFailed: () -> Unit
    ) {
        val seam = estimateForTest
        estimateJob = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (seam != null) {
                        for (index in levels.indices) {
                            if (session != sheetSeq) return@withContext
                            val bytes = runCatching { seam.invoke(doc, index) }.getOrNull()
                            withContext(Dispatchers.Main) { onLevel(index, bytes) }
                        }
                        return@withContext
                    }
                    val samples = listOfNotNull(
                        doc.pageFiles.firstOrNull(),
                        doc.pageFiles.getOrNull(doc.pageFiles.size / 2)
                    ).distinct().map { it.readBytes() }
                    if (samples.isEmpty()) {
                        withContext(Dispatchers.Main) { onFailed() }
                        return@withContext
                    }
                    levels.forEachIndexed { index, level ->
                        if (session != sheetSeq) return@withContext
                        currentCoroutineContext().ensureActive()
                        val average = samples.map {
                            ImageTools.recompressJpeg(it, level.quality, level.maxDim)
                                .size.toLong()
                        }.average()
                        val bytes = (average * doc.pageCount * 1.02 + 1024).toLong()
                        withContext(Dispatchers.Main) { onLevel(index, bytes) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailed() }
            }
        }
    }

    /** Renders page 1 of an external PDF at each level, one level at a time, and reports it. */
    private fun startPdfEstimate(
        uri: Uri,
        pageIndex: Int,
        pages: Int,
        session: Long,
        onLevel: (Int, Long?) -> Unit,
        onFailed: () -> Unit
    ) {
        estimateJob = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // One seekable copy: SAF pipes cannot be rendered directly.
                    PdfPageRenderer.withSeekablePdf(activity, uri) { fd ->
                        for (index in levels.indices) {
                            if (session != sheetSeq) return@withSeekablePdf
                            currentCoroutineContext().ensureActive()
                            val level = levels[index]
                            val rendered = runCatching {
                                PdfPageRenderer.renderPageJpeg(
                                    fd, pageIndex, level.dpi,
                                    level.maxDim ?: 4096, level.quality
                                )
                            }.getOrNull()
                            val bytes = rendered?.let {
                                (it.size.toDouble() * pages * 1.02 + 1024).toLong()
                            }
                            withContext(Dispatchers.Main) { onLevel(index, bytes) }
                            if (bytes == null) {
                                // The same render failed: the remaining levels cannot be sized.
                                for (rest in index + 1 until levels.size) {
                                    withContext(Dispatchers.Main) { onLevel(rest, null) }
                                }
                                return@withSeekablePdf
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFailed() }
            }
        }
    }

    private fun runCompressScan(doc: ScanDocument, level: Level) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = if (doc.pdfFile.exists()) doc.pdfFile.length() else 0L
                val out = repo.compressScan(doc.id, level.quality, level.maxDim)
                val after = if (out.pdfFile.exists()) out.pdfFile.length() else 0L
                host.refreshList()
                showCompressResult(out, before, after, sourceKept = true)
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
                showCompressResult(out, before, out.pdfFile.length(), target, sourceKept = true)
            } catch (e: Exception) {
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, e.message), Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /**
     * B2-M2 (device sweep 2026-11-19): every Compress result now ends on the same
     * [ToolResultActivity] as the Tools hub — Open, Also save in Documents, Share, Save as…,
     * Show in library and Undo — instead of the old export screen that offered only
     * "Save to Documents" and no way back.
     *
     * The result names the NEW compressed document only. The source is never passed as a doc id,
     * so Undo (which purges the documents it names) can remove exactly the copy this run made and
     * never the document the person compressed. [target] is the exact size asked for, so the
     * result can still say honestly when that size was not reached.
     */
    private fun showCompressResult(
        out: ScanDocument,
        before: Long,
        after: Long,
        target: Long? = null,
        sourceKept: Boolean = false
    ) {
        val missed = target != null && after > target
        val note = when {
            // The result's own target line says this, so it is not repeated as a note.
            missed -> null
            before > 0 && after > before -> activity.getString(R.string.compress_grew_warning)
            else -> null
        }
        activity.startActivity(
            ToolResultActivity.intent(
                activity,
                toolId = "compress",
                docIds = listOf(out.id), // the new copy only: Undo never touches the source
                targetBytes = target,
                note = note,
                beforeBytes = before,
                sourceKept = sourceKept
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
                val pages = devicePages.orEmpty()
                val name = withContext(Dispatchers.IO) { FlowUtil.devicePdfName(repo, uri) }
                val size = withContext(Dispatchers.IO) {
                    runCatching {
                        activity.contentResolver.openFileDescriptor(uri, "r")
                            ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                    }.getOrDefault(0L)
                }
                openSheet(
                    identity = CompressIdentity.ofPdf(name, pages.size, size),
                    onChosen = { level -> runCompressFile(uri, level) },
                    onTarget = { target -> runCompressFileToTarget(uri, target) },
                    startEstimate = { session, onLevel, onFailed ->
                        startPdfEstimate(
                            uri, pages.firstOrNull() ?: 0, pages.size,
                            session, onLevel, onFailed
                        )
                    }
                )
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
                showCompressResult(out, before, out.pdfFile.length(), target)
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

    internal companion object {

        /**
         * Test seam: measures one level of one scan. Throw (or return null) to fail a level;
         * block to hold it back. Null means the real two-sample measurement runs.
         */
        @VisibleForTesting
        internal var estimateForTest: ((ScanDocument, Int) -> Long?)? = null
    }
}

/**
 * W2: what the set sheet says the job is about, before a single estimate runs. The page image is
 * allowed only for an ordinary document — [DocThumbBinder.kindFor] decides, so a locked, Vault or
 * PDF-only source keeps a placeholder.
 */
internal data class CompressIdentity(
    val name: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val thumbKind: DocThumbBinder.Kind,
    val doc: ScanDocument? = null
) {

    /** A real first page only when the document itself may show one. */
    val showsPageImage: Boolean get() = thumbKind == DocThumbBinder.Kind.PAGE

    companion object {

        internal fun of(doc: ScanDocument) = CompressIdentity(
            name = doc.name,
            pageCount = doc.pageCount,
            sizeBytes = sizeOf(doc),
            thumbKind = DocThumbBinder.kindFor(doc),
            doc = doc
        )

        internal fun ofPdf(name: String, pageCount: Int, sizeBytes: Long) = CompressIdentity(
            name = name,
            pageCount = pageCount,
            sizeBytes = sizeBytes,
            thumbKind = DocThumbBinder.Kind.PDF_ONLY
        )

        /** The PDF's bytes when it exists, otherwise its pages' bytes. Never a guess. */
        internal fun sizeOf(doc: ScanDocument): Long =
            if (doc.pdfFile.exists() && doc.pdfFile.length() > 0) doc.pdfFile.length()
            else doc.pageFiles.sumOf { it.length() }
    }
}

/**
 * W2: a typed "aim for a size" parsed without ever wrapping. Blank, unreadable, zero and
 * overflowing sizes are each rejected and named inline; a huge number can never silently become
 * a tiny target (the shared [ImageTools.parseSizeInput] rounds through Int).
 */
internal object CompressTarget {

    /** Above 2 GB a "compress to this size" is a mistake, not a target worth chasing. */
    const val MAX_BYTES = 2L * 1024L * 1024L * 1024L

    sealed class Result {
        object Blank : Result()
        object Invalid : Result()
        object Zero : Result()
        object TooLarge : Result()
        data class Bytes(val value: Long) : Result()
    }

    private val SIZE = Regex("(\\d+(?:\\.\\d+)?)(kb|mb|k|m|b)?")

    fun parse(text: String?): Result {
        val cleaned = text?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        if (cleaned.isEmpty()) return Result.Blank
        val match = SIZE.matchEntire(cleaned) ?: return Result.Invalid
        val number = match.groupValues[1].toDoubleOrNull() ?: return Result.Invalid
        if (!number.isFinite()) return Result.TooLarge
        val bytes = when (match.groupValues[2]) {
            "mb", "m" -> number * 1024.0 * 1024.0
            "kb", "k" -> number * 1024.0
            "b" -> number
            else -> number * 1024.0 // bare number = KB
        }
        if (!bytes.isFinite() || bytes > MAX_BYTES.toDouble()) return Result.TooLarge
        val whole = bytes.toLong()
        return if (whole < 1L) Result.Zero else Result.Bytes(whole)
    }
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
