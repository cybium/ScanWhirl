package com.scanwhirl.app.ui.tools

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.launchGalleryCrop
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

/**
 * v2 UI: 03_tools_hub — the ID card tool the way people actually use it.
 *
 * 1. Opening the tool starts Google's document scanner (the same ML Kit scanner as Scan, page
 *    limit 1, which crops the card) for the FRONT side.
 * 2. After the front, an `SwSheet` asks "Add the back side?" with Scan back side / Front only;
 *    a gallery picker is offered for each side so the tool also works without a camera.
 * 3. An `SwSheet` asks for the layout: both sides on one A4 page (front top, back bottom, real
 *    ID-1 proportions) or each side on its own page. The choice is remembered.
 * 4. The document is published through `ScanRepository.createDocument` and the result screen
 *    (Open · Share · Save · Show in library · Undo) opens.
 *
 * Cancelling any step (the scanner, a sheet, leaving the screen) creates nothing.
 */
class IdCardActivity : BaseActivity() {

    enum class Side { FRONT, BACK }

    private lateinit var repo: ScanRepository
    private var front: ByteArray? = null
    private var back: ByteArray? = null
    private var pendingSide = Side.FRONT
    private var creating = false

    private lateinit var frontPreview: ImageView
    private lateinit var backPreview: ImageView
    private lateinit var errorView: TextView
    private lateinit var progress: View

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        setContentView(buildRoot())
        // The first step is always the choice sheet: people without a good camera angle can use
        // the gallery, and the camera opens only when they ask for it.
        showFrontSheet()
    }

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
        launchGalleryCrop(
            activity = this,
            maxPages = 1,
            onResult = { uris ->
                val uri = uris.firstOrNull()
                if (uri == null) onCaptureCancelled() else readImage(uri, side)
            }
        )
    }

    private fun onCaptureCancelled() {
        if (pendingSide == Side.FRONT) showFrontSheet() else showBackSheet()
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

    private fun showFrontSheet() {
        SwSheet.builder(this)
            .setTitle(R.string.tools_id_front_title)
            .setMessage(R.string.tools_id_front_msg)
            .setPrimary(R.string.tools_id_scan_camera) { scan(Side.FRONT) }
            .setSecondary(R.string.tools_id_pick_gallery) { pickFromGallery(Side.FRONT) }
            .build()
            .show()
    }

    private fun showBackSheet() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.tools_id_back_title)
            .setMessage(R.string.tools_id_back_msg)
            .setPrimary(R.string.tools_id_scan_camera) { scan(Side.BACK) }
            .setSecondary(R.string.tools_id_pick_gallery) { pickFromGallery(Side.BACK) }
            .build()
        val frontOnly = SwStyle.text(
            this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true
        ).apply {
            text = getString(R.string.tools_id_front_only)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@IdCardActivity, 44f)
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                showLayoutSheet()
            }
        }
        sheet.setContent(frontOnly)
        sheet.show()
    }

    private fun showLayoutSheet() {
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
            ) { create(other, IdCardPrefs.paper(this)) }
            .build()

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
        a4.setOnClickListener { chosenPaper = PaperSize.A4; paint() }
        letter.setOnClickListener { chosenPaper = PaperSize.LETTER; paint() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a4, LinearLayout.LayoutParams(0, SwStyle.dp(this@IdCardActivity, 42f), 1f).apply {
            marginEnd = SwStyle.dp(this@IdCardActivity, 8f)
        })
        row.addView(letter, LinearLayout.LayoutParams(0, SwStyle.dp(this@IdCardActivity, 42f), 1f))
        content.addView(row)
        paint()
        sheet.setContent(content)
        sheet.setPrimary(
            if (remembered == IdCardPrefs.Layout.ONE_PAGE) R.string.tools_id_layout_one
            else R.string.tools_id_layout_own
        ) {
            IdCardPrefs.rememberPaper(this, chosenPaper)
            create(remembered, chosenPaper)
        }
        sheet.show()
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

    private fun create(layout: IdCardPrefs.Layout, paper: PaperSize) {
        if (creating) return
        val frontBytes = front ?: return
        val backBytes = back
        creating = true
        progress.isVisible = true
        errorView.isVisible = false
        lifecycleScope.launch {
            try {
                IdCardPrefs.remember(this@IdCardActivity, layout)
                IdCardPrefs.rememberPaper(this@IdCardActivity, paper)
                val pages = withContext(Dispatchers.IO) {
                    if (layout == IdCardPrefs.Layout.ONE_PAGE) {
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
        fun intent(context: Context): Intent = Intent(context, IdCardActivity::class.java)
    }
}
