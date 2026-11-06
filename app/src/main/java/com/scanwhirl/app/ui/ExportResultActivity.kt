package com.scanwhirl.app.ui

import android.Manifest
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
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Shows the real output of Resize/Compress: a preview, actual before → after
 * sizes (plus dimensions for images), then Save (to Downloads/Documents) or
 * Share. Replaces the old "toast + forced share sheet" behaviour.
 */
class ExportResultActivity : BaseActivity() {

    private lateinit var binding: ActivityExportResultBinding
    private var outputPath: String? = null
    private var mime = "image/jpeg"
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
        binding.toolbar.setNavigationOnClickListener { finish() }

        outputPath = intent.getStringExtra(EXTRA_OUTPUT)
        mime = intent.getStringExtra(EXTRA_MIME) ?: "image/jpeg"
        displayName = intent.getStringExtra(EXTRA_NAME) ?: "ScanWhirl"
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
            val bitmap = ImageTools.decodeSampled(File(path).readBytes(), 1600)
            binding.resultPreview.setImageBitmap(bitmap)
        } catch (_: Exception) {
            binding.resultPreview.visibility = android.view.View.GONE
        }
    }

    private fun showStats() {
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
                    val finalName = withContext(Dispatchers.IO) {
                        if (isPdf) {
                            DownloadSaver.savePdf(
                                this@ExportResultActivity, file.readBytes(), displayName
                            )
                        } else {
                            // Resized images get their own folder in Downloads.
                            DownloadSaver.saveJpeg(
                                this@ExportResultActivity, file.readBytes(), displayName,
                                subdir = "Resize"
                            )
                        }
                    }
                    Toast.makeText(
                        this@ExportResultActivity,
                        getString(
                            if (isPdf) R.string.result_saved_documents
                            else R.string.result_saved_downloads,
                            finalName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.result_share)))
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.result_save_failed, e.message), Toast.LENGTH_LONG
            ).show()
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

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val EXTRA_OUTPUT = "output_path"
        private const val EXTRA_PREVIEW = "preview_path"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_BEFORE = "before_bytes"
        private const val EXTRA_AFTER = "after_bytes"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_WARNING = "warning"

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
            warning: String?
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
    }
}
