package com.scanwhirl.app.tools

import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.DialogResizeBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * test26 T2.1 — Resize tool, moved verbatim out of `MainActivity`.
 * Launchers stay in the Activity (they must register before STARTED); this
 * class only owns the dialogs and the work.
 */
class ResizeFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost
) {

    fun start(uri: Uri) {
        val bytes = FlowUtil.readUriBytes(activity, uri) ?: run {
            Toast.makeText(
                activity, activity.getString(R.string.resize_failed, "unreadable image"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val size = ImageTools.imageSize(bytes) ?: run {
            Toast.makeText(
                activity, activity.getString(R.string.resize_failed, "not an image"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val name = repo.displayNameOf(uri) ?: "image"
        showResizeDialog(bytes, size.first, size.second, name)
    }

    private fun showResizeDialog(bytes: ByteArray, origW: Int, origH: Int, name: String) {
        val dialogBinding = DialogResizeBinding.inflate(activity.layoutInflater)
        dialogBinding.fileInfo.text = activity.getString(
            R.string.resize_file_info, name, StatusLine.formatBytes(bytes.size.toLong()), origW, origH
        )

        val presetValues = mapOf(
            R.id.preset50 to "50 KB",
            R.id.preset100 to "100 KB",
            R.id.preset200 to "200 KB",
            R.id.preset500 to "500 KB"
        )

        // Preset chip fills the size field; typing a custom size deselects the chip.
        var suppressPreset = false
        dialogBinding.presetGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = presetValues[checkedId] ?: return@setOnCheckedChangeListener
            suppressPreset = true
            dialogBinding.editMaxSize.setText(value)
            suppressPreset = false
        }
        dialogBinding.editMaxSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressPreset) return
                val checkedId = dialogBinding.presetGroup.checkedChipId
                val text = s?.toString()?.trim().orEmpty()
                if (checkedId != -1 && presetValues[checkedId]?.equals(text, true) != true) {
                    dialogBinding.presetGroup.clearCheck()
                }
            }
        })

        dialogBinding.checkAdvanced.setOnCheckedChangeListener { _, checked ->
            dialogBinding.advancedBox.isVisible = checked
        }

        var suppress = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppress || !dialogBinding.checkRatio.isChecked) return
                val editingHeight = s === dialogBinding.editHeight.text
                val value = s?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return
                suppress = true
                if (editingHeight) {
                    val w = (value.toFloat() * origW / origH).roundToInt().coerceIn(1, 12000)
                    dialogBinding.editWidth.setText(FlowUtil.intText(w))
                } else {
                    val h = (value.toFloat() * origH / origW).roundToInt().coerceIn(1, 12000)
                    dialogBinding.editHeight.setText(FlowUtil.intText(h))
                }
                suppress = false
            }
        }
        dialogBinding.editWidth.addTextChangedListener(watcher)
        dialogBinding.editHeight.addTextChangedListener(watcher)

        dialogBinding.btnKeepOriginal.setOnClickListener {
            suppress = true
            dialogBinding.editWidth.text?.clear()
            dialogBinding.editHeight.text?.clear()
            suppress = false
            dialogBinding.editMaxSize.text?.clear()
            dialogBinding.presetGroup.clearCheck()
            dialogBinding.checkAdvanced.isChecked = false
        }

        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.resize_title)
            .setContent(dialogBinding.root)
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setPrimaryStayOpen(R.string.resize_action) {
                val ratio = dialogBinding.checkRatio.isChecked
                val ew = dialogBinding.editWidth.text.toString().toIntOrNull()
                val eh = dialogBinding.editHeight.text.toString().toIntOrNull()
                val targetText = dialogBinding.editMaxSize.text?.toString()

                val target = if (targetText.isNullOrBlank()) null else ImageTools.parseSizeInput(targetText)
                if (!targetText.isNullOrBlank() && target == null) {
                    Toast.makeText(activity, R.string.resize_invalid_target, Toast.LENGTH_LONG).show()
                    return@setPrimaryStayOpen
                }

                val (w, h) = when {
                    ew == null && eh == null -> origW to origH
                    ratio && ew == null -> (derive(eh!!, origH, origW)) to eh
                    ratio -> ew to derive(ew!!, origW, origH)
                    else -> (ew ?: origW) to (eh ?: origH)
                }
                if (w !in 1..12000 || h !in 1..12000) {
                    Toast.makeText(activity, R.string.resize_invalid_size, Toast.LENGTH_SHORT).show()
                    return@setPrimaryStayOpen
                }
                sheet.dismiss()
                // DPI is intentionally not exposed: a DPI tag alone does not change
                // pixels, so only real pixel dimensions + target size are offered,
                // and no DPI metadata is stamped the user didn't ask for.
                doResize(
                    bytes, name,
                    ImageTools.ResizeOptions(
                        width = w, height = h, keepRatio = ratio,
                        maxBytes = target, keepPng = false, dpi = 0
                    )
                )
        }
        sheet.show()
    }

    private fun derive(from: Int, fromOrig: Int, toOrig: Int): Int =
        if (fromOrig > 0) (from.toDouble() * toOrig / fromOrig).roundToInt() else from

    private fun doResize(bytes: ByteArray, name: String, opts: ImageTools.ResizeOptions) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { ImageTools.resize(bytes, opts) }
                val dir = File(activity.filesDir, "exports").apply { mkdirs() }
                val base = FlowUtil.sanitizeFileName(name).substringBeforeLast('.').ifBlank { "image" }
                val ext = if (result.isPng) "png" else "jpg"
                val fileName = "${base}_${result.width}x${result.height}.$ext"
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { file.writeBytes(result.bytes) }

                // Show the real result (preview + before/after) instead of a toast.
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = file.path,
                        previewPath = file.path,
                        mime = if (result.isPng) "image/png" else "image/jpeg",
                        displayName = fileName,
                        beforeBytes = bytes.size.toLong(),
                        afterBytes = result.bytes.size.toLong(),
                        width = result.width,
                        height = result.height,
                        title = name,
                        warning = null
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    activity, activity.getString(R.string.resize_failed, e.message), Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }
}
