package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
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
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.IdCard
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.PaperSize
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 */
class IdCardActivity : BaseActivity() {

    enum class Side { FRONT, BACK }

    private lateinit var repo: ScanRepository
    private var front: ByteArray? = null
    private var back: ByteArray? = null
    private var pendingSide = Side.FRONT
    private var creating = false

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

    /** Tests: fake the camera launch (the ML Kit scanner cannot run under Robolectric). */
    @Volatile
    internal var captureOverride: ((Side) -> Boolean)? = null

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
        if (uri == null) onCaptureCancelled() else readImage(uri, pendingSide)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
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
                back != null -> showLayoutSheet()
                front != null -> showBackSheet()
                else -> showFrontSheet()
            }
        }
    }

    // ---- kept across recreation (X2) --------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        keepCapturedState()
        outState.putBoolean(KEY_HAS_STATE, true)
        outState.putInt(KEY_PENDING_SIDE, pendingSide.ordinal)
    }

    /**
     * The two captures live in a small cache copy so a recreated screen (rotation, process death)
     * keeps them without pushing a multi-megabyte JPEG through the saved-state Bundle.
     */
    private fun keepCapturedState() {
        runCatching {
            val dir = capturedStateDir().apply { mkdirs() }
            front?.let { File(dir, FRONT_FILE).writeBytes(it) }
                ?: File(dir, FRONT_FILE).delete()
            back?.let { File(dir, BACK_FILE).writeBytes(it) }
                ?: File(dir, BACK_FILE).delete()
        }
    }

    private fun restoreCapturedState(state: Bundle) {
        if (!state.getBoolean(KEY_HAS_STATE, false)) return
        pendingSide = if (state.getInt(KEY_PENDING_SIDE, 0) == Side.BACK.ordinal) Side.BACK else Side.FRONT
        runCatching {
            val dir = capturedStateDir()
            front = File(dir, FRONT_FILE).takeIf { it.isFile }?.readBytes()
            back = File(dir, BACK_FILE).takeIf { it.isFile }?.readBytes()
        }
    }

    private fun clearCapturedState() {
        runCatching { capturedStateDir().deleteRecursively() }
    }

    private fun capturedStateDir(): File = File(cacheDir, STATE_DIR)

    // ---- screen -----------------------------------------------------------

    private fun buildRoot(): View {
        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
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

        scroll.addView(column)
        root.addView(scroll, FrameLayout.LayoutParams(
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

    private fun readImage(uri: Uri, side: Side) {
        progress.isVisible = true
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
                }.getOrNull()
            }
            progress.isVisible = false
            if (bytes == null) {
                fail(getString(R.string.tools_setup_image_failed))
                onCaptureCancelled()
            } else {
                acceptCapture(side, bytes)
            }
        }
    }

    /** A capture arrived (scanner or gallery); also the seam the UI tests drive. */
    internal fun acceptCapture(side: Side, bytes: ByteArray) {
        when (side) {
            Side.FRONT -> {
                front = bytes
                render()
                showBackSheet()
            }
            Side.BACK -> {
                back = bytes
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

    private fun showFrontSheet() {
        showSheet(
            SwSheet.builder(this)
                .setTitle(R.string.tools_id_front_title)
                .setMessage(R.string.tools_id_front_msg)
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

    private fun showBackSheet() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_id_back_title)
            .setMessage(R.string.tools_id_back_msg)
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
        val frontBytes = front ?: return
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
                val doc = withContext(Dispatchers.IO) {
                    repo.createDocument(getString(R.string.tools_id_card_name), pages)
                }
                progress.isVisible = false
                clearCapturedState()
                startActivity(ToolResultActivity.intent(this@IdCardActivity, "id_card", docIds = listOf(doc.id)))
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

    companion object {
        private const val STATE_DIR = "id_card_state"
        private const val FRONT_FILE = "front.jpg"
        private const val BACK_FILE = "back.jpg"
        private const val KEY_HAS_STATE = "id_card_has_state"
        private const val KEY_PENDING_SIDE = "id_card_pending_side"

        fun intent(context: Context): Intent = Intent(context, IdCardActivity::class.java)
    }
}
