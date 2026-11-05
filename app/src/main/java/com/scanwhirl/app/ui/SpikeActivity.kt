package com.scanwhirl.app.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivitySpikeBinding
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PaperScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lab screen: run the pure-Kotlin auto-crop spike against a real photo so the
 * "paper finds" quality is judged before any dependency is added.
 */
class SpikeActivity : BaseActivity() {

    private lateinit var binding: ActivitySpikeBinding
    private var pendingCapture: Uri? = null
    private var croppedPath: String? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { runScan(it) }
        }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) pendingCapture?.let { runScan(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpikeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnTake.setOnClickListener {
            val dir = File(cacheDir, "documents").apply { mkdirs() }
            val target = File(dir, "spike_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", target
            )
            pendingCapture = uri
            takePhoto.launch(uri)
        }
        binding.btnGallery.setOnClickListener {
            pickImage.launch("image/*")
        }
        binding.btnSave.setOnClickListener { openResult() }
        binding.btnSave.isEnabled = false
    }

    private fun runScan(uri: Uri) {
        val progress = progress()
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                }
                if (bytes.isEmpty()) throw IllegalStateException("empty photo")
                val crop = withContext(Dispatchers.IO) { PaperScanner.autoCrop(bytes) }
                if (crop == null) {
                    Toast.makeText(this@SpikeActivity, R.string.lab_no_paper, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val out = File(filesDir, "exports").apply { mkdirs() }
                    .let { File(it, "spike_${System.currentTimeMillis()}.jpg") }
                withContext(Dispatchers.IO) { out.writeBytes(crop.bytes) }
                croppedPath = out.path

                // Both previews are decoded small so memory stays sane.
                showPreview(binding.imgOriginal, bytes)
                showPreview(binding.imgCrop, crop.bytes)
                binding.spikeTiming.text = getString(
                    R.string.lab_timing, crop.detectMs, crop.warpMs
                )
                binding.spikeStats.text = getString(
                    R.string.lab_stats, crop.width, crop.height, bytes.size, crop.bytes.size
                )
                binding.btnSave.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(
                    this@SpikeActivity,
                    getString(R.string.resize_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }

    private fun showPreview(target: android.widget.ImageView, bytes: ByteArray) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageTools.decodeSampled(bytes, 1200)
            }
            target.setImageBitmap(bitmap)
        }
    }

    private fun openResult() {
        val path = croppedPath ?: return
        startActivity(
            ExportResultActivity.intent(
                this,
                outputPath = path,
                previewPath = path,
                mime = "image/jpeg",
                displayName = "ScanWhirl-crop.jpg",
                beforeBytes = 0,
                afterBytes = File(path).length(),
                width = 0,
                height = 0,
                title = getString(R.string.result_title),
                warning = null
            )
        )
    }

    private fun progress(): Dialog {
        val bar = ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        return AlertDialog.Builder(this)
            .setTitle(R.string.lab_processing)
            .setView(bar)
            .setCancelable(false)
            .show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SpikeActivity::class.java)
    }
}
