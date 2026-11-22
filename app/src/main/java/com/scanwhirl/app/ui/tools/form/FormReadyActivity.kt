package com.scanwhirl.app.ui.tools.form

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.CropView
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.tools.ExactResize
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormCrop
import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.SignatureCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * FORM1: prepare a photo, signature, thumb impression or declaration for an Indian exam/government
 * form, fully offline.
 *
 * A preset is an EDITABLE EXAMPLE, never a promise of portal acceptance: the screen leads with
 * "Check your form's exact rules" and the measured result says the true byte count, pixel size,
 * DPI and whether the file landed inside the requested KB band. The person picks a photo through
 * SAF (no new permission), then this screen makes a fresh JPEG in its own cache. The picked
 * original is never written to.
 *
 * FORMPIX: exam forms need PIXELS and a KB band, not a face-cutting crop. The default "Fit whole
 * photo" mode feeds the entire EXIF-normalised source to [ExactKbEncoder], which scales it once
 * and letterboxes in white. Crop is opt-in and uses the shared snap-to-aspect path.
 *
 * The lead wires the Tools entry later; this activity only exposes [intent] and [TOOL_ID].
 */
class FormReadyActivity : BaseActivity() {

    private lateinit var cropView: CropView
    private lateinit var widthField: EditText
    private lateinit var heightField: EditText
    private lateinit var minField: EditText
    private lateinit var maxField: EditText
    private lateinit var bandErrorView: TextView
    private lateinit var cleanupBox: CheckBox
    private lateinit var savePresetButton: TextView
    private lateinit var usePresetButton: TextView
    private lateinit var cleanupPreview: View
    private lateinit var previewOriginal: ImageView
    private lateinit var previewCleaned: ImageView
    private lateinit var readout: TextView
    private lateinit var example: TextView
    private lateinit var pickButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var errorView: TextView
    private lateinit var progress: View
    private lateinit var noticeView: TextView
    private lateinit var aboutToggle: TextView
    private lateinit var aboutDetails: View
    private lateinit var contentColumn: LinearLayout
    private lateinit var presetStatusView: TextView
    private lateinit var applyBar: FrameLayout

    private val presetChips = mutableMapOf<String, TextView>()
    private val dpiChips = mutableMapOf<Int, TextView>()
    private val unitChips = mutableMapOf<FormUnit, TextView>()
    private lateinit var examButton: TextView
    private lateinit var modeFitChip: TextView
    private lateinit var modeCropChip: TextView

    /** FORMPIX: Fit is the default for every preset; Crop is an explicit secondary action. */
    private enum class FrameMode { FIT, CROP }
    private var frameMode: FrameMode = FrameMode.FIT

    /** Lane F1: one durable id for this operation; the output name is derived from it. */
    private var opId: String = UUID.randomUUID().toString()

    private var encodeJob: Job? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0
    private var previewOriginalBitmap: Bitmap? = null
    private var previewCleanedBitmap: Bitmap? = null

    /** True while a worker still reads the preview bitmap; onDestroy must not recycle it then. */
    @Volatile
    private var bitmapInUse = false
    @Volatile
    private var destroyed = false

    private var spec: FormPresets.Spec = FormPresets.PHOTO
    private var unit: FormUnit = FormUnit.PX
    private var widthValue = 200f
    private var heightValue = 230f
    private var minKb = 20
    private var maxKb = 50
    private var dpi = FormPresets.DPI_200
    private var cleanup = false

    private var bitmap: Bitmap? = null
    private var imagePath: String? = null
    /** UX_RULES_SCANNER §7: the human name the person picked, used to name the saved file. */
    private var sourceName: String = "photo"
    private var busy = false
    private var settingFields = false

    private var lastOutput: File? = null
    /** P2 pre-review: the exact source-pixel rectangles used by preview and export. */
    private var lastPreviewRect: RectF? = null
    private var lastExportRect: RectF? = null

    /**
     * SEC2 N3: once an output is handed to the result screen, that screen owns
     * the cache file; this activity must not delete bytes it still needs.
     */
    private var outputHandedOff = false

    /** Tests: the exact note given to the shared result screen after a run. */
    @Volatile
    internal var lastNoteForTest: String? = null

    /** Lane F2: the accessible status announced whenever a source starts preparing. */
    internal var lastAnnouncedForTest: String? = null

    /** Lane F1: how many times a worker had to recycle a bitmap after onDestroy had run. */
    internal var deferredBitmapRecyclesForTest: Int = 0

    private fun announcePreparing() {
        val message = getString(R.string.form1_preparing)
        lastAnnouncedForTest = message
        progress.contentDescription = message
        progress.announceForAccessibility(message)
    }

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            showPickPrompt()
        } else {
            // Lane F1: never read gallery bytes on the main thread, and never read an unbounded
            // stream. The resolver call and the bounded read both happen on the worker.
            val name = displayNameOf(uri) ?: "photo"
            showPreparing()
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { FormPresets.readBounded(it) }
                    }.getOrNull()
                }
                progress.isVisible = false
                if (bytes == null) showPickFailure() else acceptPickedBytes(bytes, name)
            }
        }
    }

    /** SAF display name without a storage permission; a missing column falls back to "photo". */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun showPreparing() {
        progress.isVisible = true
        announcePreparing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        if (savedInstanceState == null) {
            // PM-3: Resize can hand a true min/max band over to Form photo. Consume it once on a
            // fresh launch; rotation below restores the exact fields from the saved state.
            if (!applyIncomingFormExtras()) selectPreset(FormPresets.PHOTO)
        } else {
            // restoreState applies the aspect BEFORE the async image load. Setting it afterwards
            // races the decode: when the image arrived first, CropView's aspect setter re-fitted
            // the restored rectangle and silently moved the user's crop after a rotation.
            restoreState(savedInstanceState)
        }
    }

    // ---- screen -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(SwStyle.color(this@FormReadyActivity, R.color.void_abyss))
            applySystemBarsPadding()
        }
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@FormReadyActivity, 18f), SwStyle.dp(this@FormReadyActivity, 10f),
                SwStyle.dp(this@FormReadyActivity, 18f), SwStyle.dp(this@FormReadyActivity, 18f)
            )
        }
        contentColumn = column
        scroll.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            setOnClickListener { finish() }
        })
        bar.addView(SwStyle.text(this, 22f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
            id = R.id.formReadyTitle
            text = getString(R.string.form1_title)
            maxLines = Int.MAX_VALUE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = SwStyle.dp(this@FormReadyActivity, 10f)
        })
        column.addView(bar)

        // COPY_DIET: one S14 summary, no cyan preamble. The odd live-photo and cm caveats live
        // behind the About control below; validation and measured results stay on screen.
        noticeView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_secondary)).apply {
            id = R.id.formReadyNotice
            text = getString(R.string.form1_notice)
            maxLines = Int.MAX_VALUE
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, SwStyle.dp(this@FormReadyActivity, 4f))
        }
        column.addView(noticeView)

        // The long caveat lives behind one small control, so the always-visible warning stays two
        // lines at 100 % font. The control is a real 48 dp touch target and speaks its state.
        aboutToggle = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_action_accent), bold = true).apply {
            id = R.id.formReadyAboutToggle
            text = getString(R.string.form1_about_requirements)
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleAbout() }
        }
        column.addView(aboutToggle)
        wireAboutA11y()
        aboutDetails = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_secondary)).apply {
            id = R.id.formReadyAboutDetails
            text = getString(R.string.form1_notice_details) + "\n" + getString(R.string.form1_photo_cm_note)
            maxLines = Int.MAX_VALUE
            setLineSpacing(0f, 1.25f)
            isVisible = false
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 4f), 0, SwStyle.dp(this@FormReadyActivity, 10f))
        }
        column.addView(aboutDetails)

        // FORMPIX: one compact Exam chooser instead of six always-visible chips, so the 40-word
        // screen budget is kept. The menu lists the NEET examples with notice KB bands.
        examButton = modeChip(R.id.formReadyExam, R.string.form1_exam_label) { showExamMenu(it) }
        examButton.contentDescription = getString(R.string.form1_exam_choose)
        examButton.background = SwStyle.rounded(
            this, SwStyle.color(this, R.color.glass_surface), 12f
        )
        column.addView(examButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 10f) })

        // FORMPIX: Fit whole photo is the default. Crop is a secondary action that only shows the
        // frame editor when the person asks for it.
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        modeFitChip = modeChip(R.id.formReadyModeFit, R.string.form1_mode_fit) { setFrameMode(FrameMode.FIT) }
        modeCropChip = modeChip(R.id.formReadyModeCrop, R.string.form1_mode_crop) { setFrameMode(FrameMode.CROP) }
        for (chip in listOf(modeFitChip, modeCropChip)) {
            chipA11y(chip)
            modeRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
            })
        }
        column.addView(modeRow)

        // Council §1: preset names stay in full; a two-column grid (not shorter words) fixes the
        // mid-word break at 100%. Five presets -> rows of 2 / 2 / 1.
        val presets = listOf(
            Triple(R.id.formReadyPresetPhoto, FormPresets.PHOTO, R.string.form1_preset_photo),
            Triple(R.id.formReadyPresetSignature, FormPresets.SIGNATURE, R.string.form1_preset_signature),
            Triple(R.id.formReadyPresetThumb, FormPresets.THUMB, R.string.form1_preset_thumb),
            Triple(R.id.formReadyPresetDeclaration, FormPresets.DECLARATION, R.string.form1_preset_declaration),
            Triple(R.id.formReadyPresetCustom, FormPresets.CUSTOM, R.string.form1_preset_custom)
        )
        for ((rowId, defs) in listOf(
            R.id.formReadyPresetRow1 to presets.subList(0, 2),
            R.id.formReadyPresetRow2 to presets.subList(2, 4),
            R.id.formReadyPresetRow3 to presets.subList(4, 5)
        )) {
            val row = LinearLayout(this).apply {
                id = rowId
                orientation = LinearLayout.HORIZONTAL
            }
            for ((id, spec, labelRes) in defs) {
                val chip = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                    this.id = id
                    text = getString(labelRes)
                    gravity = Gravity.CENTER
                    maxLines = Int.MAX_VALUE
                    minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selectPreset(spec) }
                }
                presetChips[spec.id] = chip
                chipA11y(chip)
                row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (id != defs.last().first) marginEnd = SwStyle.dp(this@FormReadyActivity, 8f)
                })
            }
            column.addView(row)
        }

        // Critique: the primary next action sits immediately under the presets, not after a long
        // scroll of fields. It disappears as soon as a photo has been picked.
        pickButton = action(getString(R.string.form1_pick), R.id.formReadyPick, true) { pick.launch("image/*") }
        column.addView(pickButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 10f) })

        val exampleLabel = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_secondary)).apply {
            text = getString(R.string.form1_example_label)
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
        }
        column.addView(exampleLabel)
        example = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
            id = R.id.formReadyExample
            maxLines = Int.MAX_VALUE
        }
        column.addView(example)

        val dimLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        dimLabels.addView(fieldLabel(R.id.formReadyWidthLabel, R.string.form1_width_label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
            })
        dimLabels.addView(fieldLabel(R.id.formReadyHeightLabel, R.string.form1_height_label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(dimLabels)

        val dims = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        widthField = numberField(R.id.formReadyWidth, R.string.form1_width_hint)
        heightField = numberField(R.id.formReadyHeight, R.string.form1_height_hint)
        dims.addView(widthField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
        })
        dims.addView(heightField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(dims)

        column.addView(fieldLabel(R.id.formReadyUnitLabel, R.string.form1_unit_label).apply {
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
        })
        val units = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((u, id, spoken) in listOf(
            Triple(FormUnit.PX, R.id.formReadyUnitPx, R.string.form1_unit_px_spoken),
            Triple(FormUnit.CM, R.id.formReadyUnitCm, R.string.form1_unit_cm_spoken),
            Triple(FormUnit.MM, R.id.formReadyUnitMm, R.string.form1_unit_mm_spoken),
            Triple(FormUnit.INCH, R.id.formReadyUnitInch, R.string.form1_unit_inch_spoken)
        )) {
            val chip = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
                this.id = id
                text = u.name.lowercase()
                contentDescription = getString(spoken)
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                isClickable = true
                isFocusable = true
                setOnClickListener { changeUnit(u) }
            }
            unitChips[u] = chip
            chipA11y(chip)
            units.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 4f)
            })
        }
        column.addView(units)

        val bandLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        bandLabels.addView(fieldLabel(R.id.formReadyMinLabel, R.string.form1_min_label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
            })
        bandLabels.addView(fieldLabel(R.id.formReadyMaxLabel, R.string.form1_max_label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bandLabels)

        val bands = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        minField = numberField(R.id.formReadyMinKb, R.string.form1_min_hint)
        maxField = numberField(R.id.formReadyMaxKb, R.string.form1_max_hint)
        bands.addView(minField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
        })
        bands.addView(maxField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bands)

        bandErrorView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_danger), bold = true).apply {
            id = R.id.formReadyBandError
            isVisible = false
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 4f), 0, 0)
        }
        column.addView(bandErrorView)

        val dpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        dpiRow.addView(SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
            text = "DPI"
        }, LinearLayout.LayoutParams(SwStyle.dp(this, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        for ((d, id) in listOf(
            FormPresets.DPI_100 to R.id.formReadyDpi100,
            FormPresets.DPI_200 to R.id.formReadyDpi200,
            FormPresets.DPI_300 to R.id.formReadyDpi300
        )) {
            val chip = SwStyle.text(this, 16f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
                this.id = id
                text = d.toString()
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                isClickable = true
                isFocusable = true
                setOnClickListener { changeDpi(d) }
            }
            dpiChips[d] = chip
            chipA11y(chip)
            dpiRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@FormReadyActivity, 4f)
            })
        }
        column.addView(dpiRow)

        cleanupBox = CheckBox(this).apply {
            id = R.id.formReadyCleanup
            text = getString(R.string.form1_cleanup)
            setTextColor(SwStyle.color(this@FormReadyActivity, R.color.f2_text_primary))
            buttonTintList = ColorStateList.valueOf(SwStyle.color(this@FormReadyActivity, R.color.f2_action_accent))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
            setOnCheckedChangeListener { _, checked ->
                if (!settingFields) {
                    cleanup = checked
                    refreshCleanupPreview()
                }
            }
        }
        column.addView(cleanupBox)

        // Lane F2: a local save/use pair. No account, no cloud, no permission.
        val presetActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        savePresetButton = action(getString(R.string.form1_save_preset), R.id.formReadySavePreset, false) { savePreset() }
        usePresetButton = action(getString(R.string.form1_use_preset), R.id.formReadyUsePreset, false) { useSavedPreset() }
        presetActions.addView(savePresetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
        })
        presetActions.addView(usePresetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(presetActions)

        // Feedback stays beside the preset buttons in the neutral text colour. It used to be the
        // red error view at the very bottom, where it was both alarming and clipped off screen.
        presetStatusView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_secondary)).apply {
            id = R.id.formReadyPresetStatus
            isVisible = false
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 4f), 0, 0)
        }
        column.addView(presetStatusView)

        // Lane F2 / council §4: before/after on a dark canvas, shown before cleanup is applied.
        cleanupPreview = LinearLayout(this).apply {
            id = R.id.formReadyCleanupPreview
            orientation = LinearLayout.HORIZONTAL
            isVisible = false
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
            contentDescription = context.getString(R.string.form1_cleanup_preview)
        }
        previewOriginal = addPreviewCell(cleanupPreview as LinearLayout, R.id.formReadyPreviewOriginal, R.string.form1_preset_original)
        previewCleaned = addPreviewCell(cleanupPreview as LinearLayout, R.id.formReadyPreviewCleaned, R.string.form1_preset_cleaned)
        column.addView(cleanupPreview)

        readout = SwStyle.text(this, 16f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
            id = R.id.formReadyReadout
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        column.addView(readout)

        cropView = CropView(this).apply {
            id = R.id.formReadyCrop
            // Critique: an empty canvas is not a preview. Only show it once a photo exists.
            isVisible = false
            // Council §4: a dark canvas hosts the page; never a white page host.
            background = SwStyle.rounded(
                this@FormReadyActivity, SwStyle.color(this@FormReadyActivity, R.color.rv_surface_2),
                radiusDp = 14f
            )
        }
        column.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@FormReadyActivity, 220f)
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 12f) })

        // Critique: Make file is pinned to the bottom of the screen only when the form is
        // actionable. It lives outside the ScrollView, so it can never scroll out of reach.
        applyBar = FrameLayout(this).apply {
            id = R.id.formReadyApplyBar
            isVisible = false
            setBackgroundColor(SwStyle.color(this@FormReadyActivity, R.color.void_abyss))
            setPadding(
                SwStyle.dp(this@FormReadyActivity, 18f), SwStyle.dp(this@FormReadyActivity, 8f),
                SwStyle.dp(this@FormReadyActivity, 18f), SwStyle.dp(this@FormReadyActivity, 10f)
            )
        }
        applyButton = action(getString(R.string.form1_apply), R.id.formReadyApply, true) { apply() }
        applyBar.addView(applyButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(applyBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        setApplyEnabled(false)

        errorView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_danger), bold = true).apply {
            id = R.id.formReadyError
            isVisible = false
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
        }
        column.addView(errorView)

        progress = FrameLayout(this).apply {
            id = R.id.formReadyProgress
            isVisible = false
            isClickable = true
            setBackgroundColor(0xCC0E1316.toInt())
            addView(ProgressBar(this@FormReadyActivity), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        root.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return root
    }

    private fun fieldLabel(id: Int, labelRes: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.f2_text_primary), bold = true).apply {
            this.id = id
            text = getString(labelRes)
            maxLines = Int.MAX_VALUE
        }

    private fun addPreviewCell(parent: LinearLayout, id: Int, labelRes: Int): ImageView {
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val image = ImageView(this).apply {
            this.id = id
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 96f)
            background = SwStyle.rounded(
                this@FormReadyActivity, SwStyle.color(this@FormReadyActivity, R.color.rv_surface_2),
                radiusDp = 12f
            )
        }
        cell.addView(image, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@FormReadyActivity, 110f)
        ))
        cell.addView(SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(labelRes)
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 4f)
        })
        return image
    }

    private fun numberField(id: Int, hintRes: Int): EditText = EditText(this).apply {
        this.id = id
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(SwStyle.color(this@FormReadyActivity, R.color.f2_text_primary))
        setHintTextColor(SwStyle.color(this@FormReadyActivity, R.color.f2_text_secondary))
        minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
        val pad = SwStyle.dp(this@FormReadyActivity, 12f)
        setPadding(pad, SwStyle.dp(this@FormReadyActivity, 8f), pad, SwStyle.dp(this@FormReadyActivity, 8f))
        background = SwStyle.rounded(
            this@FormReadyActivity, SwStyle.color(this@FormReadyActivity, R.color.rv_surface_2),
            radiusDp = 12f, stroke = SwStyle.color(this@FormReadyActivity, R.color.sw_cyan_hair)
        )
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (settingFields) return
                when (this@apply.id) {
                    R.id.formReadyWidth -> {
                        widthValue = s?.toString()?.toFloatOrNull() ?: 0f
                        updateCropAspect() // crop follows the edited dimensions
                    }
                    R.id.formReadyHeight -> {
                        heightValue = s?.toString()?.toFloatOrNull() ?: 0f
                        updateCropAspect()
                    }
                    R.id.formReadyMinKb -> minKb = s?.toString()?.toIntOrNull() ?: 0
                    R.id.formReadyMaxKb -> maxKb = s?.toString()?.toIntOrNull() ?: 0
                }
                refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    /**
     * TalkBack announces which preset/unit/DPI chip is active. [refresh] writes the current state
     * into the chip tag; the delegate reads it so it stays correct after every edit and rotation.
     */
    private fun chipA11y(chip: TextView) {
        ViewCompat.setAccessibilityDelegate(chip, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val on = host.tag as? Boolean == true
                // F2: these are single-choice sets, so announce a radio, not a bare checkable.
                info.className = android.widget.RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = on
                info.stateDescription = host.context.getString(
                    if (on) R.string.form1_selected else R.string.form1_not_selected
                )
            }
        })
    }

    /** FORMPIX: a compact single-choice chip; [refresh] paints the active one. */
    private fun modeChip(id: Int, labelRes: Int, onClick: () -> Unit): TextView =
        SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            this.id = id
            text = getString(labelRes)
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun action(text: String, id: Int, primary: Boolean, onClick: () -> Unit): TextView =
        SwStyle.asButton(SwStyle.text(this, 14f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.f2_text_primary
        ), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 50f)
            background = SwStyle.rounded(
                this@FormReadyActivity,
                SwStyle.color(this@FormReadyActivity, if (primary) R.color.cyan_primary else R.color.glass_surface),
                radiusDp = 16f
            )
            setOnClickListener { onClick() }
        })

    private fun toggleAbout() {
        val expanded = aboutDetails.visibility != View.VISIBLE
        aboutDetails.isVisible = expanded
        aboutToggle.tag = expanded
        aboutToggle.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun wireAboutA11y() {
        aboutToggle.tag = false
        ViewCompat.setAccessibilityDelegate(aboutToggle, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                info.stateDescription = host.context.getString(
                    if (host.tag as? Boolean == true) R.string.form1_expanded else R.string.form1_collapsed
                )
            }
        })
    }

    /** One owner for the Make file state, its 48 dp pin, and the room reserved beneath the form. */
    private fun setApplyEnabled(enabled: Boolean) {
        applyButton.isEnabled = enabled
        applyButton.alpha = if (enabled) 1f else 0.5f
        applyBar.isVisible = enabled
        contentColumn.setPadding(
            contentColumn.paddingLeft, contentColumn.paddingTop, contentColumn.paddingRight,
            SwStyle.dp(this, if (enabled) 92f else 18f)
        )
    }

    private fun isSignatureMode(): Boolean = FormPresets.isSignatureLike(spec.id)

    /** FORMPIX: the frame editor is only shown when Crop is chosen AND a source exists. */
    private fun syncCropVisibility() {
        cropView.isVisible = frameMode == FrameMode.CROP && hasImageForTest()
    }

    private fun setFrameMode(mode: FrameMode) {
        if (frameMode == mode) return
        frameMode = mode
        // Going back to Fit hides the editor; its stale frame is ignored by export.
        syncCropVisibility()
        refresh()
    }

    /** FORMPIX: the exam list lives behind one control to keep the screen under 40 words. */
    private fun showExamMenu(anchor: View) {
        val specs = FormPresets.EXAM
        val labels = listOf(
            R.string.form1_exam_ug_photo, R.string.form1_exam_ug_signature,
            R.string.form1_exam_ug_thumb, R.string.form1_exam_pg_photo,
            R.string.form1_exam_pg_signature, R.string.form1_exam_pg_thumb
        )
        PopupMenu(this, anchor).apply {
            specs.forEachIndexed { i, spec -> menu.add(0, i, i, getString(labels[i])) }
            setOnMenuItemClickListener { item ->
                selectPreset(specs[item.itemId])
                true
            }
            show()
        }
    }

    /**
     * FORMPIX: the exact source pixels this run uses. Fit keeps the whole EXIF-normalised source
     * (the encoder letterboxes it uniformly); Crop uses the existing snapped-rect path.
     */
    private fun activeSourceRect(): Rect {
        val source = bitmap?.takeIf { !it.isRecycled } ?: return Rect()
        if (frameMode == FrameMode.FIT) return Rect(0, 0, source.width, source.height)
        val rect = cropView.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return Rect()
        val (w, h) = currentPixels()
        return FormCrop.snapToAspect(rect, w.toFloat() / h.coerceAtLeast(1), source.width, source.height)
    }

    /** Each broken rule names itself; a decimal is not silently rounded into a zero KB limit. */
    private fun kbError(): String? {
        val minText = minField.text.toString().trim()
        val maxText = maxField.text.toString().trim()
        if (minText.isBlank() || maxText.isBlank()) return getString(R.string.form1_kb_blank)
        val min = minText.toIntOrNull()
        val max = maxText.toIntOrNull()
        val decimal = listOf(minText, maxText).any { it.any { c -> c == '.' || c == ',' } }
        if (min == null || max == null || decimal) return getString(R.string.form1_kb_decimal)
        if (min < 1) return getString(R.string.form1_kb_min_nonpositive)
        // A nonpositive maximum is the same broken rule as a maximum below the (at least 1) floor.
        val lower = maxOf(min, 1)
        if (max < lower) return getString(R.string.form1_kb_max_below_min, lower)
        return null
    }

    // ---- state and live readout -------------------------------------------

    /**
     * PM-3: ExactResize.formPhotoIntent sends width/height and an optional max KB. Refuse anything
     * that cannot be a real form photo instead of silently clipping it, and initialise Custom so
     * the person sees exactly what was handed over. Returns false when nothing usable was sent.
     */
    private fun applyIncomingFormExtras(): Boolean {
        val w = intent.getIntExtra(ExactResize.EXTRA_WIDTH, 0)
        val h = intent.getIntExtra(ExactResize.EXTRA_HEIGHT, 0)
        if (w <= 0 || h <= 0 || !FormPresets.fitsFormLimit(w, h)) return false
        val max = if (intent.hasExtra(ExactResize.EXTRA_MAX_KB)) {
            intent.getIntExtra(ExactResize.EXTRA_MAX_KB, 0)
        } else {
            0
        }
        if (max != 0 && max !in 1..10_000) return false
        spec = FormPresets.CUSTOM
        frameMode = FrameMode.FIT
        unit = FormUnit.PX
        dpi = FormPresets.DPI_200
        widthValue = w.toFloat()
        heightValue = h.toFloat()
        minKb = 1
        maxKb = if (max > 0) max else FormPresets.CUSTOM.maxKb
        cleanup = false
        writeFields()
        updateCropAspect()
        refresh()
        return true
    }

    private fun selectPreset(preset: FormPresets.Spec) {
        spec = preset
        // FORMPIX: every preset starts in Fit whole photo. Crop is a deliberate extra step.
        frameMode = FrameMode.FIT
        unit = FormUnit.PX
        widthValue = preset.widthPx.toFloat()
        heightValue = preset.heightPx.toFloat()
        dpi = preset.dpi
        minKb = preset.minKb
        maxKb = preset.maxKb
        cleanup = preset.signatureCleanup
        writeFields()
        updateCropAspect()
        // FX2: switching to Signature reframes the ink; switching back centres on the whole image.
        val live = bitmap?.takeIf { !it.isRecycled }
        val focus = if (isSignatureMode() && live != null) SignatureCleanup.inkBounds(live) else null
        cropView.setDefaultFocus(focus)
        if (live != null) cropView.setImage(live)
        syncCropVisibility()
        refresh()
    }

    private fun writeFields() {
        settingFields = true
        widthField.setText(formatValue(widthValue))
        heightField.setText(formatValue(heightValue))
        minField.setText(minKb.toString())
        maxField.setText(maxKb.toString())
        cleanupBox.isChecked = cleanup
        settingFields = false
    }

    private fun formatValue(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", value)

    private fun currentPixels(): Pair<Int, Int> =
        FormPresets.toPixels(widthValue.coerceAtLeast(1f), unit, dpi) to
            FormPresets.toPixels(heightValue.coerceAtLeast(1f), unit, dpi)

    /** F1: editing dimensions must re-shape the crop frame; the aspect setter is CropView's API. */
    private fun updateCropAspect() {
        val (w, h) = currentPixels()
        cropView.aspectRatio = w.toFloat() / h.coerceAtLeast(1)
    }

    /** F1: changing unit converts the numbers, so a 3.5 cm entry stays 3.5 cm, not 3.5 px. */
    private fun changeUnit(next: FormUnit) {
        if (next == unit) return
        val (wPx, hPx) = currentPixels()
        unit = next
        widthValue = FormPresets.fromPixels(wPx, next, dpi)
        heightValue = FormPresets.fromPixels(hPx, next, dpi)
        writeFields()
        updateCropAspect()
        refresh()
    }

    /** F1: a DPI change keeps the requested pixels by re-expressing a physical size. */
    private fun changeDpi(next: Int) {
        if (next == dpi) return
        val (wPx, hPx) = currentPixels()
        dpi = next
        if (unit != FormUnit.PX) {
            widthValue = FormPresets.fromPixels(wPx, unit, dpi)
            heightValue = FormPresets.fromPixels(hPx, unit, dpi)
        }
        writeFields()
        updateCropAspect()
        refresh()
    }

    private fun savePreset() {
        FormPresets.saveEditable(this, FormPresets.Editable(
            widthValue, heightValue, unit, dpi, minKb, maxKb, cleanup
        ))
        presetStatusView.text = getString(R.string.form1_preset_saved)
        presetStatusView.setTextColor(SwStyle.color(this, R.color.f2_text_secondary))
        presetStatusView.isVisible = true
        refresh()
    }

    private fun useSavedPreset() {
        val saved = FormPresets.loadEditable(this)
        if (saved == null) {
            // Local state is P, but never the danger colour: a missing preset is not an error.
            presetStatusView.text = getString(R.string.form1_no_preset)
            presetStatusView.setTextColor(SwStyle.color(this, R.color.f2_text_primary))
            presetStatusView.isVisible = true
            return
        }
        presetStatusView.isVisible = false
        frameMode = FrameMode.FIT
        unit = saved.unit
        dpi = saved.dpi
        minKb = saved.minKb
        maxKb = saved.maxKb
        cleanup = saved.cleanup
        widthValue = saved.width
        heightValue = saved.height
        writeFields()
        updateCropAspect()
        refresh()
    }

    /**
     * PM-2: Bitmap.createBitmap/createScaledBitmap may return the INPUT bitmap itself when the
     * rectangle already matches it. Never recycle or display an alias of the source or of a
     * preview the ImageView owns; give each owner an independently mutable copy.
     */
    private fun ownedCopy(source: Bitmap, candidate: Bitmap): Bitmap =
        if (candidate === source) {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        } else {
            candidate
        }

    /** F2: compute a small original/processed pair on a worker; apply never uses this copy. */
    private fun refreshCleanupPreview() {
        previewJob?.cancel()
        val generation = ++previewGeneration
        val source = bitmap
        // FORMPIX: preview and export share one source rect, so Fit previews the whole photo and
        // Crop previews the snapped frame. It cannot disagree with what apply() saves.
        val snapped = activeSourceRect()
        if (!cleanup || source == null || source.isRecycled || bitmapInUse ||
            snapped.width() < 1 || snapped.height() < 1
        ) {
            releasePreviewBitmaps()
            cleanupPreview.isVisible = false
            return
        }
        lastPreviewRect = RectF(snapped)
        val left = snapped.left
        val top = snapped.top
        val width = snapped.width()
        val height = snapped.height()
        // The copy is made on the caller's thread while the source is known live; the bounded
        // scaling and thresholding run on a worker.
        val rawRegion = runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull()
        if (rawRegion == null) {
            cleanupPreview.isVisible = false
            return
        }
        // PM-2: an immutable source whose crop covers the whole image aliases rawRegion.
        val region = ownedCopy(source, rawRegion)
        previewJob = lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val scale = (240f / maxOf(width, height).toFloat()).coerceAtMost(1f)
                    val pw = (width * scale).toInt().coerceAtLeast(1)
                    val ph = (height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(region, pw, ph, true)
                    // PM-2: when the region is already at preview size this returns the region
                    // itself; the ImageView and the worker finally block must not own the same
                    // pixels. SignatureCleanup always returns a fresh bitmap.
                    val original = ownedCopy(region, scaled)
                    val cleaned = SignatureCleanup.clean(original)
                    original to cleaned
                }
                if (generation != previewGeneration || previewJob?.isActive != true || destroyed) {
                    pair.first.recycle()
                    pair.second.recycle()
                    return@launch
                }
                releasePreviewBitmaps()
                previewOriginalBitmap = pair.first
                previewCleanedBitmap = pair.second
                previewOriginal.setImageBitmap(pair.first)
                previewCleaned.setImageBitmap(pair.second)
                cleanupPreview.isVisible = true
            } finally {
                region.recycle()
            }
        }
    }

    private fun releasePreviewBitmaps() {
        previewOriginalBitmap?.takeIf { !it.isRecycled }?.recycle()
        previewCleanedBitmap?.takeIf { !it.isRecycled }?.recycle()
        previewOriginalBitmap = null
        previewCleanedBitmap = null
        previewOriginal.setImageBitmap(null)
        previewCleaned.setImageBitmap(null)
    }

    private fun refresh() {
        val (w, h) = currentPixels()
        val tooLarge = !FormPresets.fitsFormLimit(w, h)
        readout.text = getString(R.string.form1_readout, w, h)
        // FORMPIX: exam pixels are a suggestion from guides; the official part is the KB band.
        example.text = if (FormPresets.isExam(spec.id)) {
            getString(R.string.form1_example_exam, spec.widthPx, spec.heightPx, spec.minKb, spec.maxKb)
        } else {
            getString(R.string.form1_example, spec.widthPx, spec.heightPx, spec.minKb, spec.maxKb)
        }
        // An impossible target is refused before any bitmap is allocated.
        val sizeMessage = getString(R.string.form1_size_too_large)
        if (tooLarge) {
            errorView.text = sizeMessage
            errorView.isVisible = true
        } else if (errorView.text.toString() == sizeMessage) {
            errorView.isVisible = false
            errorView.text = ""
        }
        // F1 + critique: blank, decimal, nonpositive and reversed KB each name the rule that
        // failed; none of them is silently turned into zero and sent to the encoder.
        val bandMessage = kbError()
        if (bandMessage == null) {
            bandErrorView.isVisible = false
            bandErrorView.text = ""
        } else {
            bandErrorView.text = bandMessage
            bandErrorView.isVisible = true
        }
        // Critique: the cleanup control and its effect belong to Signature mode only.
        cleanupBox.isVisible = isSignatureMode()
        if (!isSignatureMode() && cleanup) {
            settingFields = true
            cleanup = false
            cleanupBox.isChecked = false
            settingFields = false
        }
        usePresetButton.isEnabled = FormPresets.loadEditable(this) != null
        setApplyEnabled(hasImageForTest() && !tooLarge && bandMessage == null)
        refreshCleanupPreview()
        val hi = SwStyle.color(this, R.color.text_hologram_hi)
        val ink = SwStyle.color(this, R.color.sw_disc_ink)
        val cyan = SwStyle.color(this, R.color.cyan_primary)
        val surface = SwStyle.color(this, R.color.rv_surface_2)
        fun paint(chip: TextView, on: Boolean) {
            chip.tag = on
            chip.isSelected = on
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }
        for ((id, chip) in presetChips) paint(chip, id == spec.id)
        paint(modeFitChip, frameMode == FrameMode.FIT)
        paint(modeCropChip, frameMode == FrameMode.CROP)
        for ((d, chip) in dpiChips) {
            val on = d == dpi
            chip.tag = on
            chip.isSelected = on
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }
        for ((u, chip) in unitChips) {
            val on = u == unit
            chip.tag = on
            chip.isSelected = on
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SPEC, spec.id)
        outState.putString(KEY_UNIT, unit.name)
        outState.putFloat(KEY_W, widthValue)
        outState.putFloat(KEY_H, heightValue)
        outState.putInt(KEY_MIN, minKb)
        outState.putInt(KEY_MAX, maxKb)
        outState.putInt(KEY_DPI, dpi)
        outState.putBoolean(KEY_CLEANUP, cleanup)
        outState.putBoolean(KEY_MODE, frameMode == FrameMode.CROP)
        outState.putString(KEY_OP_ID, opId)
        outState.putString(KEY_SOURCE_NAME, sourceName)
        imagePath?.let { outState.putString(KEY_IMAGE, it) }
        val rect = cropView.getCropRect()
        if (rect.width() > 0f && rect.height() > 0f) {
            outState.putFloatArray(KEY_RECT, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
        }
    }

    private fun restoreState(state: Bundle) {
        spec = FormPresets.byId(state.getString(KEY_SPEC).orEmpty())
        unit = runCatching { FormUnit.valueOf(state.getString(KEY_UNIT).orEmpty()) }.getOrDefault(FormUnit.PX)
        widthValue = state.getFloat(KEY_W, spec.widthPx.toFloat())
        heightValue = state.getFloat(KEY_H, spec.heightPx.toFloat())
        minKb = state.getInt(KEY_MIN, spec.minKb)
        maxKb = state.getInt(KEY_MAX, spec.maxKb)
        dpi = state.getInt(KEY_DPI, spec.dpi)
        cleanup = state.getBoolean(KEY_CLEANUP, spec.signatureCleanup)
        frameMode = if (state.getBoolean(KEY_MODE, false)) FrameMode.CROP else FrameMode.FIT
        opId = state.getString(KEY_OP_ID).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        sourceName = state.getString(KEY_SOURCE_NAME).takeUnless { it.isNullOrBlank() } ?: "photo"
        writeFields()
        refresh()
        // Apply the aspect while the crop view has no image: the setter cannot re-fit anything
        // yet. setImage() then fits the frame once, and setCropRect() restores the exact frame.
        cropView.aspectRatio = currentPixels().let { (w, h) -> w.toFloat() / h }
        val path = state.getString(KEY_IMAGE)
        val rect = state.getFloatArray(KEY_RECT)
        if (path == null) {
            showPickPrompt()
            return
        }
        imagePath = path
        progress.isVisible = true
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    File(path).takeIf { it.isFile && it.length() in 1L..FormPresets.MAX_SOURCE_BYTES }
                        ?.inputStream()?.use { FormPresets.readBounded(it) }
                }.getOrNull()
                if (bytes == null) {
                    null
                } else {
                    val (bmp, uprightBytes) = ImageTools.decodeFormSource(bytes, 2400)
                    // The pick cache stores upright bytes; if this cache predates FX2 the decode
                    // above still normalises them, so the rect is always in upright source space.
                    runCatching { writePickCache(uprightBytes) }
                    val focus = if (isSignatureMode()) SignatureCleanup.inkBounds(bmp) else null
                    bmp to focus
                }
            }
            progress.isVisible = false
            if (decoded == null) {
                showPickFailure()
                return@launch
            }
            bitmap = decoded.first
            cropView.setDefaultFocus(decoded.second)
            cropView.setImage(decoded.first)
            syncCropVisibility()
            if (rect != null && rect.size == 4) cropView.setCropRect(RectF(rect[0], rect[1], rect[2], rect[3]))
            refresh()
            pickButton.isVisible = false
        }
    }

    // ---- picked photo -----------------------------------------------------

    private fun showPickPrompt() {
        setApplyEnabled(false)
        syncCropVisibility()
        pickButton.isVisible = true
    }

    private fun showPickFailure() {
        errorView.text = getString(R.string.form1_pick_failed)
        errorView.isVisible = true
        showPickPrompt()
    }

    /** Test seam for the SAF picker (which cannot run under Robolectric) and its real callback. */
    internal fun acceptPickedBytes(bytes: ByteArray, name: String? = null) {
        if (!name.isNullOrBlank()) sourceName = name
        showPreparing()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val (decoded, uprightBytes) = ImageTools.decodeFormSource(bytes, 2400)
                val path = writePickCache(uprightBytes)
                if (path == null) {
                    decoded.recycle()
                    null
                } else {
                    imagePath = path
                    // FX2: only a signature frames its ink; a photo centres on the whole image.
                    val focus = if (isSignatureMode()) SignatureCleanup.inkBounds(decoded) else null
                    decoded to focus
                }
            }
            progress.isVisible = false
            val decoded = loaded?.first
            if (decoded == null) {
                showPickFailure()
                return@launch
            }
            if (!bitmapInUse) bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = decoded
            cropView.setDefaultFocus(loaded.second)
            cropView.setImage(decoded)
            syncCropVisibility()
            updateCropAspect()
            errorView.isVisible = false
            pickButton.isVisible = false
            refresh() // re-checks the size cap before enabling Apply
        }
    }

    private fun writePickCache(bytes: ByteArray): String? = runCatching {
        val dir = File(cacheDir, "form1_pick").apply { mkdirs() }
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

    // ---- encode and hand to the shared result screen ----------------------

    private fun apply() {
        if (busy) return
        val source = bitmap ?: return
        // FORMPIX: Fit exports the WHOLE source (the encoder scales once and letterboxes); only
        // Crop uses the confirmed snapped frame. No mode adds a forced crop or a stretch.
        val rect = activeSourceRect()
        if (rect.width() < 1 || rect.height() < 1) return
        lastExportRect = RectF(rect)
        val (targetW, targetH) = currentPixels()
        if (!FormPresets.fitsFormLimit(targetW, targetH)) {
            errorView.text = getString(R.string.form1_size_too_large)
            errorView.isVisible = true
            setApplyEnabled(false)
            return
        }
        val dpiNow = dpi
        val minNow = minKb
        val maxNow = maxKb
        val minBytes = minNow * FormPresets.BYTES_PER_KB
        val maxBytes = maxNow * FormPresets.BYTES_PER_KB
        val doCleanup = cleanup && isSignatureMode()

        busy = true
        bitmapInUse = true
        showPreparing()
        encodeJob?.cancel()
        encodeJob = lifecycleScope.launch {
            try {
                val (result, file, identity) = withContext(Dispatchers.IO) {
                    val rawRegion = Bitmap.createBitmap(
                        source, rect.left, rect.top, rect.width(), rect.height()
                    )
                    // PM-2: crop == whole immutable source => rawRegion === source. Recycle only
                    // our own copy, never the bitmap CropView and Make file still display.
                    val region = ownedCopy(source, rawRegion)
                    val cleaned = if (doCleanup) SignatureCleanup.clean(region) else null
                    try {
                        val encoded = ExactKbEncoder.encode(
                            cleaned ?: region, targetW, targetH, dpiNow, minBytes, maxBytes,
                            isCancelled = { encodeJob?.isCancelled == true || destroyed }
                        )
                        // One run directory per capture. The former exists()-then-write name in
                        // the shared form1_out root was not run-unique, so an old receipt could
                        // name another run's output. Only the directory is new: the final file
                        // name, photo bytes and encoder are unchanged.
                        val dir = File(File(cacheDir, "form1_out"), UUID.randomUUID().toString())
                            .apply { mkdirs() }
                        // UX_RULES_SCANNER §7: a human name derived from the picked file.
                        val out = File(dir, uniqueOutputName(dir))
                        Durable.writeFile(out, encoded.bytes)
                        // Producer-time F: identity of the final saved bytes, captured on IO
                        // right after the write (never later, at Undo).
                        val identity = ToolResultActivity.captureOutputIdentityBlocking(
                            this@FormReadyActivity, emptyList(), listOf(out)
                        )
                        Triple(encoded, out, identity)
                    } finally {
                        cleaned?.recycle()
                        region.recycle()
                    }
                }
                progress.isVisible = false
                busy = false
                lastOutput = file
                // FX2: every number is measured from the bytes actually published.
                val note = buildNote(file, targetW, targetH, dpiNow, minNow, maxNow)
                lastNoteForTest = note
                startActivity(ToolResultActivity.intent(
                    this@FormReadyActivity, TOOL_ID,
                    files = listOf(file), mime = "image/jpeg", note = note,
                    outputIdentity = identity
                ))
                // SEC2 N3: ownership moves to the result screen before this exits.
                outputHandedOff = true
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // The phone cannot hold this rendition. Free the preview on every path and say
                // so in the app's own words instead of letting the process die.
                cropView.clearImage()
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                bitmap = null
                syncCropVisibility()
                errorView.text = getString(R.string.form1_out_of_memory)
                errorView.isVisible = true
                refresh()
            } catch (e: Exception) {
                errorView.text = getString(R.string.form1_encode_failed)
                errorView.isVisible = true
            } finally {
                // F1: onDestroy must never recycle a bitmap a worker still holds. When destroy
                // won the race, the worker releases it here instead.
                bitmapInUse = false
                if (destroyed) {
                    source.takeIf { !it.isRecycled }?.recycle()
                    if (bitmap === source) bitmap = null
                    deferredBitmapRecyclesForTest++
                }
                progress.isVisible = false
                busy = false
            }
        }
    }

    /** UX_RULES_SCANNER §7: "<source> signature.jpg" / "<source> photo.jpg", collisions " (2)". */
    private fun uniqueOutputName(dir: File): String {
        val base = sourceName.substringBeforeLast('.').trim()
            .ifBlank { "photo" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
        val stem = "$base ${FormPresets.outputRole(spec.id)}"
        var candidate = "$stem.jpg"
        var n = 2
        while (File(dir, candidate).exists()) {
            candidate = "$stem ($n).jpg"
            n++
        }
        return candidate
    }

    /**
     * FX2 / UX_RULES_SCANNER: one hero line (measured pixels + measured KB from the SAVED file)
     * and one status per failed check, each with numbers and an OK/X marker. No repeated receipt
     * lines, no "kept" size that disagrees with the file. Below-minimum is always X, never "ready".
     */
    private fun buildNote(
        file: File,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedDpi: Int,
        min: Int,
        max: Int
    ): String {
        val savedBytes = file.length()
        val raw = file.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val dpiNow = ExactKbEncoder.readJfifDpi(raw)
        val sizeText = FormPresets.formatKb(savedBytes)
        val bandText = getString(R.string.form1_band_short, min, max)
        val lines = mutableListOf(
            getString(R.string.form1_result_hero, w, h, sizeText)
        )
        var ready = true
        if (w != requestedWidth || h != requestedHeight) {
            ready = false
            lines += getString(R.string.form1_result_px_bad, w, h, requestedWidth, requestedHeight)
        }
        if (dpiNow != requestedDpi) {
            ready = false
            lines += getString(R.string.form1_result_dpi_bad, dpiNow ?: 0, requestedDpi)
        }
        if (savedBytes !in (min * FormPresets.BYTES_PER_KB)..(max * FormPresets.BYTES_PER_KB)) {
            ready = false
            lines += getString(R.string.form1_result_band_bad, sizeText, bandText, dpiNow ?: 0)
        }
        if (ready) {
            lines += getString(R.string.form1_result_ok, sizeText, requestedDpi)
        }
        return lines.joinToString("\n")
    }

    override fun onDestroy() {
        destroyed = true
        previewJob?.cancel()
        previewGeneration++
        releasePreviewBitmaps()
        cropView.clearImage()
        // F1: an encode worker may still be reading this bitmap. Leave it to the worker's
        // finally block instead of recycling under it.
        if (!bitmapInUse) {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = null
        }
        if (isFinishing && !isChangingConfigurations) {
            // SEC2 N3: a picked plaintext copy is never needed after the flow ends.
            // An output that was handed off belongs to the result screen; an
            // abandoned one is safe to remove here. Rotation keeps both so a
            // process death inside the flow can still restore the work.
            runCatching { File(cacheDir, "form1_pick").deleteRecursively() }
            if (!outputHandedOff) {
                runCatching { File(cacheDir, "form1_out").deleteRecursively() }
            }
        }
        super.onDestroy()
    }

    // ---- test seams -------------------------------------------------------

    /** The decoded preview is present once the picked photo has been read. */
    internal fun hasImageForTest(): Boolean = bitmap?.takeIf { !it.isRecycled } != null
    /** FX2: the EXIF-normalised source the crop frame lives in. */
    internal fun sourceBitmapForTest(): Bitmap? = bitmap?.takeIf { !it.isRecycled }
    internal fun currentWidthPxForTest(): Int = currentPixels().first
    internal fun currentHeightPxForTest(): Int = currentPixels().second
    internal fun currentMinKbForTest(): Int = minKb
    internal fun currentMaxKbForTest(): Int = maxKb
    internal fun currentDpiForTest(): Int = dpi
    internal fun currentCleanupForTest(): Boolean = cleanup
    internal fun currentUnitForTest(): FormUnit = unit
    internal fun previewVisibleForTest(): Boolean = cleanupPreview.isVisible
    internal fun cropVisibleForTest(): Boolean = cropView.isVisible
    internal fun cleanupVisibleForTest(): Boolean = cleanupBox.isVisible
    internal fun pinnedApplyVisibleForTest(): Boolean = applyBar.isVisible
    internal fun presetStatusTextForTest(): String = presetStatusView.text.toString()
    internal fun noticeTextForTest(): String = noticeView.text.toString()
    internal fun opIdForTest(): String = opId
    internal fun lastOutputForTest(): File? = lastOutput
    internal fun lastOutputNameForTest(): String? = lastOutput?.name

    internal fun buildNoteForTest(
        result: ExactKbEncoder.Result, width: Int, height: Int, dpi: Int
    ): String {
        val file = File(cacheDir, "form1_note_test_${opId}.jpg")
        Durable.writeFile(file, result.bytes)
        return try {
            buildNote(file, width, height, dpi, minKb, maxKb)
        } finally {
            runCatching { file.delete() }
        }
    }

    /** FX2: the human output name, exposed for the numeric naming gate. */
    internal fun outputNameForTest(): String = uniqueOutputName(File(cacheDir, "form1_out"))

    internal fun currentSpecIdForTest(): String = spec.id
    /** FORMPIX: true only after the person opts into Crop; Fit is the default everywhere. */
    internal fun cropModeForTest(): Boolean = frameMode == FrameMode.CROP
    internal fun setCropModeForTest(crop: Boolean) =
        setFrameMode(if (crop) FrameMode.CROP else FrameMode.FIT)
    internal fun activeSourceRectForTest(): RectF = RectF(activeSourceRect())
    internal fun previewCropRectForTest(): RectF? = lastPreviewRect
    internal fun exportCropRectForTest(): RectF? = lastExportRect
    internal fun previewBitmapsForTest(): List<Bitmap> =
        listOfNotNull(previewOriginalBitmap, previewCleanedBitmap)

    /**
     * PM-2: install a caller-owned (possibly immutable) bitmap exactly as a picked decode would,
     * so a test can prove that cleanup/export never recycle or alias it.
     */
    internal fun useBitmapForTest(bitmap: Bitmap, name: String? = null) {
        if (!name.isNullOrBlank()) sourceName = name
        this.bitmap = bitmap
        cropView.setDefaultFocus(if (isSignatureMode()) SignatureCleanup.inkBounds(bitmap) else null)
        cropView.setImage(bitmap)
        syncCropVisibility()
        updateCropAspect()
        refresh()
    }

    /** Test seams for the confirmed crop frame (the view keeps it in image pixels). */
    internal fun setCropRectForTest(rect: RectF) = cropView.setCropRect(rect)
    internal fun cropRectForTest(): RectF = cropView.getCropRect()

    /** Real signal that the crop view has published its image and rect, not just our own field. */
    internal fun cropReadyForTest(): Boolean = !cropView.getCropRect().isEmpty

    /** Test seam for editable custom values, mirroring what the fields do. */
    internal fun setCustomValuesForTest(w: Float, h: Float, u: FormUnit, d: Int, min: Int, max: Int) {
        unit = u
        dpi = d
        minKb = min
        maxKb = max
        widthValue = w
        heightValue = h
        writeFields()
        refresh()
    }

    companion object {
        /** The lead wires this id into the Tools hub; no registry file is edited in this lane. */
        const val TOOL_ID = "form_ready"

        private const val KEY_SPEC = "form1_spec"
        private const val KEY_UNIT = "form1_unit"
        private const val KEY_W = "form1_width"
        private const val KEY_H = "form1_height"
        private const val KEY_MIN = "form1_min"
        private const val KEY_MAX = "form1_max"
        private const val KEY_DPI = "form1_dpi"
        private const val KEY_CLEANUP = "form1_cleanup"
        private const val KEY_MODE = "form1_frame_mode"
        private const val KEY_OP_ID = "form1_op_id"
        private const val KEY_IMAGE = "form1_image"
        private const val KEY_RECT = "form1_rect"
        private const val KEY_SOURCE_NAME = "form1_source_name"

        fun intent(context: Context): Intent = Intent(context, FormReadyActivity::class.java)
    }
}
