package com.scanwhirl.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityExportResultBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.CompareMode
import com.scanwhirl.app.ui.kit.CompareModel
import com.scanwhirl.app.ui.kit.ComparePane
import com.scanwhirl.app.ui.kit.CompareView
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.SizeUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B2: ActivityResultRegistry re-delivers a pending result to the recreated screen, but a Kotlin
 * lambda cannot survive a Bundle. A screen that is waiting on a result therefore persists a small
 * token — what to do and the output path/target id, never document text or secrets — and rebuilds
 * the pending action in onCreate, before the result arrives on ON_START. Mirrors the
 * GalleryCrop.saveState/restoreState pattern in BaseActivity.
 */
internal object PendingSaveAction {
    private const val KEY_KIND = "sw_pending_save_kind"
    private const val KEY_TARGET = "sw_pending_save_target"

    internal fun save(outState: Bundle, kind: String?, target: String?) {
        if (kind == null) return
        outState.putString(KEY_KIND, kind)
        outState.putString(KEY_TARGET, target)
    }

    /** Returns the stored (kind, target) only when it belongs to this screen's action set. */
    internal fun restore(saved: Bundle?, vararg expectedKinds: String): Pair<String, String>? {
        val kind = saved?.getString(KEY_KIND) ?: return null
        if (kind !in expectedKinds) return null
        return kind to saved.getString(KEY_TARGET).orEmpty()
    }
}

/**
 * Lane RS: the compact result for a file export (Resize / Protect / Unlock). The tool name is the
 * title, one measured hero line replaces the old "Before X → After Y + stats" pile, the proof is a
 * stacked zoom pair (or nothing at all for state-only tools), and Open / Share / More are pinned.
 * A size result that did not get smaller is discarded; nothing the user owns is touched.
 */
class ExportResultActivity : BaseActivity() {

    private lateinit var binding: ActivityExportResultBinding
    private var outputPath: String? = null
    private var mime = "image/jpeg"

    /** T4.8: the in-app document this export belongs to (null = not a doc's copy). */
    private var docId: String? = null
    private var displayName = "ScanWhirl"
    private var beforeBytes = 0L
    private var afterBytes = 0L
    private var isPdf = false
    private var alreadyCompact = false

    private var pendingSave: (() -> Unit)? = null
    private var pendingSaveKind: String? = null
    private var pendingSaveTarget: String? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // B2: clear first, so a second delivery can never run the save twice.
            val action = pendingSave
            pendingSave = null
            pendingSaveKind = null
            pendingSaveTarget = null
            if (granted) action?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }

        outputPath = intent.getStringExtra(EXTRA_OUTPUT)
        mime = intent.getStringExtra(EXTRA_MIME) ?: "image/jpeg"
        displayName = intent.getStringExtra(EXTRA_NAME) ?: "ScanWhirl"
        docId = intent.getStringExtra(EXTRA_DOC_ID)
        // B2: the answer to a storage prompt can arrive on this new instance. Rebuild the
        // save from the token (output path only) and ignore a token for another output.
        PendingSaveAction.restore(savedInstanceState, ACTION_SAVE)?.let { (kind, target) ->
            if (target == outputPath) {
                pendingSaveKind = kind
                pendingSaveTarget = target
                pendingSave = { saveExport() }
            }
        }
        // SECURITY_JUDGE 1.3.0: resolve a protected document's policy before any preview.
        com.scanwhirl.app.util.SecureView.applyForDocumentId(this, window, docId)
        beforeBytes = intent.getStringExtra(EXTRA_BEFORE_PREVIEW)?.let { File(it) }
            ?.takeIf { it.isFile }?.length() ?: intent.getLongExtra(EXTRA_BEFORE, 0L)
        afterBytes = outputPath?.let { File(it) }?.takeIf { it.isFile }?.length() ?: 0L
        isPdf = mime == "application/pdf"

        val summary = intent.getStringExtra(EXTRA_SUMMARY)
        // A display screen is never a deletion authority. Rejection is a producer outcome.
        alreadyCompact = intent.getStringExtra("compare_outcome") == "NOT_SMALLER"

        binding.toolbar.title = toolName(summary)

        if (alreadyCompact) {
            renderAlreadyCompact()
        } else {
            showStats()
            bindCompare(summary)
        }
        loadPreview()

        intent.getStringExtra(EXTRA_WARNING)?.takeIf { it.isNotBlank() }?.let {
            binding.resultWarning.text = it
            binding.resultWarning.visibility = View.VISIBLE
        }

        binding.btnSaveResult.setText(
            if (isPdf) R.string.result_save_documents else R.string.result_save_downloads
        )
        binding.btnSaveResult.setOnClickListener { saveResult() }
        binding.btnShareResult.setOnClickListener { shareResult() }
        binding.btnOpenResult.setOnClickListener { if (alreadyCompact) finish() else openResult() }
        binding.btnMoreResult.setOnClickListener {
            val opening = binding.rsExportMorePanel.visibility != View.VISIBLE
            binding.rsExportMorePanel.visibility = if (opening) View.VISIBLE else View.GONE
            binding.btnMoreResult.setText(
                if (opening) R.string.tools_result_fewer_options else R.string.rs_more
            )
        }
    }

    private fun toolName(summary: String?): String {
        intent.getStringExtra(EXTRA_TOOL_ID)?.let { id ->
            ToolRegistry.byId(id)?.let { return getString(it.title) }
        }
        return when {
            summary != null -> getString(R.string.tool_protect)
            beforeBytes > 0L && afterBytes > 0L && !isPdf -> getString(R.string.tool_resize)
            else -> intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.result_title) }
        }
    }

    private fun renderAlreadyCompact() {
        binding.resultStats.text = getString(R.string.rs_already_compact_title)
        binding.resultStats.contentDescription = binding.resultStats.text
        binding.rsSavedExport.text = getString(R.string.rs_already_compact_msg)
        binding.rsChipExport.visibility = View.GONE
        binding.ccCompare.visibility = View.GONE
        binding.btnOpenResult.setText(R.string.tools_result_done)
        binding.btnShareResult.visibility = View.GONE
        binding.btnMoreResult.visibility = View.GONE
    }

    /** The legacy preview id is kept for callers/tests, but it is never a white host. */
    private fun loadPreview() {
        binding.resultPreview.visibility = View.GONE
        binding.resultPreview.background = null
    }

    private fun formatBytes(bytes: Long): String = SizeUnits.format(bytes)

    private fun pixels(width: Int, height: Int): String? =
        if (width > 0 && height > 0) getString(R.string.result_dimensions, width, height) else null

    private fun caption(size: String?, px: String?): String = listOfNotNull(size, px)
        .joinToString(" · ")
        .ifBlank { getString(R.string.cc_no_numbers) }

    /** UX_RULES_SCANNER: status by vector icon, never a glyph in shipped text. */
    private fun setStatusIcon(view: TextView, ok: Boolean) {
        val d = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            this, if (ok) com.scanwhirl.app.R.drawable.ic_sym_check
            else com.scanwhirl.app.R.drawable.ic_sym_error
        )?.mutate()
        val size = (16f * resources.displayMetrics.density).toInt()
        d?.setBounds(0, 0, size, size)
        d?.setTint(androidx.core.content.ContextCompat.getColor(
            this, if (ok) com.scanwhirl.app.R.color.rv_accent
            else com.scanwhirl.app.R.color.laser_danger
        ))
        view.setCompoundDrawablesRelative(d, null, null, null)
        view.compoundDrawablePadding = (6f * resources.displayMetrics.density).toInt()
    }

    private fun showStats() {
        val w = intent.getIntExtra(EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val summary = intent.getStringExtra(EXTRA_SUMMARY)
        if (!summary.isNullOrBlank()) {
            binding.resultStats.text = summary
            binding.rsSavedExport.text = savedLine()
            binding.rsChipExport.visibility = View.GONE
            return
        }
        if (beforeBytes <= 0L || afterBytes <= 0L) {
            binding.resultStats.text = getString(R.string.cc_outcome_unknown)
            binding.rsChipExport.visibility = View.GONE
            binding.rsSavedExport.text = savedLine()
            return
        }
        val from = formatBytes(beforeBytes)
        val to = formatBytes(afterBytes)
        binding.resultStats.text = getString(R.string.rs_size_hero, from, to)
        binding.resultStats.contentDescription = getString(
            R.string.rs_hero_a11y_size, from, to, reductionA11y(beforeBytes, afterBytes)
        )
        // UX_RULES_SCANNER: status as a short  chip with numbers, not a paragraph.
        val px = pixels(w, h)
        binding.rsChipExport.text = if (px != null) {
            getString(R.string.rs_pixels_ok, w, h)
        } else {
            reductionChip(beforeBytes, afterBytes)
        }
        setStatusIcon(binding.rsChipExport, px != null || afterBytes <= beforeBytes)
        binding.rsChipExport.visibility = View.VISIBLE
        binding.rsSavedExport.text = savedLine()
    }

    private fun savedLine(): String = getString(
        R.string.rs_saved_suffix_no_source
    ).let { suffix -> getString(R.string.rs_saved_prefix) + displayName + suffix }

    private fun reductionChip(before: Long, after: Long): String = when {
        after >= before -> getString(R.string.rs_delta_larger, formatBytes(after - before))
        formatBytes(before) == formatBytes(after) ->
            getString(R.string.rs_delta_smaller, formatBytes(before - after))
        before / after.coerceAtLeast(1L) >= 2L ->
            getString(R.string.rs_chip_ratio, before / after.coerceAtLeast(1L))
        else -> getString(
            R.string.rs_chip_percent, ((before - after) * 100 / before).toInt().coerceIn(1, 99)
        )
    }

    private fun reductionA11y(before: Long, after: Long): String = when {
        after >= before -> getString(R.string.rs_delta_larger, formatBytes(after - before))
        before / after.coerceAtLeast(1L) >= 2L ->
            getString(R.string.rs_reduction_a11y_ratio, before / after.coerceAtLeast(1L))
        else -> getString(
            R.string.rs_reduction_a11y_percent,
            ((before - after) * 100 / before).toInt().coerceIn(1, 99)
        )
    }

    private fun bindCompare(summary: String?) {
        val view: CompareView = binding.ccCompare
        val mode = when {
            alreadyCompact || !summary.isNullOrBlank() -> CompareMode.NONE
            intent.getStringExtra(EXTRA_TOOL_ID) in setOf("resize", "compress") ||
                (intent.getStringExtra(EXTRA_TOOL_ID) == null && mime.startsWith("image/") && beforeBytes > 0) ->
                CompareMode.ZOOM_STACKED
            else -> CompareMode.NONE
        }
        if (mode == CompareMode.NONE) {
            view.visibility = View.GONE
            return
        }
        val w = intent.getIntExtra(EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 0)
        val bw = intent.getIntExtra(EXTRA_BEFORE_WIDTH, 0)
        val bh = intent.getIntExtra(EXTRA_BEFORE_HEIGHT, 0)
        val afterPath = intent.getStringExtra(EXTRA_PREVIEW) ?: outputPath
        val beforePath = intent.getStringExtra(EXTRA_BEFORE_PREVIEW)
        view.visibility = View.VISIBLE
        view.bind(
            CompareModel(
                outcome = binding.resultStats.text.toString(),
                before = ComparePane(
                    imagePath = beforePath,
                    caption = caption(
                        if (beforeBytes > 0L) formatBytes(beforeBytes) else null,
                        pixels(bw, bh)
                    ),
                    emptyText = if (beforePath == null) getString(R.string.cc_no_before_image) else null
                ),
                after = ComparePane(
                    imagePath = afterPath,
                    caption = caption(
                        if (afterBytes > 0L) formatBytes(afterBytes) else null,
                        pixels(w, h)
                    ),
                    emptyText = if (afterPath == null) getString(R.string.cc_no_after_image) else null
                ),
                mode = mode
            ),
            lifecycleScope,
            canDecode = {
                docId?.let { id ->
                    ScanRepository(this).getDocument(id)?.let { !it.isLocked && !it.isVaulted }
                } ?: (docId == null)
            }
        )
    }

    private fun openResult() {
        val file = outputPath?.let { File(it) } ?: return
        if (!file.isFile || file.length() == 0L) {
            Toast.makeText(this, R.string.resize_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.p1_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveResult() {
        val file = outputPath?.let { File(it) } ?: return
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, R.string.resize_failed, Toast.LENGTH_LONG).show()
            return
        }
        withStoragePermission(ACTION_SAVE, outputPath.orEmpty()) { saveExport() }
    }

    /** B2: the save itself, separate from the permission gate so a recreated screen can re-run it. */
    private fun saveExport() {
        val file = outputPath?.let { File(it) } ?: return
        lifecycleScope.launch {
            try {
                if (isPdf) {
                    val saved = withContext(Dispatchers.IO) {
                        DownloadSaver.savePdfFile(
                            this@ExportResultActivity, file, displayName
                        ).also {
                            docId?.let { id ->
                                try {
                                    ScanRepository(this@ExportResultActivity).addExport(id, it)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    SavedSnackbar.showExport(
                        this@ExportResultActivity, binding.root, saved,
                        R.string.result_saved_documents, docId
                    )
                    binding.btnSaveResult.setText(R.string.rs_saved_documents)
                    binding.btnSaveResult.isEnabled = false
                } else {
                    val finalName = withContext(Dispatchers.IO) {
                        DownloadSaver.saveJpeg(
                            this@ExportResultActivity, file, displayName, subdir = "Resize"
                        )
                    }
                    Toast.makeText(
                        this@ExportResultActivity,
                        getString(R.string.result_saved_downloads, finalName),
                        Toast.LENGTH_LONG
                    ).show()
                    binding.btnSaveResult.setText(R.string.rs_saved_documents)
                    binding.btnSaveResult.isEnabled = false
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ExportResultActivity,
                    getString(R.string.result_save_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun shareResult() {
        val file = outputPath?.let { File(it) } ?: return
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData(
                    ClipDescription(file.name, arrayOf(mime)),
                    ClipData.Item(uri)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.result_share)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.p1_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun withStoragePermission(kind: String, target: String, action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingSave = action
            pendingSaveKind = kind
            pendingSaveTarget = target
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // B2: keep only the token. The lambda belongs to the old screen and cannot be restored.
        PendingSaveAction.save(outState, pendingSaveKind, pendingSaveTarget)
    }

    companion object {
        private const val ACTION_SAVE = "export_result_save"
        private const val EXTRA_OUTPUT = "output_path"
        private const val EXTRA_PREVIEW = "preview_path"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_BEFORE = "before_bytes"
        private const val EXTRA_AFTER = "after_bytes"
        private const val EXTRA_SUMMARY = "result_summary"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_BEFORE_PREVIEW = "before_preview_path"
        private const val EXTRA_BEFORE_WIDTH = "before_width"
        private const val EXTRA_BEFORE_HEIGHT = "before_height"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_WARNING = "warning"
        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_TOOL_ID = "tool_id"

        fun intent(
            context: Context,
            outputPath: String,
            previewPath: String?,
            mime: String,
            displayName: String,
            beforeBytes: Long,
            afterBytes: Long,
            width: Int,
            height: Int,
            title: String?,
            warning: String?,
            docId: String? = null,
            summary: String? = null,
            beforePreviewPath: String? = null,
            beforeWidth: Int = 0,
            beforeHeight: Int = 0,
            outcome: String? = null,
            toolId: String? = null
        ): Intent = Intent(context, ExportResultActivity::class.java)
            .putExtra(EXTRA_OUTPUT, outputPath)
            .putExtra(EXTRA_PREVIEW, previewPath)
            .putExtra(EXTRA_MIME, mime)
            .putExtra(EXTRA_NAME, displayName)
            .putExtra(EXTRA_BEFORE, beforeBytes)
            .putExtra(EXTRA_AFTER, afterBytes)
            .putExtra(EXTRA_WIDTH, width)
            .putExtra(EXTRA_HEIGHT, height)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_WARNING, warning)
            .putExtra(EXTRA_DOC_ID, docId)
            .putExtra(EXTRA_SUMMARY, summary)
            .putExtra(EXTRA_BEFORE_PREVIEW, beforePreviewPath)
            .putExtra(EXTRA_BEFORE_WIDTH, beforeWidth)
            .putExtra(EXTRA_BEFORE_HEIGHT, beforeHeight)
            .putExtra("compare_outcome", outcome)
            .apply { if (toolId != null) putExtra(EXTRA_TOOL_ID, toolId) }
    }
}
