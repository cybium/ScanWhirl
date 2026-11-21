package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.IdCard
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PaperSize
import com.scanwhirl.app.util.PhotoAutoCrop
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2 UI: 03_tools_hub — the ID card tool the way people actually use it.
 *
 * 1. Opening the tool starts Google's document scanner (the same ML Kit scanner as Scan, page
 *    limit 1, which crops the card) for the FRONT side, or the Android photo picker for a card
 *    that is already on the phone (one image, no scanner in the way).
 * 2. After the front, an `SwSheet` asks "Add the back side?" with Scan back side / Front only;
 *    the gallery picker is offered for the back too.
 * 3. An `SwSheet` asks for the layout: both sides on one A4 page (front top, back bottom, real
 *    ID-1 proportions) or each side on its own page. A front-only card only gets the layout
 *    that makes sense for one side. The choice is remembered.
 * 4. The document is published through `ScanRepository.createDocument` and the result screen
 *    opens with Keep (done) and Discard; Discard, or Back there, deletes that one document.
 *
 * Closing a step with Back leaves the whole flow to Home with nothing saved (owner bugs F1/W7);
 * a cancelled camera returns to this screen and never re-opens the capture sheet (X1). The
 * captured sides survive rotation and process death through a small cache copy (X2).
 *
 * fixidcrop: a photo picked from the gallery is not used as it is. The app's existing automatic
 * crop (page normalisation) cuts the card out and straightens it, then the Passport-style corner
 * crop screen shows that card for confirmation. Only the confirmed crop becomes a side; Back or
 * Cancel there returns to the side sheet and saves nothing.
 */
class IdCardActivity : BaseActivity() {

    enum class Side { FRONT, BACK }

    private lateinit var repo: ScanRepository
    private var front: ByteArray? = null
    private var back: ByteArray? = null
    private var pendingSide = Side.FRONT
    private var creating = false

    /**
     * T1: the cache copies are written once at capture time on IO. Saved state only carries these
     * path strings, so onSaveInstanceState never pushes JPEG bytes through the Bundle or writes
     * them on the main thread. Each path is published only after a complete tmp+rename write.
     */
    @Volatile
    private var frontPath: String? = null
    @Volatile
    private var backPath: String? = null
    @Volatile
    private var cropPath: String? = null
    private var frontWrite: Job? = null
    private var backWrite: Job? = null

    /**
     * True while our own code moves the flow on to the next step (a sheet button, "Front only").
     * A sheet that closes for any other reason (Back, outside tap, the close icon) is the user
     * leaving the flow, so the screen finishes and nothing is saved (F1/W7).
     */
    private var movingOn = false

    private lateinit var frontPreview: ImageView
    private lateinit var backPreview: ImageView
    private lateinit var errorView: TextView
    private lateinit var progress: View
    private lateinit var screenRoot: FrameLayout
    private lateinit var contentScroll: ScrollView

    /**
     * fixidcrop: the Passport-style corner crop screen. A photo picked from the gallery is cut
     * out and straightened by [PhotoAutoCrop] first, then this shows that card so the person can
     * confirm or adjust the edges. Back or Cancel returns to the side sheet with nothing saved.
     */
    private var cropOverlay: FrameLayout? = null
    private var cropView: CropView? = null
    private var cropBitmap: Bitmap? = null
    private var cropSide = Side.FRONT
    private var cropBytes: ByteArray? = null
    private var cropDetected = true
    private var cropBusy = false

    /**
     * N1/D5: while the picked photo is being read and prepared its Uri is kept, so a recreation
     * during preparation re-reads the same photo instead of losing the pick.
     */
    private var cropSourceUri: Uri? = null
    private var cropPreparing = false

    /** The person's adjusted frame (normalised 0..1), restored once the crop screen is shown. */
    private var cropSavedRect: FloatArray? = null

    /** Invalidates an older preparation/reopen when a newer state starts (or Cancel wins). */
    private var cropGeneration = 0

    private val cropBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelCrop()
    }

    /** Tests: fake the camera launch (the ML Kit scanner cannot run under Robolectric). */
    @Volatile
    internal var captureOverride: ((Side) -> Boolean)? = null

    /** Tests: hold automatic preparation so a recreation can happen while it is in flight. */
    @Volatile
    internal var cropPrepareGate: ((Side) -> Unit)? = null

    /** Tests: the crop JPEG finished encoding, even when Back already abandoned the screen. */
    @Volatile
    internal var cropEncodeFinishedForTest: ((ByteArray?) -> Unit)? = null

    /**
     * Tests: hold the finished crop encode, so a Back (or Cancel) is guaranteed to reach the
     * screen while the save is still in flight instead of racing the main-thread decision.
     */
    @Volatile
    internal var cropEncodeGate: (() -> Unit)? = null

    /** Tests: the main-thread accept/abandon decision ran (true = the side is accepted). */
    @Volatile
    internal var cropSaveSettledForTest: ((Boolean) -> Unit)? = null

    private val scanner = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            onCaptureCancelled()
            return@registerForActivityResult
        }
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uri = scan?.pages?.firstOrNull()?.imageUri
        if (uri == null) {
            onCaptureCancelled()
        } else {
            readImage(uri, pendingSide)
        }
    }

    /**
     * F1/W4: "Pick from gallery" is the Android photo picker itself, one image, so the card photo
     * is two taps away instead of Google's scanner plus its own gallery import (W4).
     */
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            onCaptureCancelled()
        } else {
            // D5(b): remember the pick before anything is read, so a recreation while the photo
            // is still being read and prepared can re-read the same Uri.
            cropPreparing = true
            cropSourceUri = uri
            cropSide = pendingSide
            cropSavedRect = null
            readImage(uri, pendingSide, autoCrop = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
        onBackPressedDispatcher.addCallback(this, cropBack)
        if (savedInstanceState == null) {
            clearCapturedState()
            // The first step is always the choice sheet: people without a good camera angle can
            // use the gallery, and the camera opens only when they ask for it.
            showFrontSheet()
        } else {
            // X2: rotation or process death must not re-ask for the sides that are already in.
            restoreCapturedState(savedInstanceState)
            render()
            when {
                // D5: a rotation while the corner screen was open brings that photo back, and a
                // rotation while it was still being prepared re-runs the preparation.
                cropBytes != null -> reopenCropScreen()
                cropPreparing && cropSourceUri != null ->
                    resumeCropPreparation(cropSourceUri!!, cropSide)
                back != null -> showLayoutSheet()
                front != null -> showBackSheet()
                else -> showFrontSheet()
            }
        }
    }

    // ---- kept across recreation (X2) --------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        keepCapturedState(outState)
        outState.putBoolean(KEY_HAS_STATE, true)
        outState.putInt(KEY_PENDING_SIDE, pendingSide.ordinal)
    }

    /**
     * The captures and the photo waiting on the corner crop screen live in a small cache copy so
     * a recreated screen (rotation, process death) keeps them without pushing a multi-megabyte
     * JPEG through the saved-state Bundle (X2, D5).
     */
    private fun keepCapturedState(outState: Bundle) {
        // The writes already ran on IO at capture time. Join them here only as a safety barrier so
        // a rotation in that tiny gap still finds a complete file; nothing is written on main.
        runBlocking {
            frontWrite?.join()
            backWrite?.join()
        }
        if (front != null && frontPath != null) outState.putString(KEY_FRONT_PATH, frontPath)
        if (back != null && backPath != null) outState.putString(KEY_BACK_PATH, backPath)
        if (cropBytes != null) {
            outState.putBoolean(KEY_CROP_PENDING, true)
            cropPath?.let { outState.putString(KEY_CROP_PATH, it) }
            outState.putInt(KEY_CROP_SIDE, cropSide.ordinal)
            outState.putBoolean(KEY_CROP_DETECTED, cropDetected)
            // D5(a): the person's adjusted frame, normalised so any preview size restores it.
            // R2-1: during the async reopen there is no live CropView/bitmap yet, so keep the
            // rectangle that was restored from the previous state instead of saving nothing.
            val rect = cropView?.getCropRect()
            val shown = cropBitmap
            val savedRect = if (rect != null && shown != null && shown.width > 0 && shown.height > 0) {
                floatArrayOf(
                    rect.left / shown.width, rect.top / shown.height,
                    rect.right / shown.width, rect.bottom / shown.height
                )
            } else {
                cropSavedRect
            }
            if (savedRect != null) outState.putFloatArray(KEY_CROP_RECT, savedRect)
        } else {
            // D5(b): preparation is still running, so keep the pick itself, not its result.
            val uri = cropSourceUri
            if (cropPreparing && uri != null) {
                outState.putString(KEY_CROP_SOURCE_URI, uri.toString())
                outState.putInt(KEY_CROP_SIDE, cropSide.ordinal)
            }
        }
    }

    private fun restoreCapturedState(state: Bundle) {
        if (!state.getBoolean(KEY_HAS_STATE, false)) return
        pendingSide = if (state.getInt(KEY_PENDING_SIDE, 0) == Side.BACK.ordinal) Side.BACK else Side.FRONT
        runCatching {
            val dir = capturedStateDir()
            fun pathFor(key: String, fallback: String): String? =
                state.getString(key) ?: File(dir, fallback).takeIf { it.isFile }?.absolutePath
            fun bytesAt(path: String?): ByteArray? = path?.let {
                File(it).takeIf { f -> f.isFile && f.length() > 0L }?.readBytes()
            }

            val fp = pathFor(KEY_FRONT_PATH, FRONT_FILE)
            front = bytesAt(fp)
            frontPath = fp
            val bp = pathFor(KEY_BACK_PATH, BACK_FILE)
            back = bytesAt(bp)
            backPath = bp
            if (state.getBoolean(KEY_CROP_PENDING, false)) {
                val cp = pathFor(KEY_CROP_PATH, CROP_FILE)
                cropBytes = bytesAt(cp)
                cropPath = cp
                if (cropBytes != null) {
                    cropSide = if (state.getInt(KEY_CROP_SIDE, 0) == Side.BACK.ordinal) Side.BACK else Side.FRONT
                    cropDetected = state.getBoolean(KEY_CROP_DETECTED, true)
                    cropSavedRect = state.getFloatArray(KEY_CROP_RECT)
                }
            } else {
                val source = state.getString(KEY_CROP_SOURCE_URI)
                if (source != null) {
                    cropSourceUri = Uri.parse(source)
                    cropPreparing = true
                    cropSide = if (state.getInt(KEY_CROP_SIDE, 0) == Side.BACK.ordinal) Side.BACK else Side.FRONT
                }
            }
        }
    }

    private fun clearCapturedState() {
        runCatching { capturedStateDir().deleteRecursively() }
        frontPath = null
        backPath = null
        cropPath = null
    }

    private fun capturedStateDir(): File = File(cacheDir, STATE_DIR)

    /** T1: capture-time write on IO; tmp+rename so a half-written JPEG is never restored. */
    private fun writeCache(bytes: ByteArray, fileName: String): String? = runCatching {
        val dir = capturedStateDir().apply { mkdirs() }
        val final = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(bytes)
        if (final.exists()) final.delete()
        if (!tmp.renameTo(final)) {
            tmp.delete()
            return@runCatching null
        }
        final.absolutePath
    }.getOrNull()

    // ---- screen -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        contentScroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = SwStyle.dp(this@IdCardActivity, 18f)
            setPadding(pad, SwStyle.dp(this@IdCardActivity, 10f), pad, SwStyle.dp(this@IdCardActivity, 28f))
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
                text = getString(R.string.tools_id_card_name)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@IdCardActivity, 10f)
            }
        )
        column.addView(bar)
        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.tools_id_hint)
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@IdCardActivity, 8f), 0, SwStyle.dp(this@IdCardActivity, 12f))
        })

        val previews = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frontPreview = previewSlot(previews, R.string.tools_setup_id_front, R.id.toolIdCardFront, Side.FRONT)
        backPreview = previewSlot(previews, R.string.tools_setup_id_back, R.id.toolIdCardBack, Side.BACK)
        column.addView(previews)

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolIdCardError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@IdCardActivity, 10f) })

        contentScroll.addView(column)
        root.addView(contentScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        progress = FrameLayout(this).apply {
            id = R.id.toolSetupProgress
            isVisible = false
            setBackgroundColor(0xCC0E1316.toInt())
            isClickable = true
            addView(
                LinearLayout(this@IdCardActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(ProgressBar(this@IdCardActivity))
                    addView(SwStyle.text(
                        this@IdCardActivity, 14f,
                        SwStyle.color(this@IdCardActivity, R.color.text_hologram_mid)
                    ).apply {
                        text = getString(R.string.tools_setup_working)
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(this@IdCardActivity, 12f) })
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.applySystemBarsPadding()
        screenRoot = root
        return root
    }

    private fun previewSlot(
        row: LinearLayout,
        labelRes: Int,
        id: Int,
        side: Side
    ): ImageView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = SwStyle.dp(this@IdCardActivity, 10f)
            setPadding(pad, pad, pad, pad)
            background = SwStyle.card(this@IdCardActivity, 16f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (side == Side.FRONT) showFrontSheet() else showBackSheet()
            }
        }
        val image = ImageView(this).apply {
            this.id = id
            setImageResource(R.drawable.ic_sym_badge)
            imageTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(this@IdCardActivity, R.color.cyan_primary)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        cell.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@IdCardActivity, 110f)
        ))
        cell.addView(SwStyle.text(
            this, 13f, SwStyle.color(this, R.color.text_hologram_mid)
        ).apply {
            text = getString(labelRes)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@IdCardActivity, 6f) })
        row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (side == Side.FRONT) marginEnd = SwStyle.dp(this@IdCardActivity, 8f)
            else marginStart = SwStyle.dp(this@IdCardActivity, 8f)
        })
        return image
    }

    private fun render() {
        frontPreview.setImageResource(R.drawable.ic_sym_badge)
        frontPreview.imageTintList = android.content.res.ColorStateList.valueOf(
            SwStyle.color(this, R.color.cyan_primary)
        )
        front?.let { setPreview(frontPreview, it) }
        backPreview.setImageResource(R.drawable.ic_sym_badge)
        backPreview.imageTintList = android.content.res.ColorStateList.valueOf(
            SwStyle.color(this, R.color.cyan_primary)
        )
        back?.let { setPreview(backPreview, it) }
    }

    private fun setPreview(view: ImageView, bytes: ByteArray) {
        runCatching {
            view.setImageBitmap(ImageTools.decodeSampled(bytes, 400))
            view.imageTintList = null
        }
    }

    // ---- capture ----------------------------------------------------------

    /** Opens the same Google ML Kit document scanner Scan uses, limited to one page. */
    private fun scan(side: Side) {
        pendingSide = side
        if (captureOverride?.invoke(side) == true) return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setScannerMode(
                if (Prefs.autoEnhanceScan(this)) {
                    GmsDocumentScannerOptions.SCANNER_MODE_FULL
                } else {
                    GmsDocumentScannerOptions.SCANNER_MODE_BASE
                }
            )
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(1)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender -> scanner.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { onCaptureCancelled() }
        } catch (e: Exception) {
            onCaptureCancelled()
        }
    }

    private fun pickFromGallery(side: Side) {
        pendingSide = side
        photoPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * W4/X1: a cancelled camera or picker puts the person back on the ID card screen (the step
     * the capture was opened from) with the side slots ready for another try. It never re-opens
     * the capture sheet, which made Back in the scanner look ignored.
     */
    internal fun onCaptureCancelled() {
        render()
    }

    private fun readImage(uri: Uri, side: Side, autoCrop: Boolean = false) {
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
            }
            progress.isVisible = false
            if (bytes == null) {
                if (autoCrop) {
                    cropPreparing = false
                    cropSourceUri = null
                    cropBack.isEnabled = false
                }
                fail(getString(R.string.tools_setup_image_failed))
                onCaptureCancelled()
            } else if (autoCrop) {
                beginCrop(side, bytes)
            } else {
                acceptCapture(side, bytes)
            }
        }
    }

    // ---- gallery photo: auto-crop, then confirm the corners (fixidcrop) ----

    /**
     * fixidcrop: a gallery photo is not a scan. It is oriented, cut out and straightened with the
     * app's existing page normalisation ([PhotoAutoCrop], the same detector behind the page
     * straighten) before the corner screen opens, so the table around the card is not on the
     * paper. Nothing is accepted here; only [applyCrop] hands a side to the flow.
     *
     * D5(b): the pick's Uri stays in [cropSourceUri] until the prepared photo is ready, so a
     * recreation during this work re-reads it ([resumeCropPreparation]) instead of losing it.
     */
    private fun beginCrop(side: Side, bytes: ByteArray) {
        val generation = ++cropGeneration
        cropPreparing = true
        cropSide = side
        // N1: Back while the photo is being prepared returns to the side sheet (and cleans up),
        // instead of finishing silently in the middle of the work.
        cropBack.isEnabled = true
        progress.isVisible = true
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                cropPrepareGate?.invoke(side)
                val oriented = runCatching { ImageTools.normalizeGalleryPhoto(bytes) }.getOrDefault(bytes)
                val result = runCatching { PhotoAutoCrop.crop(oriented) }
                    .getOrDefault(PhotoAutoCrop.Result(oriented, false))
                // T1: the pending crop photo is cached here on IO, not later on main in state save.
                cropPath = writeCache(result.bytes, CROP_FILE)
                result
            }
            if (generation != cropGeneration) return@launch
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { ImageTools.decodeSampled(prepared.bytes, CROP_PREVIEW_DIM) }.getOrNull()
            }
            if (generation != cropGeneration) {
                bitmap?.recycle()
                return@launch
            }
            progress.isVisible = false
            if (isFinishing || isDestroyed || isChangingConfigurations) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap == null) {
                fail(getString(R.string.fixidcrop_id_crop_failed))
                cropPreparing = false
                cropSourceUri = null
                cropBack.isEnabled = false
                clearCropState()
                render()
                showCaptureSheet(side)
                return@launch
            }
            cropPreparing = false
            cropSourceUri = null
            cropBytes = prepared.bytes
            showCropScreen(side, bitmap, prepared.cropped)
        }
    }

    /**
     * D5(b): the screen was recreated while the picked photo was still being read or prepared.
     * The Uri was saved with the side, so the same photo is read and prepared again here.
     */
    private fun resumeCropPreparation(uri: Uri, side: Side) {
        val generation = ++cropGeneration
        cropPreparing = true
        cropSide = side
        cropBack.isEnabled = true
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
            }
            if (generation != cropGeneration) return@launch
            progress.isVisible = false
            if (isFinishing || isDestroyed) return@launch
            if (bytes == null) {
                // R2-2: the restored pick can no longer be read (grant gone, provider denied).
                // The recovery message goes inside the side sheet that is about to open, so it is
                // visible and announced, with the normal Scan / Pick actions as the retry.
                cropPreparing = false
                cropSourceUri = null
                cropBack.isEnabled = false
                render()
                showCaptureSheet(side, R.string.fixidcrop_id_photo_gone)
                return@launch
            }
            beginCrop(side, bytes)
        }
    }

    /**
     * D5: the screen was recreated (rotation, process death) while a photo was waiting on the
     * corner screen. The prepared bytes come back from the cache copy with the same side and the
     * person's adjusted frame (D5(a)), so the crop continues where it was.
     */
    private fun reopenCropScreen() {
        val bytes = cropBytes ?: return
        val side = cropSide
        val detected = cropDetected
        val generation = ++cropGeneration
        // N1: Back during this async reopen must clean up and return to the side sheet.
        cropBack.isEnabled = true
        progress.isVisible = true
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                cropReopenGate?.invoke()
                runCatching { ImageTools.decodeSampled(bytes, CROP_PREVIEW_DIM) }.getOrNull()
            }
            if (generation != cropGeneration) {
                bitmap?.recycle()
                return@launch
            }
            progress.isVisible = false
            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap == null) {
                clearCropState()
                cropBytes = null
                cropSavedRect = null
                cropBack.isEnabled = false
                render()
                showCaptureSheet(side)
                return@launch
            }
            showCropScreen(side, bitmap, detected)
        }
    }

    private fun showCropScreen(side: Side, bitmap: Bitmap, detected: Boolean) {
        // dismissCropScreen() clears the pending state; this screen is the new owner of it.
        val savedRect = cropSavedRect
        val pendingBytes = cropBytes
        dismissCropScreen()
        cropBytes = pendingBytes
        cropSide = side
        cropDetected = detected
        cropBitmap = bitmap

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(SwStyle.color(this@IdCardActivity, R.color.void_abyss))
            isClickable = true
        }
        // D9: the card screen under the overlay must not stay in the accessibility tree.
        contentScroll.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@IdCardActivity, 18f), SwStyle.dp(this@IdCardActivity, 10f),
                SwStyle.dp(this@IdCardActivity, 18f), SwStyle.dp(this@IdCardActivity, 18f)
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
            // D9: the kit's icon button is 40dp; the crop screen's Back is a 48dp target.
            val size = SwStyle.dp(this@IdCardActivity, 48f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { cancelCrop() }
        })
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.fixidcrop_id_crop_title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@IdCardActivity, 10f)
            }
        )
        column.addView(bar)
        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            // D10: an undetected photo is not silently the same as a detected one.
            text = getString(
                if (cropDetected) R.string.fixidcrop_id_crop_hint
                else R.string.fixidcrop_id_crop_hint_none
            )
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@IdCardActivity, 8f), 0, SwStyle.dp(this@IdCardActivity, 10f))
        })

        // The Passport crop surface, unchanged: four draggable corners and edge handles.
        val view = CropView(this).apply {
            id = R.id.toolCropView
            background = SwStyle.rounded(this@IdCardActivity, 0xFF000000.toInt(), radiusDp = 14f)
        }
        column.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val apply = SwStyle.text(
            this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true
        ).apply {
            id = R.id.toolCropApply
            text = getString(R.string.fixidcrop_id_crop_apply)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = SwStyle.rounded(
                this@IdCardActivity, SwStyle.color(this@IdCardActivity, R.color.cyan_primary), radiusDp = 16f
            )
            setOnClickListener { applyCrop() }
        }
        column.addView(apply, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@IdCardActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@IdCardActivity, 14f) })

        overlay.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        screenRoot.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        cropOverlay = overlay
        cropView = view
        // Preset: the auto-crop already cut the card out, so the frame starts on its edges and
        // only needs adjusting when the detection was slightly off. D5(a): a frame the person
        // adjusted before a recreation comes back exactly as they left it.
        view.setImage(bitmap)
        savedRect?.let { rect ->
            if (rect.size == 4 && bitmap.width > 0 && bitmap.height > 0) {
                view.setCropRect(
                    android.graphics.RectF(
                        rect[0] * bitmap.width, rect[1] * bitmap.height,
                        rect[2] * bitmap.width, rect[3] * bitmap.height
                    )
                )
            }
        }
        cropSavedRect = null
        cropBack.isEnabled = true
    }

    private fun applyCrop() {
        if (cropBusy) return
        val view = cropView ?: return
        val source = cropBitmap ?: return
        val rect = view.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        // D2/D4: always a private copy. A full-frame crop hands back the source bitmap itself,
        // and the screen may recycle its bitmap the moment Back closes it; the encode must never
        // read a recycled (or concurrently recycled) bitmap.
        val region = runCatching {
            val cut = Bitmap.createBitmap(source, left, top, width, height)
            if (cut === source) source.copy(Bitmap.Config.ARGB_8888, false) else cut
        }.getOrNull()
        if (region == null) {
            // T1: a failed crop must leave a visible, localized error and a usable screen, never
            // a spinner that never ends or a silent tap that does nothing.
            cropBusy = false
            progress.isVisible = false
            fail(getString(R.string.fixidcrop_id_crop_failed))
            return
        }
        cropBusy = true
        val token = cropOverlay
        val side = cropSide
        progress.bringToFront()
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    val encoded = runCatching { ImageTools.encodeJpeg(region, 92) }.getOrNull()
                    // Tests: hold the finished encode so Back during the save is deterministic
                    // (null in production, like cropPrepareGate/cropReopenGate).
                    cropEncodeGate?.invoke()
                    // Tests: the encode really finished, even for an abandoned screen.
                    cropEncodeFinishedForTest?.invoke(encoded)
                    encoded
                }
            } finally {
                // N2: released on cancellation too; the main continuation may never run.
                region.takeIf { !it.isRecycled }?.recycle()
            }
            // D2: Back or Cancel closed the crop screen while the JPEG was being written. The
            // side must not be accepted afterwards, and nothing is shown to the person.
            if (token == null || token !== cropOverlay || isFinishing || isDestroyed) {
                cropSaveSettledForTest?.invoke(false)
                return@launch
            }
            cropSaveSettledForTest?.invoke(true)
            progress.isVisible = false
            cropBusy = false
            if (bytes == null) {
                fail(getString(R.string.fixidcrop_id_crop_failed))
                return@launch
            }
            clearCropState()
            dismissCropScreen()
            acceptCapture(side, bytes)
        }
    }

    /** Back / Cancel on the corner screen: nothing is saved and the side sheet comes back. */
    internal fun cancelCrop() {
        if (cropOverlay == null && cropBytes == null && !cropPreparing) return
        val side = cropSide
        cropGeneration++
        cropPreparing = false
        cropSourceUri = null
        cropSavedRect = null
        clearCropState()
        dismissCropScreen()
        render()
        showCaptureSheet(side)
    }

    private fun dismissCropScreen() {
        cropBack.isEnabled = false
        cropView?.clearImage()
        val overlay = cropOverlay
        cropOverlay = null
        cropView = null
        cropBytes = null
        cropSavedRect = null
        cropBitmap?.takeIf { !it.isRecycled }?.recycle()
        cropBitmap = null
        // D2/D9: leave no busy/progress state behind and give the card screen its tree back.
        cropBusy = false
        progress.isVisible = false
        contentScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (overlay != null) screenRoot.removeView(overlay)
    }

    /** The prepared photo only lives for the crop screen; the cache copy goes with it. */
    private fun clearCropState() {
        runCatching { File(capturedStateDir(), CROP_FILE).delete() }
        cropPath = null
    }

    private fun showCaptureSheet(side: Side, message: Int? = null) {
        if (side == Side.BACK) showBackSheet(message) else showFrontSheet(message)
    }

    /** Tests: the auto-cropped photo the corner crop screen is showing. */
    internal fun cropScreenBytesForTest(): ByteArray? = cropBytes

    /** Tests: how the card screen behind the crop overlay is exposed to accessibility. */
    internal fun contentAccessibilityForTest(): Int = contentScroll.importantForAccessibility

    /** A capture arrived (scanner or gallery); also the seam the UI tests drive. */
    internal fun acceptCapture(side: Side, bytes: ByteArray) {
        when (side) {
            Side.FRONT -> {
                front = bytes
                // T1: the cache copy is written once, here, on IO; onSaveInstanceState only
                // stores the resulting path string.
                frontWrite = lifecycleScope.launch(Dispatchers.IO) {
                    frontPath = writeCache(bytes, FRONT_FILE)
                }
                render()
                showBackSheet()
            }
            Side.BACK -> {
                back = bytes
                backWrite = lifecycleScope.launch(Dispatchers.IO) {
                    backPath = writeCache(bytes, BACK_FILE)
                }
                render()
                showLayoutSheet()
            }
        }
    }

    // ---- sheets -----------------------------------------------------------

    /**
     * A sheet is one step of the flow. Closing it with Back (or the close icon, or an outside tap)
     * leaves the flow with nothing saved, so one Back from any step before the result reaches
     * Home (F1/W7). Steps that move on set [movingOn] first.
     */
    private fun showSheet(sheet: SwSheet) {
        sheet.setOnDismissListener {
            val moved = movingOn
            movingOn = false
            if (!moved && !creating && !isFinishing && !isDestroyed && !isChangingConfigurations) {
                finish()
            }
        }
        sheet.show()
    }

    private fun showFrontSheet(message: Int? = null) {
        showSheet(
            SwSheet.builder(this)
                .setTitle(R.string.tools_id_front_title)
                .setMessage(message ?: R.string.tools_id_front_msg)
                .setPrimary(R.string.tools_id_scan_camera) {
                    movingOn = true
                    scan(Side.FRONT)
                }
                .setSecondary(R.string.tools_id_pick_gallery) {
                    movingOn = true
                    pickFromGallery(Side.FRONT)
                }
                .build()
        )
    }

    private fun showBackSheet(message: Int? = null) {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_id_back_title)
            .setMessage(message ?: R.string.tools_id_back_msg)
            .setPrimary(R.string.tools_id_scan_camera) {
                movingOn = true
                scan(Side.BACK)
            }
            .setSecondary(R.string.tools_id_pick_gallery) {
                movingOn = true
                pickFromGallery(Side.BACK)
            }
            .build()
        val frontOnly = SwStyle.text(
            this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true
        ).apply {
            text = getString(R.string.tools_id_front_only)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@IdCardActivity, 44f)
            isClickable = true
            setOnClickListener {
                movingOn = true
                sheet.dismiss()
                showLayoutSheet()
            }
        }
        sheet.setContent(frontOnly)
        showSheet(sheet)
    }

    private fun showLayoutSheet() {
        // T1: a layout needs a front. A back captured first (from the preview slot) must never
        // reach create() with no front and then be discarded when the sheet dismisses.
        if (front == null) {
            movingOn = true
            showFrontSheet(R.string.tools_id_need_front)
            return
        }
        // W6: with no back side there is nothing to put on a second half of the page.
        if (back == null) {
            showFrontOnlyLayoutSheet()
            return
        }
        val remembered = IdCardPrefs.layout(this)
        val other = if (remembered == IdCardPrefs.Layout.ONE_PAGE) {
            IdCardPrefs.Layout.OWN_PAGES
        } else {
            IdCardPrefs.Layout.ONE_PAGE
        }
        val last = getString(
            if (remembered == IdCardPrefs.Layout.ONE_PAGE) R.string.tools_id_layout_one
            else R.string.tools_id_layout_own
        )
        var chosenPaper = IdCardPrefs.paper(this)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_id_layout_title)
            .setMessage(getString(R.string.tools_id_layout_msg, last))
            .setSecondary(
                if (other == IdCardPrefs.Layout.ONE_PAGE) R.string.tools_id_layout_one
                else R.string.tools_id_layout_own
            ) { create(other, chosenPaper) }
            .build()

        sheet.setContent(paperRow(chosenPaper) { chosenPaper = it })
        sheet.setPrimary(
            if (remembered == IdCardPrefs.Layout.ONE_PAGE) R.string.tools_id_layout_one
            else R.string.tools_id_layout_own
        ) { create(remembered, chosenPaper) }
        showSheet(sheet)
    }

    /**
     * W6: front only, so the single meaningful layout is the front on its own page. "Both sides
     * on one page" is not offered at all because there is no back side to place.
     */
    private fun showFrontOnlyLayoutSheet() {
        var chosenPaper = IdCardPrefs.paper(this)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_id_layout_title)
            .setMessage(R.string.fixflow_id_layout_front_only_msg)
            .build()
        sheet.setContent(paperRow(chosenPaper) { chosenPaper = it })
        sheet.setPrimary(R.string.fixflow_id_layout_front_page) {
            // The layout was not a choice here, so the remembered layout is left alone.
            create(IdCardPrefs.Layout.OWN_PAGES, chosenPaper, rememberLayout = false)
        }
        showSheet(sheet)
    }

    /** The paper chips under a layout sheet; [onPick] gets every new choice. */
    private fun paperRow(initial: PaperSize, onPick: (PaperSize) -> Unit): View {
        var chosenPaper = initial
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            text = getString(R.string.tools_id_paper_title)
            setPadding(0, 0, 0, SwStyle.dp(this@IdCardActivity, 8f))
        })
        val a4 = paperChip(getString(R.string.tools_id_paper_a4))
        val letter = paperChip(getString(R.string.tools_id_paper_letter))
        fun paint() {
            paintPaperChip(a4, chosenPaper == PaperSize.A4)
            paintPaperChip(letter, chosenPaper == PaperSize.LETTER)
        }
        a4.setOnClickListener { chosenPaper = PaperSize.A4; paint(); onPick(chosenPaper) }
        letter.setOnClickListener { chosenPaper = PaperSize.LETTER; paint(); onPick(chosenPaper) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a4, LinearLayout.LayoutParams(0, SwStyle.dp(this@IdCardActivity, 42f), 1f).apply {
            marginEnd = SwStyle.dp(this@IdCardActivity, 8f)
        })
        row.addView(letter, LinearLayout.LayoutParams(0, SwStyle.dp(this@IdCardActivity, 42f), 1f))
        content.addView(row)
        paint()
        return content
    }

    private fun paperChip(text: String): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim), bold = true).apply {
            this.text = text
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

    private fun paintPaperChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(
            SwStyle.color(this, if (selected) R.color.cyan_primary else R.color.text_hologram_dim)
        )
        chip.background = if (selected) {
            SwStyle.rounded(
                this, SwStyle.color(this, R.color.sw_cyan_soft), radiusDp = 12f,
                stroke = SwStyle.color(this, R.color.cyan_primary)
            )
        } else {
            SwStyle.rounded(
                this, SwStyle.color(this, R.color.glass_surface), radiusDp = 12f,
                stroke = SwStyle.color(this, R.color.sw_field_border)
            )
        }
    }

    private fun create(
        layout: IdCardPrefs.Layout,
        paper: PaperSize,
        rememberLayout: Boolean = true
    ) {
        if (creating) return
        val frontBytes = front
        if (frontBytes == null) {
            // T1: the dismissing sheet must not finish the activity and throw away a captured back.
            movingOn = true
            showFrontSheet(R.string.tools_id_need_front)
            return
        }
        val backBytes = back
        creating = true
        progress.isVisible = true
        errorView.isVisible = false
        lifecycleScope.launch {
            try {
                if (rememberLayout) IdCardPrefs.remember(this@IdCardActivity, layout)
                IdCardPrefs.rememberPaper(this@IdCardActivity, paper)
                val pages = withContext(Dispatchers.IO) {
                    if (layout == IdCardPrefs.Layout.ONE_PAGE && backBytes != null) {
                        listOf(IdCard.compose(frontBytes, backBytes, paper))
                    } else {
                        val list = mutableListOf(IdCard.composeSingle(frontBytes, paper))
                        backBytes?.let { list += IdCard.composeSingle(it, paper) }
                        list
                    }
                }
                // One IO hop: create then hash in the same block, so the result
                // screen still opens after the single idle the UI tests use.
                val (doc, identity) = withContext(Dispatchers.IO) {
                    val created = repo.createDocument(getString(R.string.tools_id_card_name), pages)
                    created to ToolResultActivity.captureOutputIdentityBlocking(
                        this@IdCardActivity, listOf(created.id), emptyList()
                    )
                }
                progress.isVisible = false
                clearCapturedState()
                startActivity(ToolResultActivity.intent(this@IdCardActivity, "id_card",
                    docIds = listOf(doc.id), outputIdentity = identity))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.isVisible = false
                creating = false
                fail(getString(R.string.tools_id_failed, e.message ?: ""))
            }
        }
    }

    private fun fail(message: String) {
        errorView.text = message
        errorView.isVisible = true
    }

    override fun onDestroy() {
        cropBitmap?.takeIf { !it.isRecycled }?.recycle()
        cropBitmap = null
        // N1: the flow really ended (Back, finished task, Home), so no picked card photo may
        // stay in the cache: crop.jpg, front.jpg and back.jpg all go. A configuration change
        // (and a system-recreated process) keeps them for the restored screen.
        if (isFinishing && !isChangingConfigurations) clearCapturedState()
        super.onDestroy()
    }

    companion object {
        /** Tests: hold the reopened crop decode so Back or a second recreation happens first. */
        @Volatile
        internal var cropReopenGate: (() -> Unit)? = null

        private const val STATE_DIR = "id_card_state"
        private const val FRONT_FILE = "front.jpg"
        private const val BACK_FILE = "back.jpg"
        private const val CROP_FILE = "crop.jpg"
        private const val KEY_HAS_STATE = "id_card_has_state"
        private const val KEY_PENDING_SIDE = "id_card_pending_side"
        private const val KEY_CROP_PENDING = "id_card_crop_pending"
        private const val KEY_CROP_SIDE = "id_card_crop_side"
        private const val KEY_CROP_DETECTED = "id_card_crop_detected"
        private const val KEY_CROP_RECT = "id_card_crop_rect"
        private const val KEY_CROP_SOURCE_URI = "id_card_crop_source_uri"
        // T1: paths only. The JPEG bytes themselves are written at capture time on IO.
        private const val KEY_FRONT_PATH = "t1_id_front_path"
        private const val KEY_BACK_PATH = "t1_id_back_path"
        private const val KEY_CROP_PATH = "t1_id_crop_path"

        /** The crop screen never needs more than a phone-width copy of the card. */
        private const val CROP_PREVIEW_DIM = 2400

        fun intent(context: Context): Intent = Intent(context, IdCardActivity::class.java)
    }
}
