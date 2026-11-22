package com.scanwhirl.app.ui.tools.form

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
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
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.FormUnit
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.SignatureCleanup
import com.scanwhirl.app.util.SizeUnits
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
 * SAF (no new permission), confirms the aspect on the existing [CropView], then this screen makes
 * a fresh JPEG in its own cache. The picked original is never written to.
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
    private var busy = false
    private var settingFields = false

    private var lastOutput: File? = null

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
            showPreparing()
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { FormPresets.readBounded(it) }
                    }.getOrNull()
                }
                progress.isVisible = false
                if (bytes == null) showPickFailure() else acceptPickedBytes(bytes)
            }
        }
    }

    private fun showPreparing() {
        progress.isVisible = true
        announcePreparing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        if (savedInstanceState == null) {
            selectPreset(FormPresets.PHOTO)
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

    private fun isSignatureMode(): Boolean = spec.id == "signature"

    private fun showCrop(show: Boolean) {
        cropView.isVisible = show
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

    private fun selectPreset(preset: FormPresets.Spec) {
        spec = preset
        unit = FormUnit.PX
        widthValue = preset.widthPx.toFloat()
        heightValue = preset.heightPx.toFloat()
        dpi = preset.dpi
        minKb = preset.minKb
        maxKb = preset.maxKb
        cleanup = preset.signatureCleanup
        writeFields()
        updateCropAspect()
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

    /** F2: compute a small original/processed pair on a worker; apply never uses this copy. */
    private fun refreshCleanupPreview() {
        previewJob?.cancel()
        val generation = ++previewGeneration
        val source = bitmap
        val rect = cropView.getCropRect()
        if (!cleanup || source == null || source.isRecycled || bitmapInUse ||
            rect.width() < 1f || rect.height() < 1f
        ) {
            releasePreviewBitmaps()
            cleanupPreview.isVisible = false
            return
        }
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        // The copy is made on the caller's thread while the source is known live; the bounded
        // scaling and thresholding run on a worker.
        val region = runCatching { Bitmap.createBitmap(source, left, top, width, height) }.getOrNull()
        if (region == null) {
            cleanupPreview.isVisible = false
            return
        }
        previewJob = lifecycleScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val scale = (240f / maxOf(width, height).toFloat()).coerceAtMost(1f)
                    val pw = (width * scale).toInt().coerceAtLeast(1)
                    val ph = (height * scale).toInt().coerceAtLeast(1)
                    val original = Bitmap.createScaledBitmap(region, pw, ph, true)
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
        example.text = getString(R.string.form1_example, spec.widthPx, spec.heightPx, spec.minKb, spec.maxKb)
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
        for ((id, chip) in presetChips) {
            val on = id == spec.id
            chip.tag = on
            chip.isSelected = on
            chip.setTextColor(if (on) ink else hi)
            chip.background = SwStyle.rounded(this, if (on) cyan else surface, 12f)
        }
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
        outState.putString(KEY_OP_ID, opId)
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
        opId = state.getString(KEY_OP_ID).takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
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
                runCatching {
                    File(path).takeIf { it.isFile && it.length() in 1L..FormPresets.MAX_SOURCE_BYTES }
                        ?.inputStream()?.use { FormPresets.readBounded(it) }
                }.getOrNull()
                    ?.let { runCatching { ImageTools.decodeSampled(it, 2400) }.getOrNull() }
            }
            progress.isVisible = false
            if (decoded == null) {
                showPickFailure()
                return@launch
            }
            bitmap = decoded
            cropView.setImage(decoded)
            showCrop(true)
            if (rect != null && rect.size == 4) cropView.setCropRect(RectF(rect[0], rect[1], rect[2], rect[3]))
            refresh()
            pickButton.isVisible = false
        }
    }

    // ---- picked photo -----------------------------------------------------

    private fun showPickPrompt() {
        setApplyEnabled(false)
        showCrop(false)
        pickButton.isVisible = true
    }

    private fun showPickFailure() {
        errorView.text = getString(R.string.form1_pick_failed)
        errorView.isVisible = true
        showPickPrompt()
    }

    /** Test seam for the SAF picker (which cannot run under Robolectric) and its real callback. */
    internal fun acceptPickedBytes(bytes: ByteArray) {
        showPreparing()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val upright = runCatching { ImageTools.normalizeGalleryPhoto(bytes) }.getOrDefault(bytes)
                val path = writePickCache(upright)
                if (path == null) {
                    null to true
                } else {
                    imagePath = path
                    runCatching { ImageTools.decodeSampled(upright, 2400) }.getOrNull() to false
                }
            }
            progress.isVisible = false
            val decoded = loaded.first
            if (decoded == null) {
                showPickFailure()
                return@launch
            }
            if (!bitmapInUse) bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = decoded
            cropView.setImage(decoded)
            showCrop(true)
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
        val rect = cropView.getCropRect()
        if (rect.width() < 1f || rect.height() < 1f) return
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val width = rect.width().toInt().coerceIn(1, source.width - left)
        val height = rect.height().toInt().coerceIn(1, source.height - top)
        val (targetW, targetH) = currentPixels()
        if (!FormPresets.fitsFormLimit(targetW, targetH)) {
            errorView.text = getString(R.string.form1_size_too_large)
            errorView.isVisible = true
            setApplyEnabled(false)
            return
        }
        val dpiNow = dpi
        val minBytes = minKb * SizeUnits.BYTES_PER_KB
        val maxBytes = maxKb * SizeUnits.BYTES_PER_KB
        val doCleanup = cleanup && isSignatureMode()

        busy = true
        bitmapInUse = true
        showPreparing()
        encodeJob?.cancel()
        encodeJob = lifecycleScope.launch {
            try {
                val (result, file) = withContext(Dispatchers.IO) {
                    val region = Bitmap.createBitmap(source, left, top, width, height)
                    val cleaned = if (doCleanup) SignatureCleanup.clean(region) else null
                    try {
                        val encoded = ExactKbEncoder.encode(
                            cleaned ?: region, targetW, targetH, dpiNow, minBytes, maxBytes,
                            isCancelled = { encodeJob?.isCancelled == true || destroyed }
                        )
                        val dir = File(cacheDir, "form1_out").apply { mkdirs() }
                        // F1: a durable operation id, not wall-clock time, names the output. A
                        // recreation keeps the same id and cannot produce a second random file.
                        val out = File(dir, "form_ready_${opId}.jpg")
                        Durable.writeFile(out, encoded.bytes)
                        encoded to out
                    } finally {
                        cleaned?.recycle()
                        region.recycle()
                    }
                }
                progress.isVisible = false
                busy = false
                lastOutput = file
                val note = buildNote(result, targetW, targetH, dpiNow)
                lastNoteForTest = note
                startActivity(ToolResultActivity.intent(
                    this@FormReadyActivity, TOOL_ID,
                    files = listOf(file), mime = "image/jpeg", note = note
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
                showCrop(false)
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                bitmap = null
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

    /**
     * F1: pixel, DPI and KB band are three separate checks. Landing inside the KB band alone is
     * NOT overall readiness: the requested pixels and the written DPI must match too.
     */
    private fun buildNote(
        result: ExactKbEncoder.Result,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedDpi: Int
    ): String {
        // COPY_DIET: one measured numeric row, exact bytes on its own detail line, no duplicate
        // "as requested" sentences. The three checks still gate readiness independently.
        val measured = getString(
            R.string.form1_note_measured_short,
            SizeUnits.format(result.sizeBytes), result.width, result.height, result.dpi
        )
        val bytes = getString(R.string.form1_note_bytes, result.bytes.size)
        val pixelCheck = if (result.width == requestedWidth && result.height == requestedHeight) {
            null
        } else {
            getString(R.string.form1_result_pixels_changed, result.width, result.height)
        }
        val writtenDpi = ExactKbEncoder.readJfifDpi(result.bytes)
        val dpiCheck = if (writtenDpi == requestedDpi) {
            null
        } else {
            getString(R.string.form1_result_dpi_changed, writtenDpi ?: 0, requestedDpi)
        }
        val bandCheck = when (result.outcome) {
            ExactKbEncoder.Outcome.IN_BAND -> getString(
                R.string.form1_note_in_band,
                SizeUnits.format(minKb * SizeUnits.BYTES_PER_KB),
                SizeUnits.format(maxKb * SizeUnits.BYTES_PER_KB)
            )
            ExactKbEncoder.Outcome.BELOW_MIN -> getString(
                R.string.form1_min_miss, SizeUnits.format(minKb * SizeUnits.BYTES_PER_KB)
            )
            ExactKbEncoder.Outcome.ABOVE_MAX -> getString(R.string.form1_max_miss)
        }
        val bandLabel = getString(R.string.form1_band_label)
        val problems = mutableListOf<String>()
        pixelCheck?.let { problems += it }
        dpiCheck?.let { problems += it }
        if (result.outcome != ExactKbEncoder.Outcome.IN_BAND) problems += bandCheck
        val overall = if (problems.isEmpty()) {
            getString(R.string.form1_result_ready)
        } else {
            getString(R.string.form1_result_not_ready, problems.joinToString(" "))
        }
        val lines = mutableListOf(measured, bytes)
        pixelCheck?.let { lines += it }
        dpiCheck?.let { lines += it }
        lines += "$bandLabel $bandCheck"
        if (result.outcome == ExactKbEncoder.Outcome.BELOW_MIN) {
            lines += getString(R.string.form1_min_miss_constraint)
        }
        lines += overall
        lines += getString(R.string.form1_source_kept)
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
    ): String = buildNote(result, width, height, dpi)

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
        private const val KEY_OP_ID = "form1_op_id"
        private const val KEY_IMAGE = "form1_image"
        private const val KEY_RECT = "form1_rect"

        fun intent(context: Context): Intent = Intent(context, FormReadyActivity::class.java)
    }
}
