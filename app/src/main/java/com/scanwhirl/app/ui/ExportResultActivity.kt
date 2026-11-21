package com.scanwhirl.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivityExportResultBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.SizeUnits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows the real output of Resize/Compress: a preview, actual before → after
 * sizes (plus dimensions for images), then Save (to Downloads/Documents) or
 * Share. Replaces the old "toast + forced share sheet" behaviour.
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

    private var pendingSave: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingSave?.invoke()
            else Toast.makeText(this, R.string.storage_perm_needed, Toast.LENGTH_LONG).show()
            pendingSave = null
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
        // SECURITY_JUDGE 1.3.0: a protected copy's preview is plaintext in a new
        // window; resolve the document's own policy from the id before showing it.
        com.scanwhirl.app.util.SecureView.applyForDocumentId(this, window, docId)
        beforeBytes = intent.getLongExtra(EXTRA_BEFORE, 0L)
        afterBytes = intent.getLongExtra(EXTRA_AFTER, 0L)
        isPdf = mime == "application/pdf"

        val title = intent.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) binding.toolbar.title = title

        loadPreview()
        showStats()

        intent.getStringExtra(EXTRA_WARNING)?.takeIf { it.isNotBlank() }?.let {
            binding.resultWarning.text = it
            binding.resultWarning.visibility = android.view.View.VISIBLE
        }

        binding.btnSaveResult.setText(
            if (isPdf) R.string.result_save_documents else R.string.result_save_downloads
        )
        binding.btnSaveResult.setOnClickListener { saveResult() }
        binding.btnShareResult.setOnClickListener { shareResult() }
    }

    private fun loadPreview() {
        val path = intent.getStringExtra(EXTRA_PREVIEW) ?: outputPath
        if (path == null) {
            binding.resultPreview.visibility = android.view.View.GONE
            return
        }
        try {
            val bitmap = ImageTools.decodeSampledFile(File(path), 1600)
            binding.resultPreview.setImageBitmap(bitmap)
        } catch (_: Exception) {
            binding.resultPreview.visibility = android.view.View.GONE
        }
    }

    private fun showStats() {
        // Protect PDF has no meaningful before/after size (the source had no PDF yet); it
        // passes a summary instead of the comparison.
        val summary = intent.getStringExtra(EXTRA_SUMMARY)
        if (!summary.isNullOrBlank()) {
            binding.resultStats.text = summary
            return
        }
        val text = getString(
            R.string.result_before_after,
            formatBytes(beforeBytes),
            formatBytes(afterBytes)
        )
        binding.resultStats.text = text
        val w = intent.getIntExtra(EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(EXTRA_HEIGHT, 0)
        if (w > 0 && h > 0) {
            binding.resultStats.append("\n" + getString(R.string.result_dimensions, w, h))
        }
    }

    private fun saveResult() {
        val file = outputPath?.let { File(it) } ?: return
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, R.string.resize_failed, Toast.LENGTH_LONG).show()
            return
        }
        withStoragePermission {
            lifecycleScope.launch {
                try {
                    if (isPdf) {
                        // M06 export transaction (covers the Compress / Lock / Unlock
                        // flows that land here): streamed from the result file, never
                        // overwrites an earlier copy; identical re-save → "Already saved".
                        val saved = withContext(Dispatchers.IO) {
                            DownloadSaver.savePdfFile(
                                this@ExportResultActivity, file, displayName
                            ).also {
                                // T4.8: remember the copy so delete can remove it. A failure
                                // to record it must not turn a finished export into a failure.
                                docId?.let { id ->
                                    try {
                                        ScanRepository(this@ExportResultActivity)
                                            .addExport(id, it)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        SavedSnackbar.showExport(
                            this@ExportResultActivity,
                            binding.root,
                            saved,
                            R.string.result_saved_documents,
                            docId
                        )
                    } else {
                        // Resized images get their own folder in Downloads (streamed, always a new file).
                        val finalName = withContext(Dispatchers.IO) {
                            DownloadSaver.saveJpeg(
                                this@ExportResultActivity, file, displayName,
                                subdir = "Resize"
                            )
                        }
                        Toast.makeText(
                            this@ExportResultActivity,
                            getString(R.string.result_saved_downloads, finalName),
                            Toast.LENGTH_LONG
                        ).show()
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
    }

    private fun shareResult() {
        val file = outputPath?.let { File(it) } ?: return
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                // Android 12+ reads the read grant from ClipData when the sharesheet
                // previews the file; EXTRA_STREAM alone logs a Permission Denial.
                // Keep the same URI and MIME on both so the grant survives the
                // chooser hop (same contract as ShareUtils, without calling it).
                clipData = ClipData(
                    ClipDescription(file.name, arrayOf(mime)),
                    ClipData.Item(uri)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.result_share)))
        } catch (e: Exception) {
            // A JVM message is diagnostics, not user-facing text.
            Toast.makeText(this, R.string.p1_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun withStoragePermission(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingSave = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** CP1: the one decimal formatter; the old local copy counted 1024-byte KB. */
    private fun formatBytes(bytes: Long): String = SizeUnits.format(bytes)

    companion object {
        private const val EXTRA_OUTPUT = "output_path"
        private const val EXTRA_PREVIEW = "preview_path"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_BEFORE = "before_bytes"
        private const val EXTRA_AFTER = "after_bytes"
        private const val EXTRA_SUMMARY = "result_summary"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_WARNING = "warning"

        /** T4.8: the document whose meta.txt should record the saved copy. */
        private const val EXTRA_DOC_ID = "doc_id"

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
            /** When set, replaces the before/after comparison (e.g. Protect PDF). */
            summary: String? = null
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
    }
}
