package com.scanwhirl.app.tools

import android.graphics.Rect
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
import com.scanwhirl.app.util.SizeUnits
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

    // L3-03: the engine numbers are intentionally unchanged — CP1's best tier
    // still caps at 3000 px. The literal resource names stay here because a
    // long-standing source-contract test reads them verbatim; the real
    // single-source-of-truth check below fails fast if the order ever drifts
    // from the shared table used by ToolSetupActivity.
    private val levels = listOf(
        Level(R.string.compress_level_low, 80, 3000, 200f),
        Level(R.string.compress_level_medium, 65, 2000, 150f),
        Level(R.string.compress_level_high, 50, 1600, 130f),
        Level(R.string.compress_level_ultra, 38, 1200, 110f)
    ).also { declared ->
        require(declared.map { it.nameRes } == CompressionPresentation.levelLabels) {
            "Compression levels drifted from the shared presentation table"
        }
    }

    /** The pages chosen in the device-PDF gate (null until a device PDF is picked). */
    private var devicePages: List<Int>? = null

    /** Total pages in the picked device PDF, for the honest "N pages of M" before line (CP1). */
    private var devicePageCount: Int = 0

    /** The estimate job of this flow's sheet; cancelled the moment that sheet closes. */
    private var estimateJob: Job? = null

    /** The sheet this flow last opened. A fresh open replaces it. */
    private var activeSheet: SwSheet? = null

    /** Bumped for every sheet this flow opens; an older sheet's callbacks are ignored. */
    private var sheetSeq: Long = 0L

    fun start() {
        devicePages = null
        val candidates = docs()
        // No saved scans yet: the source is necessarily an external file.
        if (candidates.isEmpty()) {
            pickPdf()
            return
        }
        // L3-03: one combined library picker. The last row is the honest route to a
        // phone file, instead of a source sheet followed by a second picker.
        val labels = candidates.map { it.name } +
            activity.getString(CompressionPresentation.fromPhoneStorage)
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.compress_pick_scan)
            .setOptions(labels) { which, _ ->
                if (which == candidates.size) {
                    pickPdf()
                } else {
                    candidates.getOrNull(which)?.let { startWith(it) }
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
            .setScrollableContent(dialogBinding.root)
            .setSecondary(android.R.string.cancel)
            .build()
        val session = ++sheetSeq
        activeSheet = sheet

        // B3: the job runs at most once per sheet, however it is started.
        var submitted = false
        val measured = BooleanArray(levels.size)

        fun showError(res: Int) {
            dialogBinding.compressTargetError.apply {
                text = activity.getString(res)
                isVisible = true
                post { if (isShown) requestRectangleOnScreen(Rect(0, 0, width, height)) }
            }
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
        dialogBinding.editCompressTarget.setOnFocusChangeListener { field, focused ->
            if (focused) {
                field.post {
                    field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height))
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
        sheet.setPrimaryStayOpen(R.string.compress_action, iconRes = R.drawable.ic_sym_compress) { submit() }
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
                        // CP1: the shared measured-overhead model, the same one the hub uses.
                        // The sheet says "about"; the real size follows the run.
                        val bytes = SizeUnits.estimateJpegPages(
                            average, doc.pageCount, doc.pageFiles.size
                        )
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
                        // PERF item 4: one native renderer for all quality levels.
                        PdfPageRenderer.withRendererSuspend(fd) { renderer ->
                            for (index in levels.indices) {
                                if (session != sheetSeq) return@withRendererSuspend
                                currentCoroutineContext().ensureActive()
                                val level = levels[index]
                                val rendered = runCatching {
                                    PdfPageRenderer.renderPageJpeg(
                                        renderer, pageIndex, level.dpi,
                                        level.maxDim ?: 3000, level.quality
                                    )
                                }.getOrNull()
                                val bytes = rendered?.let {
                                    SizeUnits.estimateJpegPages(it.size.toDouble(), pages)
                                }
                                withContext(Dispatchers.Main) { onLevel(index, bytes) }
                                if (bytes == null) {
                                    // The same render failed: the remaining levels cannot be sized.
                                    for (rest in index + 1 until levels.size) {
                                        withContext(Dispatchers.Main) { onLevel(rest, null) }
                                    }
                                    return@withRendererSuspend
                                }
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
                showCompressResult(out, before, after, sourceKept = true,
                    receiptSourceId = doc.id, outputIdentity = outputIdentity(out))
            } catch (e: CancellationException) {
                // H5-3: cancellation (screen closed or recreated) is not a failure.
                throw e
            } catch (e: Throwable) {
                // CP1: an OutOfMemoryError is an Error. It still needs a localized message and a
                // dismissed progress sheet, never a crash with "Compression failed:" in English.
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, failureReason(e)), Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    /**
     * Compresses a saved scan to an exact target with a determinate "page k of N" sheet and Cancel.
     * The repository writes each page into private staging and only publishes after every page
     * succeeds, so Cancel (or an OOM) leaves no partial document and never touches the source.
     */
    private fun runCompressScanToTarget(doc: ScanDocument, target: Long) {
        var job: Job? = null
        val progress = com.scanwhirl.app.ui.kit.SwProgress.show(
            activity, activity.getString(R.string.progress_title), total = doc.pageCount
        ) { job?.cancel() }
        job = activity.lifecycleScope.launch {
            try {
                val before = CompressIdentity.sizeOf(doc)
                // F11: streaming, one page at a time. F11 (H3-5): recognised text is carried over.
                val out = repo.compressScanToTarget(doc.id, target, onPage = { done, total ->
                    activity.runOnUiThread { progress.setProgress(done, total) }
                })
                host.refreshList()
                showCompressResult(out, before, out.pdfFile.length(), target, sourceKept = true,
                    receiptSourceId = doc.id, outputIdentity = outputIdentity(out))
            } catch (e: CancellationException) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity, activity.getString(R.string.cp1_compress_cancelled), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, failureReason(e)), Toast.LENGTH_LONG
                ).show()
            } finally {
                activity.runOnUiThread { progress.dismiss() }
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
    /** L3-02 P1: hash the new document on IO, never on the Main thread. */
    private suspend fun outputIdentity(out: ScanDocument): String? = withContext(Dispatchers.IO) {
        com.scanwhirl.app.ui.tools.ToolResultActivity.captureOutputIdentityBlocking(
            activity, listOf(out.id), emptyList()
        )
    }

    private fun showCompressResult(
        out: ScanDocument,
        before: Long,
        after: Long,
        target: Long? = null,
        sourceKept: Boolean = false,
        /** Honest "Before: N pages of M (X MB)" for a picked PDF's selected pages. */
        beforeLine: String? = null,
        /** L3-02 P2: library source used only to derive the "original kept" receipt from state. */
        receiptSourceId: String? = null,
        /** L3-02 P1: identity captured by the caller on Dispatchers.IO. */
        outputIdentity: String? = null
    ) {
        val missed = target != null && after > target
        val grew = before > 0L && after > before
        val notes = buildList<String> {
            beforeLine?.let { add(it) }
            if (target != null && after > target) {
                // CP1: the readable floor means we stop instead of shredding the text. Say the miss
                // and the smallest readable size; never present an unreadable page as success.
                add(
                    activity.getString(
                        R.string.cp1_compress_floor_miss,
                        StatusLine.formatBytes(target),
                        StatusLine.formatBytes(after)
                    )
                )
            }
            if (grew) add(activity.getString(R.string.compress_grew_warning))
        }
        val note = notes.joinToString("\n").takeIf { it.isNotBlank() }
        activity.startActivity(
            ToolResultActivity.intent(
                activity,
                toolId = "compress",
                docIds = listOf(out.id), // the new copy only: Undo never touches the source
                targetBytes = target,
                note = note,
                // A subset line replaces the misleading whole-file "Before: X MB".
                beforeBytes = if (beforeLine == null) before else null,
                sourceKept = sourceKept,
                compressTargetMissed = missed,
                compressGrew = grew,
                receiptSourceId = receiptSourceId,
                outputIdentity = outputIdentity
            )
        )
    }

    fun startFromFile(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { repo.inspectDevicePdf(uri, 2048) }
                val decision = com.scanwhirl.app.ui.tools.DevicePdfGate.decide(activity, plan)
                if (decision == ScanRepository.ImportDecision.Cancel) return@launch
                devicePageCount = plan.pageCount
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
            } catch (e: CancellationException) {
                // H5-3: cancellation (screen closed or recreated) is not a failure.
                throw e
            } catch (e: Throwable) {
                // CP1: inspecting a device PDF can OOM too; report it with a localized reason.
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, failureReason(e)), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * CP1 (defect 6): a picked PDF's whole-file size is not the "before" for a page subset. The
     * result states how many of the PDF's pages were actually compressed and the whole file's size
     * as context, so a 2-of-20 selection can never look like it shrank a 20-page file.
     */
    private fun deviceBeforeLine(wholeBytes: Long): String? {
        val selected = devicePages?.size ?: return null
        val total = devicePageCount.takeIf { it > 0 } ?: return null
        return activity.getString(
            R.string.cp1_compress_before_subset,
            selected, total, StatusLine.formatBytes(wholeBytes)
        )
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
                    uri, name, level.dpi, level.maxDim ?: 3000, level.quality,
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
                showCompressResult(out, before, after, beforeLine = deviceBeforeLine(before),
                    outputIdentity = outputIdentity(out))
            } catch (e: CancellationException) {
                sheet?.dismiss()
                activity.runOnUiThread {
                    Toast.makeText(
                        activity, activity.getString(R.string.cp1_compress_cancelled), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                sheet?.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, failureReason(e)), Toast.LENGTH_LONG
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
                // F11 (H3-2): render into private staging one page at a time and re-fit each page
                // from disk; the old code collected every rendered JPEG in memory first.
                val out = repo.importPdfToTarget(
                    uri, baseName, indices, target,
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
                host.refreshList()
                showCompressResult(
                    out, before, out.pdfFile.length(), target,
                    beforeLine = deviceBeforeLine(before),
                    outputIdentity = outputIdentity(out)
                )
            } catch (e: CancellationException) {
                sheet?.dismiss()
                activity.runOnUiThread {
                    Toast.makeText(
                        activity, activity.getString(R.string.cp1_compress_cancelled), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                sheet?.dismiss()
                Toast.makeText(
                    activity, activity.getString(R.string.compress_failed, failureReason(e)), Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Exact-target compression helpers (PDF ≈ Σ page JPEGs + ~600 B/page)
    // ------------------------------------------------------------------

    /**
     * H2-5: the toast keeps its localized prefix and names a localized reason. The raw
     * exception message (English system/JVM text) is never shown.
     */
    private fun failureReason(e: Throwable): String = activity.getString(
        if (ImageTools.isTooLargePhoto(e)) R.string.f8_reason_too_large
        else R.string.f8_reason_unreadable
    )

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
    const val MAX_BYTES = SizeUnits.MAX_BYTES

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
        // CP1: decimal units. "500" and "500 KB" both mean 500,000 bytes, so a result that fits
        // this target also fits a portal counting a KB as 1024 bytes. MAX_BYTES deliberately stays
        // the shared 2 GiB ceiling used by ImageTools.parseSizeInput (the hub's parser).
        val bytes = when (match.groupValues[2]) {
            "mb", "m" -> number * SizeUnits.BYTES_PER_MB
            "kb", "k" -> number * SizeUnits.BYTES_PER_KB
            "b" -> number
            else -> number * SizeUnits.BYTES_PER_KB // bare number = KB (the hint says so)
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
 *
 * F11 (H3-2): the two list helpers below hold every page at once, so no UI path uses them any
 * more — Compress runs through `ScanRepository.compressScanToTarget`/`importPdfToTarget`, which
 * stream one page at a time. They stay as measured references for the perf harness.
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

    fun fitToShare(
        orig: ByteArray,
        share: Long,
        isCancelled: () -> Boolean = { false }
    ): ByteArray {
        if (orig.size.toLong() <= share) return orig
        val bmp = try {
            // CP1: the same 3000 px decode ceiling as import; never feed a huge page to the encoder.
            ImageTools.decodeSampled(orig, 3000)
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("A page could not be decoded")
        val fitted = try {
            ImageTools.compressToFit(
                bmp, share,
                minLongSide = ImageTools.MIN_READABLE_LONG_SIDE,
                isCancelled = isCancelled
            ).bytes
        } finally {
            bmp.recycle()
        }
        return if (fitted.size >= orig.size) orig else fitted
    }
}
