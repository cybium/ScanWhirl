package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.L3FailureCopy
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PassportOutputOptions
import com.scanwhirl.app.util.SizeUnits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * FF-P: Passport delivers the right asset for the job, fully offline.
 *
 * The person first says whether they need a **photo file** or a **print sheet**:
 *  - Photo file: an editable exact-pixel example (mission 630 × 810 px with a dated source, or a
 *    custom size with an optional KB band) is encoded through the shared [ExactKbEncoder]. The
 *    result is a real `.jpg`; a missed KB band is said out loud, never hidden.
 *  - Print sheet: the crop is placed into exact 35 × 45 mm boxes on a physical A4 (or 10 × 15 cm)
 *    PDF page, so the printed size does not depend on a viewer's "fit" scaling.
 *
 * The picked original is never written to. The existing crop surface and rotation-safe cache are
 * kept unchanged; this screen adds only the output choice and the two honest receipts.
 */
class PassportActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var cropView: CropView
    private lateinit var applyButton: TextView
    private lateinit var pickPrompt: TextView
    private lateinit var errorView: TextView
    private lateinit var progress: View

    private lateinit var needPhotoButton: TextView
    private lateinit var needPrintButton: TextView
    private lateinit var photoOptions: LinearLayout
    private lateinit var printOptions: LinearLayout
    private lateinit var missionChip: TextView
    private lateinit var customChip: TextView
    private lateinit var sourceNote: TextView
    private lateinit var widthField: EditText
    private lateinit var heightField: EditText
    private lateinit var minField: EditText
    private lateinit var maxField: EditText
    private lateinit var bandError: TextView
    private lateinit var sheetA4Chip: TextView
    private lateinit var sheet10x15Chip: TextView

    private var bitmap: Bitmap? = null
    private var busy = false

    /** Cache path of the upright picked photo; the Bundle only carries this string. */
    @Volatile
    private var imagePath: String? = null

    private var need = PassportOutputOptions.Need.PHOTO_FILE
    private var photoSpec: PassportOutputOptions.PhotoSpec = PassportOutputOptions.MISSION
    private var sheet = PassportOutputOptions.Sheet.A4
    private var customWidth = PassportOutputOptions.MISSION_WIDTH
    private var customHeight = PassportOutputOptions.MISSION_HEIGHT
    private var customMinKb: Int? = null
    private var customMaxKb: Int? = null
    private var opId: String = UUID.randomUUID().toString()

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            showPickPrompt()
        } else {
            load(uri)
        }
    }

    /** The file, MIME type and honest receipt handed to the shared result screen. */
    internal data class Output(val file: File, val mime: String, val note: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
        if (savedInstanceState == null) {
            refreshOptions()
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
        outState.putString(KEY_NEED, need.name)
        outState.putString(KEY_SPEC_ID, photoSpec.id)
        outState.putInt(KEY_CUSTOM_W, customWidth)
        outState.putInt(KEY_CUSTOM_H, customHeight)
        outState.putInt(KEY_CUSTOM_MIN, customMinKb ?: -1)
        outState.putInt(KEY_CUSTOM_MAX, customMaxKb ?: -1)
        outState.putString(KEY_SHEET, sheet.name)
        outState.putString(KEY_OP_ID, opId)
    }

    // ---- screen -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@PassportActivity, 18f), SwStyle.dp(this@PassportActivity, 10f),
                SwStyle.dp(this@PassportActivity, 18f), SwStyle.dp(this@PassportActivity, 18f)
            )
        }
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

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
                maxLines = Int.MAX_VALUE
                text = getString(R.string.tool_passport)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@PassportActivity, 10f)
            }
        )
        column.addView(bar)

        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            text = getString(R.string.ffp_centre_note)
            maxLines = Int.MAX_VALUE
            setLineSpacing(0f, 1.2f)
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, SwStyle.dp(this@PassportActivity, 8f))
        })

        column.addView(SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(R.string.ffp_what_need)
            maxLines = Int.MAX_VALUE
        })
        val needs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, 0)
        }
        needPhotoButton = optionButton(R.string.ffp_need_photo) { setNeed(PassportOutputOptions.Need.PHOTO_FILE) }
        needPrintButton = optionButton(R.string.ffp_need_print) { setNeed(PassportOutputOptions.Need.PRINT_SHEET) }
        needs.addView(needPhotoButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@PassportActivity, 6f)
        })
        needs.addView(needPrintButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(needs)

        // Photo-file options: a dated example, editable pixels, optional decimal KB band.
        photoOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 10f), 0, 0)
        }
        val examples = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        missionChip = optionButton(R.string.ffp_example_mission) { useMission() }
        customChip = optionButton(R.string.ffp_example_custom) { useCustom() }
        examples.addView(missionChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@PassportActivity, 6f)
        })
        examples.addView(customChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        photoOptions.addView(examples)

        sourceNote = SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            maxLines = Int.MAX_VALUE
            setLineSpacing(0f, 1.2f)
            setPadding(0, SwStyle.dp(this@PassportActivity, 6f), 0, 0)
        }
        photoOptions.addView(sourceNote)

        val dimLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, 0)
        }
        dimLabels.addView(label(R.string.ffp_width_label), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = SwStyle.dp(this@PassportActivity, 6f) })
        dimLabels.addView(label(R.string.ffp_height_label), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        photoOptions.addView(dimLabels)
        val dims = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        widthField = numberField()
        heightField = numberField()
        dims.addView(widthField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@PassportActivity, 6f)
        })
        dims.addView(heightField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        photoOptions.addView(dims)

        val bandLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, 0)
        }
        bandLabels.addView(label(R.string.ffp_min_kb_label), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = SwStyle.dp(this@PassportActivity, 6f) })
        bandLabels.addView(label(R.string.ffp_max_kb_label), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        photoOptions.addView(bandLabels)
        val bands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        minField = numberField()
        maxField = numberField()
        bands.addView(minField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@PassportActivity, 6f)
        })
        bands.addView(maxField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        photoOptions.addView(bands)

        bandError = SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.laser_danger)).apply {
            maxLines = Int.MAX_VALUE
            isVisible = false
            setPadding(0, SwStyle.dp(this@PassportActivity, 4f), 0, 0)
        }
        photoOptions.addView(bandError)
        column.addView(photoOptions)

        // Print-sheet options: the physical sheet, never a bare aspect ratio labelled "mm".
        printOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 10f), 0, 0)
        }
        printOptions.addView(label(R.string.ffp_sheet_label))
        val sheets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@PassportActivity, 8f), 0, 0)
        }
        sheetA4Chip = optionButton(R.string.ffp_sheet_a4) { setSheet(PassportOutputOptions.Sheet.A4) }
        sheet10x15Chip = optionButton(R.string.ffp_sheet_10x15) { setSheet(PassportOutputOptions.Sheet.CM_10X15) }
        sheets.addView(sheetA4Chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@PassportActivity, 6f)
        })
        sheets.addView(sheet10x15Chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        printOptions.addView(sheets)
        printOptions.addView(SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            text = getString(R.string.ffp_box_note)
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@PassportActivity, 6f), 0, 0)
        })
        column.addView(printOptions)

        column.addView(SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.ffp_manual_framing)
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@PassportActivity, 10f), 0, 0)
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
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 6f) })

        cropView = CropView(this).apply {
            id = R.id.toolCropView
            background = SwStyle.rounded(this@PassportActivity, 0xFF000000.toInt(), radiusDp = 14f)
        }
        column.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@PassportActivity, 260f)
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 10f) })

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolResultError
            isVisible = false
            maxLines = Int.MAX_VALUE
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 8f) })

        applyButton = SwStyle.text(this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            id = R.id.toolCropApply
            text = getString(R.string.ffp_apply_photo)
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
            isEnabled = false
            alpha = 0.5f
            background = SwStyle.rounded(this@PassportActivity, SwStyle.color(this@PassportActivity, R.color.cyan_primary), radiusDp = 16f)
            setOnClickListener { apply() }
        }
        column.addView(applyButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@PassportActivity, 54f)
        ).apply { topMargin = SwStyle.dp(this@PassportActivity, 14f) })

        root.addView(FrameLayout(this).apply {
            id = R.id.toolSetupProgress
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        progress = root.findViewById(R.id.toolSetupProgress)
        progress.isVisible = false
        progress.isClickable = true
        progress.setBackgroundColor(0xCC0E1316.toInt())
        (progress as FrameLayout).addView(ProgressBar(this@PassportActivity), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        root.applySystemBarsPadding()
        return root
    }

    private fun label(res: Int): TextView =
        SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_mid), bold = true).apply {
            text = getString(res)
            maxLines = Int.MAX_VALUE
        }

    private fun optionButton(res: Int, onClick: () -> Unit): TextView =
        SwStyle.asButton(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(res)
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@PassportActivity, 48f)
            setOnClickListener { onClick() }
        })

    private fun numberField(): EditText = EditText(this).apply {
        hint = getString(R.string.ffp_band_hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        setTextColor(SwStyle.color(this@PassportActivity, R.color.text_hologram_hi))
        setHintTextColor(SwStyle.color(this@PassportActivity, R.color.text_hologram_dim))
        minimumHeight = SwStyle.dp(this@PassportActivity, 48f)
        val pad = SwStyle.dp(this@PassportActivity, 12f)
        setPadding(pad, SwStyle.dp(this@PassportActivity, 8f), pad, SwStyle.dp(this@PassportActivity, 8f))
        background = SwStyle.rounded(
            this@PassportActivity, SwStyle.color(this@PassportActivity, R.color.rv_surface_2),
            radiusDp = 12f, stroke = SwStyle.color(this@PassportActivity, R.color.sw_cyan_hair)
        )
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // ---- output choice ----------------------------------------------------

    private fun setNeed(next: PassportOutputOptions.Need) {
        if (need == next) return
        need = next
        refreshOptions()
    }

    private fun useMission() {
        photoSpec = PassportOutputOptions.MISSION
        writeFields()
        refreshOptions()
    }

    private fun useCustom() {
        photoSpec = PassportOutputOptions.custom(customWidth, customHeight, customMinKb, customMaxKb)
        writeFields()
        refreshOptions()
    }

    private fun setSheet(next: PassportOutputOptions.Sheet) {
        if (sheet == next) return
        sheet = next
        refreshOptions()
    }

    private fun writeFields() {
        widthField.setText(photoSpec.widthPx.toString())
        heightField.setText(photoSpec.heightPx.toString())
        minField.setText(photoSpec.minKb?.toString().orEmpty())
        maxField.setText(photoSpec.maxKb?.toString().orEmpty())
    }

    private fun typedKb(field: EditText): Int? = field.text?.toString()?.trim()?.toIntOrNull()

    private fun refreshOptions(applyAspect: Boolean = true) {
        val hi = SwStyle.color(this, R.color.text_hologram_hi)
        val ink = SwStyle.color(this, R.color.sw_disc_ink)
        val cyan = SwStyle.color(this, R.color.cyan_primary)
        val surface = SwStyle.color(this, R.color.rv_surface_2)

        needPhotoButton.tag = need == PassportOutputOptions.Need.PHOTO_FILE
        needPrintButton.tag = need == PassportOutputOptions.Need.PRINT_SHEET
        for (chip in listOf(needPhotoButton, needPrintButton)) {
            val on = chip.tag as Boolean
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }

        photoOptions.isVisible = need == PassportOutputOptions.Need.PHOTO_FILE
        printOptions.isVisible = need == PassportOutputOptions.Need.PRINT_SHEET
        applyButton.text = getString(
            if (need == PassportOutputOptions.Need.PHOTO_FILE) R.string.ffp_apply_photo
            else R.string.ffp_apply_print
        )

        val mission = photoSpec.id == "mission"
        missionChip.tag = mission
        customChip.tag = !mission
        for (chip in listOf(missionChip, customChip)) {
            val on = chip.tag as Boolean
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }
        sourceNote.isVisible = photoOptions.isVisible && mission
        if (mission) sourceNote.text = getString(R.string.ffp_example_mission) + "\n" + getString(R.string.ffp_example_mission_px)

        sheetA4Chip.tag = sheet == PassportOutputOptions.Sheet.A4
        sheet10x15Chip.tag = sheet == PassportOutputOptions.Sheet.CM_10X15
        for (chip in listOf(sheetA4Chip, sheet10x15Chip)) {
            val on = chip.tag as Boolean
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }

        val min = typedKb(minField)
        val max = typedKb(maxField)
        val halfBand = (min == null) != (max == null)
        bandError.isVisible = photoOptions.isVisible && halfBand
        if (halfBand) bandError.text = getString(R.string.ffp_error_band)

        if (applyAspect) {
            cropView.aspectRatio = when (need) {
                PassportOutputOptions.Need.PHOTO_FILE ->
                    photoSpec.widthPx.toFloat() / photoSpec.heightPx.coerceAtLeast(1)
                PassportOutputOptions.Need.PRINT_SHEET ->
                    PassportOutputOptions.PRINT_MM_W / PassportOutputOptions.PRINT_MM_H
            }
        }
        applyButton.isEnabled = bitmap != null && !halfBand
        applyButton.alpha = if (applyButton.isEnabled) 1f else 0.5f
    }

    // ---- picked photo (unchanged T1 rotation-safe path) -------------------

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
            refreshOptions()
        }
    }

    /** Rotation/process death: read the cached upright photo off the main thread and restore the frame. */
    private fun restoreState(state: Bundle) {
        need = runCatching { PassportOutputOptions.Need.valueOf(state.getString(KEY_NEED).orEmpty()) }
            .getOrDefault(PassportOutputOptions.Need.PHOTO_FILE)
        sheet = runCatching { PassportOutputOptions.Sheet.valueOf(state.getString(KEY_SHEET).orEmpty()) }
            .getOrDefault(PassportOutputOptions.Sheet.A4)
        customWidth = state.getInt(KEY_CUSTOM_W, PassportOutputOptions.MISSION_WIDTH)
        customHeight = state.getInt(KEY_CUSTOM_H, PassportOutputOptions.MISSION_HEIGHT)
        customMinKb = state.getInt(KEY_CUSTOM_MIN, -1).takeIf { it > 0 }
        customMaxKb = state.getInt(KEY_CUSTOM_MAX, -1).takeIf { it > 0 }
        val specId = state.getString(KEY_SPEC_ID).orEmpty()
        photoSpec = if (specId == "custom") {
            PassportOutputOptions.custom(customWidth, customHeight, customMinKb, customMaxKb)
        } else {
            PassportOutputOptions.MISSION
        }
        opId = state.getString(KEY_OP_ID).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        writeFields()
        refreshOptions()

        val path = state.getString(KEY_IMAGE_PATH)
        val rect = state.getFloatArray(KEY_CROP_RECT)
        if (path == null) {
            showPickPrompt()
            return
        }
        progress.isVisible = true
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
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
            // Do not re-fit the aspect after restoring the saved frame: that would move the
            // person's crop after a rotation (the same race FormReady guards against).
            refreshOptions(applyAspect = false)
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

    // ---- produce the chosen asset -----------------------------------------

    private fun cropRegion(source: Bitmap): Bitmap? {
        val rect = cropView.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return null
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        return runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull()
    }

    private fun outputDir(): File = File(cacheDir, "ffp_out").apply { mkdirs() }

    private fun buildPhotoFile(region: Bitmap): Output {
        val spec = photoSpec
        val encoded = ExactKbEncoder.encode(
            region, spec.widthPx, spec.heightPx, spec.dpi, spec.minBytes, spec.maxBytes
        )
        val file = File(outputDir(), "passport_photo_${opId}.jpg")
        Durable.atomicWrite(file, encoded.bytes)

        val measured = getString(
            R.string.ffp_note_measured,
            SizeUnits.format(encoded.sizeBytes), encoded.sizeBytes, spec.widthPx, spec.heightPx, spec.dpi
        )
        val band = if (!spec.hasBand) {
            getString(R.string.ffp_note_band_none)
        } else {
            val minText = SizeUnits.format(spec.minBytes)
            val maxText = SizeUnits.format(spec.maxBytes)
            when (encoded.outcome) {
                ExactKbEncoder.Outcome.IN_BAND -> getString(R.string.ffp_note_band_ok, minText, maxText)
                else -> getString(
                    R.string.ffp_note_band_miss,
                    SizeUnits.format(encoded.sizeBytes), minText, maxText
                )
            }
        }
        val note = listOf(
            measured, band, getString(R.string.ffp_note_source_kept), getString(R.string.ffp_note_no_validation)
        ).joinToString("\n")
        return Output(file, PassportOutputOptions.MIME_JPEG, note)
    }

    private fun buildPrintSheet(region: Bitmap): Output {
        val (width, height) = PassportOutputOptions.printPixels()
        val encoded = ExactKbEncoder.encode(
            region, width, height, PassportOutputOptions.PRINT_DPI,
            SizeUnits.BYTES_PER_KB, 10_000L * SizeUnits.BYTES_PER_KB
        )
        val pdf = PassportOutputOptions.buildPrintPdf(encoded.bytes, width, height, sheet)
        val file = File(outputDir(), "passport_print_${opId}.pdf")
        Durable.atomicWrite(file, pdf)
        val layout = PassportOutputOptions.layout(sheet)
        val note = listOf(
            getString(R.string.ffp_note_print, PassportOutputOptions.sheetName(sheet), layout.boxes.size),
            getString(R.string.ffp_note_source_kept),
            getString(R.string.ffp_note_no_validation)
        ).joinToString("\n")
        return Output(file, PassportOutputOptions.MIME_PDF, note)
    }

    private fun buildOutput(): Output {
        val source = bitmap?.takeIf { !it.isRecycled } ?: error("No photo was picked")
        val region = cropRegion(source) ?: error("No crop region")
        return try {
            when (need) {
                PassportOutputOptions.Need.PHOTO_FILE -> buildPhotoFile(region)
                PassportOutputOptions.Need.PRINT_SHEET -> buildPrintSheet(region)
            }
        } finally {
            region.recycle()
        }
    }

    private fun apply() {
        if (busy) return
        if (bitmap == null) return
        busy = true
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val output = withContext(Dispatchers.IO) { buildOutput() }
                progress.isVisible = false
                busy = false
                startActivity(ToolResultActivity.intent(
                    this@PassportActivity, TOOL_ID,
                    files = listOf(output.file), mime = output.mime, note = output.note,
                    sourceKept = true
                ))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                errorView.text = getString(R.string.ffp_out_of_memory)
                errorView.isVisible = true
            } catch (e: Exception) {
                errorView.text = L3FailureCopy.message(this@PassportActivity, e)
                errorView.isVisible = true
            } finally {
                progress.isVisible = false
                busy = false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cropView.clearImage()
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        if (isFinishing && !isChangingConfigurations) {
            runCatching { File(cacheDir, "t1_passport").deleteRecursively() }
        }
    }

    // ---- test seams -------------------------------------------------------

    internal fun loadUriForTest(uri: Uri) = load(uri)

    internal fun acceptPhotoBytesForTest(bytes: ByteArray) {
        val upright = runCatching { ImageTools.normalizeGalleryPhoto(bytes) }.getOrDefault(bytes)
        imagePath = writeImageCache(upright)
        val decoded = ImageTools.decodeSampled(upright, 2400)
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = decoded
        cropView.setImage(decoded)
        errorView.isVisible = false
        pickPrompt.isVisible = false
        refreshOptions()
    }

    internal fun selectNeedForTest(next: PassportOutputOptions.Need) = setNeed(next)
    internal fun selectSheetForTest(next: PassportOutputOptions.Sheet) = setSheet(next)
    internal fun useMissionForTest() = useMission()

    internal fun setCustomForTest(widthPx: Int, heightPx: Int, minKb: Int?, maxKb: Int?) {
        customWidth = widthPx
        customHeight = heightPx
        customMinKb = minKb
        customMaxKb = maxKb
        photoSpec = PassportOutputOptions.custom(widthPx, heightPx, minKb, maxKb)
        writeFields()
        refreshOptions()
    }

    internal fun produceForTest(): Output = runBlocking(Dispatchers.IO) { buildOutput() }

    companion object {
        /** Tests: held inside IO right after the cache path is published. */
        @Volatile
        internal var cacheWriteGate: (() -> Unit)? = null

        const val TOOL_ID = "passport"

        private const val KEY_IMAGE_PATH = "t1_passport_image_path"
        private const val KEY_CROP_RECT = "t1_passport_crop_rect"
        private const val KEY_NEED = "ffp_need"
        private const val KEY_SPEC_ID = "ffp_spec_id"
        private const val KEY_CUSTOM_W = "ffp_custom_w"
        private const val KEY_CUSTOM_H = "ffp_custom_h"
        private const val KEY_CUSTOM_MIN = "ffp_custom_min"
        private const val KEY_CUSTOM_MAX = "ffp_custom_max"
        private const val KEY_SHEET = "ffp_sheet"
        private const val KEY_OP_ID = "ffp_op_id"

        fun intent(context: Context): Intent = Intent(context, PassportActivity::class.java)
    }
}
