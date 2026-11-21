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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private lateinit var cleanupBox: CheckBox
    private lateinit var readout: TextView
    private lateinit var example: TextView
    private lateinit var photoCmNote: TextView
    private lateinit var pickButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var errorView: TextView
    private lateinit var progress: View

    private val presetChips = mutableMapOf<String, TextView>()
    private val dpiChips = mutableMapOf<Int, TextView>()
    private val unitChips = mutableMapOf<FormUnit, TextView>()

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

    /** Tests: the exact note given to the shared result screen after a run. */
    @Volatile
    internal var lastNoteForTest: String? = null

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            showPickPrompt()
        } else {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
            }.getOrNull()
            if (bytes == null) showPickFailure() else acceptPickedBytes(bytes)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        if (savedInstanceState == null) {
            selectPreset(FormPresets.PHOTO)
        } else {
            restoreState(savedInstanceState)
            cropView.aspectRatio = currentPixels().let { it.first.toFloat() / it.second }
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
        bar.addView(SwStyle.text(this, 20f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(R.string.form1_title)
            maxLines = Int.MAX_VALUE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = SwStyle.dp(this@FormReadyActivity, 10f)
        })
        column.addView(bar)

        column.addView(SwStyle.text(this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            text = getString(R.string.form1_check_rules)
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        })
        column.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            text = getString(R.string.form1_notice)
            maxLines = Int.MAX_VALUE
            setLineSpacing(0f, 1.25f)
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, SwStyle.dp(this@FormReadyActivity, 10f))
        })

        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val presetDefs = listOf(
            R.id.formReadyPresetPhoto to (FormPresets.PHOTO to R.string.form1_preset_photo),
            R.id.formReadyPresetSignature to (FormPresets.SIGNATURE to R.string.form1_preset_signature),
            R.id.formReadyPresetThumb to (FormPresets.THUMB to R.string.form1_preset_thumb),
            R.id.formReadyPresetDeclaration to (FormPresets.DECLARATION to R.string.form1_preset_declaration),
            R.id.formReadyPresetCustom to (FormPresets.CUSTOM to R.string.form1_preset_custom)
        )
        for ((id, def) in presetDefs) {
            val chip = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                this.id = id
                text = getString(def.second)
                gravity = Gravity.CENTER
                maxLines = Int.MAX_VALUE
                minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectPreset(def.first) }
            }
            presetChips[def.first.id] = chip
            chipA11y(chip)
            presetRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 4f)
            })
        }
        column.addView(presetRow)

        example = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            id = R.id.formReadyExample
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
        }
        column.addView(example)
        photoCmNote = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            id = R.id.formReadyPhotoCmNote
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 4f), 0, 0)
        }
        column.addView(photoCmNote)

        val dims = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        widthField = numberField(R.id.formReadyWidth, R.string.form1_width_hint)
        heightField = numberField(R.id.formReadyHeight, R.string.form1_height_hint)
        dims.addView(widthField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
        })
        dims.addView(heightField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(dims)

        val units = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 8f), 0, 0)
        }
        for (u in listOf(FormUnit.PX, FormUnit.CM, FormUnit.MM, FormUnit.INCH)) {
            val chip = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = u.name.lowercase()
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                isClickable = true
                isFocusable = true
                setOnClickListener { unit = u; refresh() }
            }
            unitChips[u] = chip
            chipA11y(chip)
            units.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@FormReadyActivity, 4f)
            })
        }
        column.addView(units)

        val bands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        minField = numberField(R.id.formReadyMinKb, R.string.form1_min_hint)
        maxField = numberField(R.id.formReadyMaxKb, R.string.form1_max_hint)
        bands.addView(minField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = SwStyle.dp(this@FormReadyActivity, 6f)
        })
        bands.addView(maxField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(bands)

        val dpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        dpiRow.addView(SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid), bold = true).apply {
            text = "DPI"
        }, LinearLayout.LayoutParams(SwStyle.dp(this, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        for (d in FormPresets.SUPPORTED_DPI) {
            val chip = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = d.toString()
                gravity = Gravity.CENTER
                minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
                isClickable = true
                isFocusable = true
                setOnClickListener { dpi = d; refresh() }
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
            setTextColor(SwStyle.color(this@FormReadyActivity, R.color.text_hologram_hi))
            buttonTintList = ColorStateList.valueOf(SwStyle.color(this@FormReadyActivity, R.color.cyan_primary))
            maxLines = Int.MAX_VALUE
            minimumHeight = SwStyle.dp(this@FormReadyActivity, 48f)
            setOnCheckedChangeListener { _, checked -> if (!settingFields) { cleanup = checked } }
        }
        column.addView(cleanupBox)

        readout = SwStyle.text(this, 15f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.formReadyReadout
            maxLines = Int.MAX_VALUE
            setPadding(0, SwStyle.dp(this@FormReadyActivity, 10f), 0, 0)
        }
        column.addView(readout)

        pickButton = action(getString(R.string.form1_pick), R.id.formReadyPick, true) { pick.launch("image/*") }
        column.addView(pickButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 12f) })

        cropView = CropView(this).apply {
            id = R.id.formReadyCrop
            background = SwStyle.rounded(this@FormReadyActivity, 0xFF000000.toInt(), radiusDp = 14f)
        }
        column.addView(cropView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@FormReadyActivity, 220f)
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 12f) })

        applyButton = action(getString(R.string.form1_apply), R.id.formReadyApply, true) { apply() }
        applyButton.isEnabled = false
        applyButton.alpha = 0.5f
        column.addView(applyButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@FormReadyActivity, 12f) })

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
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

    private fun numberField(id: Int, hintRes: Int): EditText = EditText(this).apply {
        this.id = id
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        setTextColor(SwStyle.color(this@FormReadyActivity, R.color.text_hologram_hi))
        setHintTextColor(SwStyle.color(this@FormReadyActivity, R.color.text_hologram_dim))
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
                    R.id.formReadyWidth -> widthValue = s?.toString()?.toFloatOrNull() ?: 0f
                    R.id.formReadyHeight -> heightValue = s?.toString()?.toFloatOrNull() ?: 0f
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
                info.isCheckable = true
                info.isChecked = on
                info.stateDescription = host.context.getString(
                    if (on) R.string.form1_selected else R.string.form1_not_selected
                )
            }
        })
    }

    private fun action(text: String, id: Int, primary: Boolean, onClick: () -> Unit): TextView =
        SwStyle.asButton(SwStyle.text(this, 15f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
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
        cropView.aspectRatio = preset.aspect
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

    private fun refresh() {
        val (w, h) = currentPixels()
        val tooLarge = !FormPresets.fitsFormLimit(w, h)
        readout.text = getString(R.string.form1_readout, w, h)
        example.text = getString(R.string.form1_example, spec.widthPx, spec.heightPx, spec.minKb, spec.maxKb)
        photoCmNote.isVisible = spec.id == "photo"
        // An impossible target is refused before any bitmap is allocated.
        val sizeMessage = getString(R.string.form1_size_too_large)
        if (tooLarge) {
            errorView.text = sizeMessage
            errorView.isVisible = true
        } else if (errorView.text.toString() == sizeMessage) {
            errorView.isVisible = false
            errorView.text = ""
        }
        applyButton.isEnabled = hasImageForTest() && !tooLarge
        applyButton.alpha = if (applyButton.isEnabled) 1f else 0.5f
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
        writeFields()
        refresh()
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
                runCatching { File(path).takeIf { it.isFile && it.length() > 0L }?.readBytes() }
                    .getOrNull()
                    ?.let { runCatching { ImageTools.decodeSampled(it, 2400) }.getOrNull() }
            }
            progress.isVisible = false
            if (decoded == null) {
                showPickFailure()
                return@launch
            }
            bitmap = decoded
            cropView.setImage(decoded)
            if (rect != null && rect.size == 4) cropView.setCropRect(RectF(rect[0], rect[1], rect[2], rect[3]))
            refresh()
            pickButton.isVisible = false
        }
    }

    // ---- picked photo -----------------------------------------------------

    private fun showPickPrompt() {
        applyButton.isEnabled = false
        applyButton.alpha = 0.5f
        pickButton.isVisible = true
    }

    private fun showPickFailure() {
        errorView.text = getString(R.string.form1_pick_failed)
        errorView.isVisible = true
        showPickPrompt()
    }

    /** Test seam for the SAF picker (which cannot run under Robolectric) and its real callback. */
    internal fun acceptPickedBytes(bytes: ByteArray) {
        progress.isVisible = true
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
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = decoded
            cropView.setImage(decoded)
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
            applyButton.isEnabled = false
            applyButton.alpha = 0.5f
            return
        }
        val dpiNow = dpi
        val minBytes = minKb * SizeUnits.BYTES_PER_KB
        val maxBytes = maxKb * SizeUnits.BYTES_PER_KB
        val doCleanup = cleanup

        busy = true
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val (result, file) = withContext(Dispatchers.IO) {
                    val region = Bitmap.createBitmap(source, left, top, width, height)
                    val cleaned = if (doCleanup) SignatureCleanup.clean(region) else null
                    try {
                        val encoded = ExactKbEncoder.encode(
                            cleaned ?: region, targetW, targetH, dpiNow, minBytes, maxBytes
                        )
                        val dir = File(cacheDir, "form1_out").apply { mkdirs() }
                        val out = File(dir, "form_ready_${System.currentTimeMillis()}.jpg")
                        Durable.writeFile(out, encoded.bytes)
                        encoded to out
                    } finally {
                        cleaned?.recycle()
                        if (region !== source) region.recycle()
                    }
                }
                progress.isVisible = false
                busy = false
                lastOutput = file
                val note = buildNote(result)
                lastNoteForTest = note
                startActivity(ToolResultActivity.intent(
                    this@FormReadyActivity, TOOL_ID,
                    files = listOf(file), mime = "image/jpeg", note = note
                ))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // The phone cannot hold this rendition. Free the preview on every path and say
                // so in the app's own words instead of letting the process die.
                progress.isVisible = false
                busy = false
                cropView.clearImage()
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                bitmap = null
                errorView.text = getString(R.string.form1_out_of_memory)
                errorView.isVisible = true
                refresh()
            } catch (e: Exception) {
                progress.isVisible = false
                busy = false
                errorView.text = getString(R.string.form1_encode_failed)
                errorView.isVisible = true
            }
        }
    }

    private fun buildNote(result: ExactKbEncoder.Result): String {
        val measured = getString(
            R.string.form1_note_measured,
            SizeUnits.format(result.sizeBytes), result.bytes.size,
            result.width, result.height, result.dpi
        )
        val verdict = when (result.outcome) {
            ExactKbEncoder.Outcome.IN_BAND -> getString(
                R.string.form1_note_in_band,
                SizeUnits.format(minKb * SizeUnits.BYTES_PER_KB),
                SizeUnits.format(maxKb * SizeUnits.BYTES_PER_KB)
            )
            ExactKbEncoder.Outcome.BELOW_MIN -> getString(
                R.string.form1_note_below_min,
                getString(R.string.form1_min_miss, SizeUnits.format(minKb * SizeUnits.BYTES_PER_KB))
            )
            ExactKbEncoder.Outcome.ABOVE_MAX -> getString(
                R.string.form1_note_above_max, getString(R.string.form1_max_miss)
            )
        }
        return measured + "\n" + verdict + "\n" + getString(R.string.form1_source_kept)
    }

    override fun onDestroy() {
        cropView.clearImage()
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        if (isFinishing && !isChangingConfigurations) {
            runCatching { File(cacheDir, "form1_pick").deleteRecursively() }
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
    internal fun lastOutputForTest(): File? = lastOutput

    /** Test seams for the confirmed crop frame (the view keeps it in image pixels). */
    internal fun setCropRectForTest(rect: RectF) = cropView.setCropRect(rect)
    internal fun cropRectForTest(): RectF = cropView.getCropRect()

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
        private const val KEY_IMAGE = "form1_image"
        private const val KEY_RECT = "form1_rect"

        fun intent(context: Context): Intent = Intent(context, FormReadyActivity::class.java)
    }
}
