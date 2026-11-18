package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PhotoAutoCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * fixinsertcrop (audit A8): the corner screen for photos picked through "Insert pages > Photos".
 *
 * A gallery photo is not a scan: the app's existing automatic crop ([PhotoAutoCrop], the same
 * `Deskew` detector behind page straightening) cuts the page out of the photo and straightens it,
 * then this screen shows it on the Passport-style [CropView] so the person confirms or adjusts the
 * corners. Several photos are confirmed one after another, with "Photo 2 of 3" in the header
 * because the total is known.
 *
 * Only confirmed crops are written, next to the picked copies in the batch's cache folder, and
 * the caller inserts them when every photo is done. Back or Cancel at any photo inserts nothing
 * from the batch, deletes the picked copies and leaves the document's draft untouched. Rotation
 * keeps the current photo and the adjusted frame (saved state holds the index and the rectangle,
 * never the JPEG).
 */
class PhotoCropFlowActivity : BaseActivity() {

    private lateinit var root: FrameLayout
    private lateinit var progress: ProgressBar

    private var sources: List<File> = emptyList()
    private var index = 0
    private val outputs = ArrayList<File>()

    private var overlay: FrameLayout? = null
    private var cropView: CropView? = null
    private var bitmap: Bitmap? = null

    /** The prepared (auto-cropped) JPEG of the current photo; the crop is taken from it. */
    private var currentBytes: ByteArray? = null
    private var detected = true
    private var busy = false

    /** The person's adjusted frame (normalised 0..1), re-applied once the restored photo shows. */
    private var savedRect: FloatArray? = null

    /** True once every crop is written: the picked copies may go, the crops must be inserted. */
    private var keepOutputs = false

    /** Invalidates an older preparation/encode when the screen is recreated or cancelled. */
    private var generation = 0

    private val cropBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = cancelFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = intent.getStringArrayListExtra(EXTRA_SOURCES).orEmpty().map(::File)
        if (sources.isEmpty() || sources.any { !it.isFile }) {
            cancelFlow()
            return
        }
        setContentView(buildRoot())
        onBackPressedDispatcher.addCallback(this, cropBack)
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt(KEY_INDEX, 0).coerceIn(0, sources.lastIndex)
            outputs += savedInstanceState.getStringArrayList(KEY_OUTPUTS).orEmpty()
                .map(::File).filter { it.isFile }
            savedRect = savedInstanceState.getFloatArray(KEY_RECT)
        }
        prepare(index)
    }

    private fun buildRoot(): View {
        root = FrameLayout(this).apply {
            setBackgroundColor(SwStyle.color(this@PhotoCropFlowActivity, R.color.void_abyss))
            applySystemBarsPadding()
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            isVisible = false
            contentDescription = getString(R.string.fixinsertcrop_crop_title, 1, sources.size)
        }
        root.addView(
            progress,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        return root
    }

    // ---- one photo at a time ----------------------------------------------

    /**
     * Reads, auto-crops and decodes the photo at [index] off the main thread (sampled decode, so a
     * 12 MP gallery photo is never decoded at full size), then shows its corner screen.
     */
    private fun prepare(index: Int) {
        val generation = ++generation
        busy = false
        progress.isVisible = true
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val raw = runCatching { sources[index].readBytes() }.getOrNull()
                if (raw == null) null else runCatching { PhotoAutoCrop.crop(raw) }
                    .getOrDefault(PhotoAutoCrop.Result(raw, false))
            }
            if (generation != this@PhotoCropFlowActivity.generation) return@launch
            if (prepared == null) {
                fail()
                return@launch
            }
            val decoded = withContext(Dispatchers.IO) {
                runCatching { ImageTools.decodeSampled(prepared.bytes, PREVIEW_DIM) }.getOrNull()
            }
            if (generation != this@PhotoCropFlowActivity.generation) {
                decoded?.recycle()
                return@launch
            }
            if (decoded == null) {
                fail()
                return@launch
            }
            if (isFinishing || isDestroyed) {
                decoded.recycle()
                return@launch
            }
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = decoded
            currentBytes = prepared.bytes
            detected = prepared.cropped
            progress.isVisible = false
            showCropScreen()
        }
    }

    private fun showCropScreen() {
        val shown = bitmap ?: return
        val saved = savedRect
        root.removeView(overlay)

        val screen = FrameLayout(this).apply { isClickable = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@PhotoCropFlowActivity, 18f), SwStyle.dp(this@PhotoCropFlowActivity, 10f),
                SwStyle.dp(this@PhotoCropFlowActivity, 18f), SwStyle.dp(this@PhotoCropFlowActivity, 18f)
            )
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            val size = SwStyle.dp(this@PhotoCropFlowActivity, 48f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { cancelFlow() }
        })
        bar.addView(
            SwStyle.text(this, 20f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.fixinsertcrop_crop_title, index + 1, sources.size)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@PhotoCropFlowActivity, 10f)
            }
        )
        column.addView(bar)
        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(
                if (detected) R.string.fixinsertcrop_crop_hint
                else R.string.fixinsertcrop_crop_hint_none
            )
            setLineSpacing(0f, 1.25f)
            setPadding(
                0, SwStyle.dp(this@PhotoCropFlowActivity, 8f),
                0, SwStyle.dp(this@PhotoCropFlowActivity, 10f)
            )
        })

        // The Passport/ID-card crop surface, unchanged: four corners and four edge handles.
        val view = CropView(this).apply {
            id = R.id.toolCropView
            background = SwStyle.rounded(
                this@PhotoCropFlowActivity, 0xFF000000.toInt(), radiusDp = 14f
            )
        }
        column.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val apply = SwStyle.text(
            this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true
        ).apply {
            id = R.id.toolCropApply
            text = getString(R.string.fixinsertcrop_crop_apply)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = SwStyle.rounded(
                this@PhotoCropFlowActivity,
                SwStyle.color(this@PhotoCropFlowActivity, R.color.cyan_primary),
                radiusDp = 16f
            )
            setOnClickListener { applyCrop() }
        }
        column.addView(apply, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@PhotoCropFlowActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@PhotoCropFlowActivity, 14f) })

        screen.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(screen, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay = screen
        cropView = view
        progress.bringToFront()
        // The auto-crop already cut the page out, so the frame starts on its edges; the person
        // only trims when the detection was slightly off. A frame adjusted before a rotation
        // comes back exactly as it was left.
        view.setImage(shown)
        if (saved != null && saved.size == 4) {
            view.setCropRect(
                android.graphics.RectF(
                    saved[0] * shown.width, saved[1] * shown.height,
                    saved[2] * shown.width, saved[3] * shown.height
                )
            )
        }
        savedRect = null
    }

    /** Confirms the frame of the current photo and moves on to the next one (or finishes). */
    private fun applyCrop() {
        if (busy) return
        val view = cropView ?: return
        val source = bitmap ?: return
        val rect = view.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        // Always a private copy: a full-frame crop hands back the source bitmap itself, and this
        // screen may recycle it the moment Back closes the screen.
        val region = runCatching {
            val cut = Bitmap.createBitmap(source, left, top, width, height)
            if (cut === source) source.copy(Bitmap.Config.ARGB_8888, false) else cut
        }.getOrNull() ?: return
        busy = true
        val token = overlay
        val tokenGeneration = generation
        progress.bringToFront()
        progress.isVisible = true
        lifecycleScope.launch {
            val encoded = try {
                withContext(Dispatchers.IO) {
                    runCatching { ImageTools.encodeJpeg(region, 95) }.getOrNull()
                }
            } finally {
                region.takeIf { !it.isRecycled }?.recycle()
            }
            // Back/Cancel won while the JPEG was written: nothing is kept from this photo.
            if (token == null || token !== overlay ||
                tokenGeneration != this@PhotoCropFlowActivity.generation ||
                isFinishing || isDestroyed
            ) {
                return@launch
            }
            busy = false
            if (encoded == null) {
                fail()
                return@launch
            }
            val dir = sources.firstOrNull()?.parentFile ?: run { fail(); return@launch }
            val file = File(dir, String.format(Locale.ROOT, "cropped_%03d.jpg", index))
            val written = withContext(Dispatchers.IO) {
                runCatching { Durable.writeFile(file, encoded) }.isSuccess
            }
            if (generation != this@PhotoCropFlowActivity.generation || isFinishing || isDestroyed) {
                file.delete()
                return@launch
            }
            if (!written || !file.isFile) {
                fail()
                return@launch
            }
            outputs += file
            if (index + 1 < sources.size) {
                index++
                savedRect = null
                prepare(index)
            } else {
                finishBatch()
            }
        }
    }

    /** Every photo is confirmed: the picked copies go, the confirmed crops go back to the caller. */
    private fun finishBatch() {
        keepOutputs = true
        sources.forEach { runCatching { it.delete() } }
        val result = Intent().putStringArrayListExtra(
            EXTRA_CROPPED, ArrayList(outputs.map { it.path })
        )
        setResult(RESULT_OK, result)
        finish()
    }

    /** Back / Cancel at any photo: nothing from this batch is inserted and no copy is kept. */
    private fun cancelFlow() {
        generation++
        deleteBatch()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun fail() {
        progress.isVisible = false
        android.widget.Toast.makeText(
            this, R.string.fixinsertcrop_crop_failed, android.widget.Toast.LENGTH_LONG
        ).show()
        cancelFlow()
    }

    /** The batch's cache folder holds plaintext picked photos, so leaving the flow deletes it. */
    private fun deleteBatch() {
        runCatching {
            sources.firstOrNull()?.parentFile
                ?.takeIf { it.name.startsWith("insert_") }
                ?.deleteRecursively()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
        outState.putStringArrayList(KEY_OUTPUTS, ArrayList(outputs.map { it.path }))
        val rect = cropView?.getCropRect()
        val shown = bitmap
        val normalised = if (rect != null && shown != null && shown.width > 0 && shown.height > 0) {
            floatArrayOf(
                rect.left / shown.width, rect.top / shown.height,
                rect.right / shown.width, rect.bottom / shown.height
            )
        } else {
            savedRect
        }
        if (normalised != null) outState.putFloatArray(KEY_RECT, normalised)
    }

    override fun onDestroy() {
        generation++
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        // A finish that did not run finishBatch (system Back, a crash path) keeps no picked copy.
        if (isFinishing && !isChangingConfigurations && !keepOutputs) {
            deleteBatch()
            setResult(RESULT_CANCELED)
        }
        super.onDestroy()
    }

    /** Tests: the photo being confirmed (0 based). */
    internal fun currentPhotoIndexForTest(): Int = index

    /** Tests: the prepared (auto-cropped) photo the corner screen is showing. */
    internal fun shownBytesForTest(): ByteArray? = currentBytes

    companion object {
        /** The confirmed page files, in the order the person confirmed them. */
        const val EXTRA_CROPPED = "cropped_pages"

        private const val EXTRA_SOURCES = "source_photos"
        private const val KEY_INDEX = "crop_photo_index"
        private const val KEY_OUTPUTS = "crop_photo_outputs"
        private const val KEY_RECT = "crop_photo_rect"

        /** Display/crop copy cap; the prepared page is never larger than the deskew warp. */
        private const val PREVIEW_DIM = 2400

        /** The corner screen for these picked copies (from `cacheDir/insert_<id>`). */
        fun intent(context: Context, sources: List<File>): Intent = Intent(
            context, PhotoCropFlowActivity::class.java
        ).putStringArrayListExtra(EXTRA_SOURCES, ArrayList(sources.map { it.path }))
    }
}
