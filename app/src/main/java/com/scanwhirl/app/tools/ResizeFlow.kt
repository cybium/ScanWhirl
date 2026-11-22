package com.scanwhirl.app.tools

import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.DialogResizeBinding
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.util.BoundedCopy
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.SizeUnits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Resize tool. The launchers stay in the Activity (they must register before
 * STARTED); this class owns the dialog and the work.
 *
 * FF-R: width x height is now an exact canvas. Crop is the default and uses a
 * movable aspect-locked frame; the alternative aspect-fits the whole picture on
 * white bars. Both preserve the subject's shape. A byte target only changes JPEG
 * quality and an unmet target is reported, never hidden by shrinking pixels.
 */
class ResizeFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    /** Injectable so a cancellation test can block an in-flight compute. */
    private val compute: (ByteArray, ExactResize.Options) -> ExactResize.Result =
        { bytes, opts -> ExactResize.process(bytes, opts) }
) {

    fun start(uri: Uri) {
        activity.lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { repo.displayNameOf(uri) ?: "image" }
                val copy = FlowUtil.copyPickedImage(activity, uri, name)
                val bytes = try {
                    withContext(Dispatchers.IO) { copy.readBytes() }
                } finally {
                    BoundedCopy.release(copy)
                }
                // EXIF rotation belongs to the source space the crop frame works in.
                val size = try {
                    ExactResize.normalizedSize(bytes)
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (size == null) {
                    failWith(R.string.f8_reason_not_image)
                    return@launch
                }
                showResizeDialog(bytes, size.first, size.second, name)
            } catch (e: BoundedCopy.Refused) {
                val reason = if (e.result == BoundedCopy.Result.TOO_LARGE ||
                    e.result == BoundedCopy.Result.NO_SPACE
                ) R.string.f8_reason_too_large else R.string.f8_reason_unreadable
                failWith(reason)
            } catch (e: ExactResize.TooLargeException) {
                failWith(R.string.f8_reason_too_large)
            } catch (e: OutOfMemoryError) {
                failWith(R.string.f8_reason_too_large)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failWith(R.string.f8_reason_unreadable)
            }
        }
    }

    private fun failWith(@StringRes reason: Int) {
        Toast.makeText(
            activity,
            activity.getString(R.string.resize_failed, activity.getString(reason)),
            Toast.LENGTH_LONG
        ).show()
    }

    private suspend fun showResizeDialog(bytes: ByteArray, origW: Int, origH: Int, name: String) {
        val b = DialogResizeBinding.inflate(activity.layoutInflater)
        b.fileInfo.text = activity.getString(
            R.string.resize_file_info, name, SizeUnits.format(bytes.size.toLong()), origW, origH
        )
        b.editWidth.setText(FlowUtil.intText(origW))
        b.editHeight.setText(FlowUtil.intText(origH))

        // A bounded, EXIF-normalised display copy: CropView works in these pixels
        // and its rect is scaled back to the full source when the work starts.
        val preview = withContext(Dispatchers.IO) {
            ExactResize.decodeNormalized(bytes, 900)
        }
        b.cropPreview.setImage(preview)

        fun currentSize(): Pair<Int, Int> {
            val w = b.editWidth.text.toString().toIntOrNull() ?: origW
            val h = b.editHeight.text.toString().toIntOrNull() ?: origH
            return w to h
        }
        fun refreshFrame() {
            val (w, h) = currentSize()
            if (w in 1..12000 && h in 1..12000) {
                b.cropPreview.aspectRatio = w.toFloat() / h
            }
            // Text budget: one short helper. The byte-limit explanation sits
            // behind the ⓘ button; the numbers already live in the fields.
            val helper = if (b.resizeModeCrop.isChecked) R.string.resize_crop_helper
            else R.string.resize_borders_helper
            b.helperText.text = activity.getString(helper)
        }
        refreshFrame()

        // Details behind an ⓘ tap, never on the face of the work screen.
        b.btnResizeInfo.setOnClickListener {
            Toast.makeText(activity, R.string.resize_info_detail, Toast.LENGTH_LONG).show()
        }

        b.resizeModeGroup.setOnCheckedChangeListener { _, _ ->
            b.cropPreview.isVisible = b.resizeModeCrop.isChecked
            refreshFrame()
        }

        // V2T T2: one free decimal-KB table across Resize and Compress.
        val presetValues = CompressionPresentation.quickTargetKb.associateWith { kb ->
            activity.getString(CompressionPresentation.quickTargetLabel(kb))
        }.map { (kb, label) ->
            val id = when (kb) {
                20 -> R.id.v2t_quick_20
                50 -> R.id.v2t_quick_50
                100 -> R.id.v2t_quick_100
                else -> R.id.v2t_quick_200
            }
            id to label
        }.toMap()

        var suppressPreset = false
        b.presetGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = presetValues[checkedId] ?: return@setOnCheckedChangeListener
            suppressPreset = true
            b.editMaxSize.setText(value)
            suppressPreset = false
        }
        b.editMaxSize.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressPreset) return
                val checkedId = b.presetGroup.checkedChipId
                val text = s?.toString()?.trim().orEmpty()
                if (checkedId != -1 && presetValues[checkedId]?.equals(text, true) != true) {
                    b.presetGroup.clearCheck()
                }
            }
        })

        var suppress = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppress) return
                suppress = true
                refreshFrame()
                suppress = false
            }
        }
        b.editWidth.addTextChangedListener(watcher)
        b.editHeight.addTextChangedListener(watcher)

        b.btnMinMaxKb.setOnClickListener {
            val (w, h) = currentSize()
            val kb = b.editMaxSize.text?.toString()?.trim()?.let { SizeUnits.parse(it) }
            activity.startActivity(
                ExactResize.formPhotoIntent(
                    activity,
                    w.coerceIn(1, 12000),
                    h.coerceIn(1, 12000),
                    kb?.let { (it / 1000L).toInt().coerceAtLeast(1) }
                )
            )
        }

        b.btnKeepOriginal.setOnClickListener {
            suppress = true
            b.editWidth.setText(FlowUtil.intText(origW))
            b.editHeight.setText(FlowUtil.intText(origH))
            suppress = false
            b.editMaxSize.text?.clear()
            b.presetGroup.clearCheck()
            b.cropPreview.clearImage()
            b.cropPreview.setImage(preview)
            b.resizeModeCrop.isChecked = true
            refreshFrame()
        }

        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.resize_title)
            .setContent(b.root)
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setPrimaryStayOpen(R.string.resize_action) {
            val (w, h) = currentSize()
            if (w !in 1..12000 || h !in 1..12000) {
                Toast.makeText(activity, R.string.resize_invalid_size, Toast.LENGTH_SHORT).show()
                return@setPrimaryStayOpen
            }
            val targetText = b.editMaxSize.text?.toString()
            val target = if (targetText.isNullOrBlank()) null else SizeUnits.parse(targetText)
            if (!targetText.isNullOrBlank() && target == null) {
                Toast.makeText(activity, R.string.resize_invalid_target, Toast.LENGTH_LONG).show()
                return@setPrimaryStayOpen
            }

            // CropView's rect is in the preview's pixels; scale it into the
            // EXIF-normalised full source. Borders mode ignores the frame.
            val cropRect = if (b.resizeModeCrop.isChecked && preview.width > 0 && preview.height > 0) {
                val r = b.cropPreview.getCropRect()
                if (r.width() > 0f && r.height() > 0f) {
                    val sx = origW.toFloat() / preview.width
                    val sy = origH.toFloat() / preview.height
                    android.graphics.RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
                } else null
            } else null
            sheet.dismiss()
            doResize(
                bytes, name,
                ExactResize.Options(
                    width = w,
                    height = h,
                    mode = if (b.resizeModeCrop.isChecked) ExactResize.Mode.CROP
                    else ExactResize.Mode.BORDERS,
                    maxBytes = target,
                    cropRect = cropRect
                )
            )
        }
        sheet.show()
    }

    companion object {
        /** A handful of tries is enough for a genuine same-millisecond name collision. */
        private const val MAX_OUTPUT_DIR_ATTEMPTS = 8
    }

    /**
     * Every run writes into its own folder under the FileProvider-exposed
     * `cacheDir/documents` tree. The cold-start sweep deletes it, and a second
     * resize can never overwrite the first one's result.
     *
     * Call ONLY from Dispatchers.IO (see [doResize]): a full or unwritable file
     * system must fail fast, never spin or block the main thread. The parent is
     * created once; only a genuine name collision is retried, with a small bound.
     */
    fun resizeOutputDir(cacheDir: File): File {
        val root = File(cacheDir, "documents/resize")
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("Cannot create resize output folder: ${root.path}")
        }
        for (attempt in 0 until MAX_OUTPUT_DIR_ATTEMPTS) {
            val candidate = File(
                root, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
            )
            if (candidate.mkdir()) return candidate
            if (candidate.isDirectory) return candidate // lost a race, still a valid folder
            if (!candidate.exists()) {
                // Not a collision: the file system refused the write (e.g. no space).
                throw IOException("Cannot create resize output folder: ${candidate.path}")
            }
        }
        throw IOException("Could not reserve a unique resize output folder")
    }

    internal fun doResize(bytes: ByteArray, name: String, opts: ExactResize.Options): Job {
        val progress = host.showProgress()
        return activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { compute(bytes, opts) }
                val dir = withContext(Dispatchers.IO) { resizeOutputDir(activity.cacheDir) }
                val base = FlowUtil.sanitizeFileName(name).substringBeforeLast('.').ifBlank { "image" }
                val fileName = "${base}_${result.width}x${result.height}.jpg"
                val file = File(dir, fileName)
                withContext(Dispatchers.IO) { file.writeBytes(result.bytes) }

                // Report from the saved file, decoded again, not from the request.
                val saved = withContext(Dispatchers.IO) {
                    val raw = file.readBytes()
                    val dims = ImageTools.imageSize(raw)
                    Triple(raw.size.toLong(), dims?.first, dims?.second)
                }
                val warning = if (!result.bytesMet && opts.maxBytes != null) {
                    activity.getString(
                        R.string.resize_target_missed,
                        saved.second ?: result.width, saved.third ?: result.height,
                        SizeUnits.format(opts.maxBytes)
                    )
                } else null
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = file.path,
                        previewPath = file.path,
                        mime = result.mime,
                        displayName = fileName,
                        beforeBytes = bytes.size.toLong(),
                        afterBytes = saved.first,
                        width = saved.second ?: result.width,
                        height = saved.third ?: result.height,
                        title = name,
                        warning = warning
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExactResize.TooLargeException) {
                failWith(R.string.f8_reason_too_large)
            } catch (e: ExactResize.InsufficientSourceException) {
                failWith(R.string.f8_reason_unreadable)
            } catch (e: OutOfMemoryError) {
                failWith(R.string.f8_reason_too_large)
            } catch (e: IOException) {
                // One short honest line, not a "Resize failed: <raw error>" receipt.
                Toast.makeText(activity, R.string.resize_save_failed, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val reason = if (ImageTools.isTooLargePhoto(e)) R.string.f8_reason_too_large
                else R.string.f8_reason_unreadable
                failWith(reason)
            } finally {
                progress.dismiss()
            }
        }
    }
}
