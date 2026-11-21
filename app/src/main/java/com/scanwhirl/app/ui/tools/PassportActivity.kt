package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2 UI: 03_tools_hub — Passport is the existing crop surface with a fixed 35 × 45 mm preset.
 *
 * Pick a photo, drag the four corners to the passport edges, then the cropped page is saved as
 * a normal ScanWhirl document and handed to [ToolResultActivity] (Open · Share · Save · Undo).
 *
 * T1: the picked photo is kept in a small cache copy (written off the main thread) together with
 * the adjusted crop frame, so rotation keeps real work instead of reopening an empty picker.
 */
class PassportActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var cropView: CropView
    private lateinit var applyButton: TextView
    private lateinit var pickPrompt: TextView
    private lateinit var errorView: TextView
    private lateinit var progress: View
    private var bitmap: Bitmap? = null
    private var busy = false

    /** Cache path of the upright picked photo; the Bundle only carries this string. */
    @Volatile
    private var imagePath: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            showPickPrompt()
        } else {
            load(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
        if (savedInstanceState == null) {
            pick.launch("image/*")
        } else {
            restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_IMAGE_PATH, imagePath)
        val rect = cropView.getCropRect()
        if (rect.width() > 0f && rect.height() > 0f) {
            outState.putFloatArray(KEY_CROP_RECT, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
        }
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@PassportActivity, 18f), SwStyle.dp(this@PassportActivity, 10f),
                SwStyle.dp(this@PassportActivity, 18f), SwStyle.dp(this@PassportActivity, 18f)
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
            setOnClickListener { finish() }
        })
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.tool_passport)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@PassportActivity, 10f)
            }
        )
        column.addView(bar)
        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.tools_passport_hint)
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, SwStyle.dp(this@PassportActivity, 10f))
        })

        pickPrompt = SwStyle.text(this, 16f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            text = getString(R.string.tools_passport_pick)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@PassportActivity, 48f)
            isClickable = true
            isFocusable = true
            setOnClickListener { pick.launch("image/*") }
        }
        column.addView(pickPrompt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        cropView = CropView(this).apply {
            id = R.id.toolCropView
            aspectRatio = 35f / 45f
            background = SwStyle.rounded(this@PassportActivity, 0xFF000000.toInt(), radiusDp = 14f)
        }
        column.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolResultError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 8f) })

        applyButton = SwStyle.text(this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.toolCropApply
            text = getString(R.string.tools_passport_apply)
            gravity = Gravity.CENTER
            // T1: Apply must do nothing until there is a real photo to crop.
            isEnabled = false
            alpha = 0.5f
            background = SwStyle.rounded(this@PassportActivity, SwStyle.color(this@PassportActivity, R.color.cyan_primary), radiusDp = 16f)
            setOnClickListener { apply() }
        }
        column.addView(applyButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@PassportActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 14f) })

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        progress = FrameLayout(this).apply {
            id = R.id.toolSetupProgress
            isVisible = false
            setBackgroundColor(0xCC0E1316.toInt())
            isClickable = true
            addView(ProgressBar(this@PassportActivity), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.applySystemBarsPadding()
        return root
    }

    private fun showPickPrompt() {
        pickPrompt.isVisible = true
        applyButton.isEnabled = false
        applyButton.alpha = 0.5f
    }

    /** Reads, orients and caches the pick entirely off the main thread. */
    private fun load(uri: Uri) {
        progress.isVisible = true
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val raw = runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
                val upright = raw?.let { bytes ->
                    runCatching { ImageTools.normalizeGalleryPhoto(bytes) }.getOrDefault(bytes)
                }
                upright?.let { bytes ->
                    // T1: publish the path from inside IO, right after the complete tmp+rename
                    // write. A rotation between the write and the main continuation must not lose it.
                    writeImageCache(bytes)?.let { path ->
                        imagePath = path
                        cacheWriteGate?.invoke()
                    }
                }
                val decoded = upright?.let { bytes ->
                    runCatching { ImageTools.decodeSampled(bytes, 2400) }.getOrNull()
                }
                decoded to (upright == null)
            }
            progress.isVisible = false
            val decoded = loaded.first
            if (decoded == null) {
                errorView.text = getString(R.string.tools_setup_image_failed)
                errorView.isVisible = true
                showPickPrompt()
                return@launch
            }
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = decoded
            cropView.setImage(decoded)
            errorView.isVisible = false
            pickPrompt.isVisible = false
            applyButton.isEnabled = true
            applyButton.alpha = 1f
        }
    }

    /** Rotation/process death: read the cached upright photo off the main thread and restore the frame. */
    private fun restoreState(state: Bundle) {
        val path = state.getString(KEY_IMAGE_PATH)
        val rect = state.getFloatArray(KEY_CROP_RECT)
        if (path == null) {
            showPickPrompt()
            return
        }
        progress.isVisible = true
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                // A partial .tmp can never sit at the final path; only a complete rename can.
                runCatching { File(path).takeIf { it.isFile && it.length() > 0L }?.readBytes() }
                    .getOrNull()
                    ?.let { runCatching { ImageTools.decodeSampled(it, 2400) }.getOrNull() }
            }
            progress.isVisible = false
            if (decoded == null) {
                errorView.text = getString(R.string.tools_setup_image_failed)
                errorView.isVisible = true
                showPickPrompt()
                return@launch
            }
            bitmap = decoded
            cropView.setImage(decoded)
            if (rect != null && rect.size == 4) {
                cropView.setCropRect(RectF(rect[0], rect[1], rect[2], rect[3]))
            }
            pickPrompt.isVisible = false
            applyButton.isEnabled = true
            applyButton.alpha = 1f
        }
    }

    /** Tests: the size of the decoded photo currently shown on the crop surface. */
    internal fun loadedImageSizeForTest(): Pair<Int, Int> =
        bitmap?.takeIf { !it.isRecycled }?.let { it.width to it.height } ?: (0 to 0)

    private fun writeImageCache(bytes: ByteArray): String? = runCatching {
        val dir = File(cacheDir, "t1_passport").apply { mkdirs() }
        val final = File(dir, "photo.jpg")
        val tmp = File(dir, "photo.jpg.tmp")
        tmp.writeBytes(bytes)
        if (final.exists()) final.delete()
        if (!tmp.renameTo(final)) {
            tmp.delete()
            return@runCatching null
        }
        final.absolutePath
    }.getOrNull()

    private fun apply() {
        if (busy) return
        val source = bitmap ?: return
        val rect = cropView.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        val cropped = runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull()
        if (cropped == null) {
            errorView.text = getString(R.string.tools_passport_failed)
            errorView.isVisible = true
            return
        }
        val bytes = ImageTools.encodeJpeg(cropped, 92)
        cropped.recycle()
        busy = true
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    repo.createDocument(getString(R.string.tools_passport_name), listOf(bytes))
                }
                progress.isVisible = false
                startActivity(ToolResultActivity.intent(this@PassportActivity, "passport", docIds = listOf(doc.id)))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.isVisible = false
                busy = false
                errorView.text = getString(R.string.tools_passport_failed_with, e.message ?: "")
                errorView.isVisible = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cropView.clearImage()
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        // T1: the plaintext cache is only for rotation. A real exit removes it; a configuration
        // change keeps it so the recreated screen can still restore the photo.
        if (isFinishing && !isChangingConfigurations) {
            runCatching { File(cacheDir, "t1_passport").deleteRecursively() }
        }
    }

    companion object {
        /** Tests: held inside IO right after the cache path is published. */
        @Volatile
        internal var cacheWriteGate: (() -> Unit)? = null

        private const val KEY_IMAGE_PATH = "t1_passport_image_path"
        private const val KEY_CROP_RECT = "t1_passport_crop_rect"

        fun intent(context: Context): Intent = Intent(context, PassportActivity::class.java)
    }
}
