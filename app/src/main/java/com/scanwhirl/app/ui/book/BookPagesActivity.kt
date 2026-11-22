package com.scanwhirl.app.ui.book

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.tools.V2cFailureMap
import com.scanwhirl.app.tools.V2cReceiptStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.BookSplit
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2 "Book pages" (Pro, Create): every picked photo is a two-page spread; the tool finds the fold,
 * lets the owner drag the line, then creates one new document with two pages per spread through the
 * existing [com.scanwhirl.app.store.ScanRepository.createDocument] path.
 *
 * Gallery picking uses the same `GetMultipleContents` image contract as Photos to PDF. Sources
 * are only read: decoding is sampled to [BookSplit.MAX_LONG_SIDE] px and the provider's bytes are
 * never written back.
 */
class BookPagesActivity : BaseActivity() {

    private val repo by lazy { ScanWhirlApp.from(this).repository }

    private lateinit var container: LinearLayout
    private lateinit var primaryButton: TextView
    private lateinit var addButton: TextView
    private lateinit var hintView: TextView
    private lateinit var statusView: TextView

    private val items = mutableListOf<Item>()

    private var busy = false

    /** Preview cards, in pick order (tests read them). */
    internal val previews: List<BookSpreadView> get() = items.map { it.view }

    internal fun createButton(): View = primaryButton
    internal fun addButton(): View = addButton

    /** The "Keep as one page" switch for a card, or null when it is a spread. */
    internal fun onePageSwitch(index: Int): SwitchCompat? = items.getOrNull(index)?.onePageSwitch

    /** True when card [index] will contribute one page instead of two. */
    internal fun keepsAsOnePage(index: Int): Boolean = items.getOrNull(index)?.keepAsOnePage == true

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) loadImages(uris)
    }

    /** The Google scanner crops the book (camera and gallery import) before the split. */
    private val scanSpread = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val scanning = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uris = scanning?.pages.orEmpty().mapNotNull { it.imageUri }
        if (uris.isEmpty()) {
            V2cReceiptStore.record(
                this, "book", getString(R.string.book_title), getString(R.string.v2c_error_unreadable)
            )
            Toast.makeText(this, R.string.book_read_failed, Toast.LENGTH_SHORT).show()
        } else {
            loadImages(uris)
        }
    }

    private fun launchBookScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(ScanLauncher.CAMERA_PAGE_LIMIT)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scanSpread.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { e ->
                    reportFailure(e)
                    pickImages.launch("image/*")
                }
        } catch (e: Exception) {
            reportFailure(e)
            pickImages.launch("image/*")
        }
    }

    private fun showAddSpread() {
        SwSheet.builder(this)
            .setTitle(R.string.book_add_spread)
            .setMessage(R.string.book_add_spread_note)
            .setPrimary(R.string.book_scan_camera) { launchBookScanner() }
            .setSecondary(R.string.book_from_photos) {
                // L1's opt-in gallery import runs the scanner (perspective crop) on a picked
                // photo; its manual fallback hands the raw URIs to the page-region guard.
                com.scanwhirl.app.tools.launchGalleryCrop(
                    activity = this,
                    maxPages = 30,
                    // S1 review P0: dispatch to the live activity, never a captured old screen.
                    onResult = { host, _, uris ->
                        if (host is BookPagesActivity && uris.isNotEmpty()) host.loadImages(uris)
                    },
                    onFallback = { host, _, uris ->
                        if (host is BookPagesActivity && uris.isNotEmpty()) host.loadImages(uris)
                    }
                )
            }
            .build()
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SwStyle.color(this@BookPagesActivity, R.color.rv_bg))
        }
        root.applySystemBarsPadding()

        val pad = SwStyle.dp(this, 20f)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, SwStyle.dp(this@BookPagesActivity, 12f), SwStyle.dp(this@BookPagesActivity, 8f), 0)
        }
        header.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.book_title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            SwStyle.iconButton(
                this,
                R.drawable.ic_sym_close,
                SwStyle.color(this, R.color.text_hologram_dim)
            ).apply {
                contentDescription = getString(R.string.book_close)
                setOnClickListener { finish() }
            }
        )
        root.addView(header)

        hintView = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            text = getString(R.string.book_hint)
            setLineSpacing(0f, 1.25f)
        }
        root.addView(
            hintView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    leftMargin = pad
                    rightMargin = pad
                    topMargin = SwStyle.dp(this@BookPagesActivity, 4f)
                }
        )

        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                container,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = SwStyle.dp(this@BookPagesActivity, 8f)
            }
        )

        statusView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_dim), bold = true).apply {
            visibility = View.GONE
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    leftMargin = pad
                    rightMargin = pad
                    topMargin = SwStyle.dp(this@BookPagesActivity, 4f)
                }
        )

        addButton = SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_mid), bold = true).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.book_add)
            visibility = View.GONE
            setOnClickListener { showAddSpread() }
            minHeight = SwStyle.dp(this@BookPagesActivity, 44f)
        }
        root.addView(
            addButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = pad; rightMargin = pad }
        )

        primaryButton = SwStyle.text(this, 16f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            gravity = Gravity.CENTER
            background = SwStyle.rounded(this@BookPagesActivity, SwStyle.color(this@BookPagesActivity, R.color.cyan_primary), 16f)
            minHeight = SwStyle.dp(this@BookPagesActivity, 54f)
            setOnClickListener {
                if (items.isEmpty()) showAddSpread() else createDocument()
            }
        }
        root.addView(
            primaryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 54f)).apply {
                leftMargin = pad
                rightMargin = pad
                topMargin = SwStyle.dp(this@BookPagesActivity, 10f)
                bottomMargin = SwStyle.dp(this@BookPagesActivity, 18f)
            }
        )

        setContentView(root)
        renderEmpty()
        updateControls()
    }

    // ---- picking and previewing ------------------------------------------

    /**
     * Decodes every picked photo at preview size, scans its gutter and adds a draggable card.
     * Kept internal so a Robolectric test can feed content URIs without the system picker.
     */
    internal fun loadImages(uris: List<Uri>) {
        if (busy) return
        busy = true
        statusView.text = getString(R.string.book_loading)
        updateControls()
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        try {
                            val bytes = readBytes(uri)
                            val bitmap = BookSplit.decode(bytes, PREVIEW_MAX_DIM)
                            var analysis = BookSplit.analyse(bitmap)
                            val confirmed = TextRotation.chooseRotation(bitmap, analysis.textVertical)
                            if (confirmed != null && confirmed != analysis.rotation) {
                                analysis = BookSplit.analyseUpright(bitmap, confirmed)
                            }
                            val preview = BookSplit.rotate(bitmap, analysis.rotation)
                            if (preview !== bitmap) bitmap.recycle()
                            Loaded(uri, preview, analysis)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                for (loadedPage in loaded) {
                    if (isFinishing || isDestroyed) {
                        loadedPage.preview.recycle()
                        continue
                    }
                    addPreview(loadedPage)
                }
                val skipped = uris.size - loaded.size
                if (skipped > 0) {
                    Toast.makeText(
                        this@BookPagesActivity,
                        getString(R.string.book_read_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                busy = false
                updateControls()
            }
        }
    }

    private fun addPreview(loaded: Loaded) {
        val uri = loaded.uri
        val bitmap = loaded.preview
        val analysis = loaded.analysis
        val gutter = analysis.gutter
        if (items.isEmpty()) container.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.card(this@BookPagesActivity, 18f)
            val p = SwStyle.dp(this@BookPagesActivity, 10f)
            setPadding(p, p, p, SwStyle.dp(this@BookPagesActivity, 10f))
        }

        val title = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(R.string.book_spread, items.size + 1)
        }
        val note = SwStyle.text(this, 11.5f, SwStyle.color(this, R.color.cyan_dim)).apply {
            maxLines = 2
            gravity = Gravity.END
            text = getString(
                when {
                    gutter.singlePage -> R.string.book_one_page_note
                    gutter.lowConfidence -> R.string.book_gutter_middle
                    else -> R.string.book_gutter_found
                }
            )
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(note)
        }
        card.addView(heading)

        val spread = BookSpreadView(this).apply {
            contentDescription = getString(R.string.book_preview_desc)
            setSpread(bitmap, gutter)
            onGutterMoved = {
                note.text = getString(R.string.book_gutter_moved)
                note.setTextColor(SwStyle.color(this@BookPagesActivity, R.color.gold_pro_primary))
            }
        }
        val item = Item(uri, spread, gutter.singlePage, analysis)
        if (gutter.lowConfidence && !gutter.singlePage) spread.pulseLine()
        card.addView(
            spread,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = SwStyle.dp(this@BookPagesActivity, 6f)
            }
        )

        run {
            val rotate = SwStyle.text(
                this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true
            ).apply {
                text = getString(R.string.book_rotate)
                gravity = Gravity.CENTER
                minHeight = SwStyle.dp(this@BookPagesActivity, 40f)
                setOnClickListener {
                    val current = spread.bitmap ?: return@setOnClickListener
                    val flipped = BookSplit.rotate(current, 180)
                    // Turning 180 degrees mirrors the line: a tilted spine's top becomes its bottom.
                    val nextGutter = spread.gutter().mirrored()
                    item.analysis = item.analysis.copy(
                        rotation = (item.analysis.rotation + 180) % 360,
                        gutter = nextGutter
                    )
                    spread.setSpread(flipped, nextGutter)
                    if (flipped !== current) current.recycle()
                }
            }
            card.addView(rotate)
        }
        if (gutter.singlePage) {
            val onePage = SwitchCompat(this).apply {
                text = getString(R.string.book_keep_one_page)
                isChecked = true
                setTextColor(SwStyle.color(this@BookPagesActivity, R.color.text_hologram_hi))
                minHeight = SwStyle.dp(this@BookPagesActivity, 44f)
                setOnCheckedChangeListener { _, checked ->
                    item.keepAsOnePage = checked
                    spread.lineVisible = !checked
                }
            }
            item.onePageSwitch = onePage
            spread.lineVisible = false
            card.addView(onePage)
        }

        container.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = SwStyle.dp(this@BookPagesActivity, 10f) }
        )
        items += item
    }

    private fun renderEmpty() {
        container.addView(
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.book_no_images)
                setPadding(0, SwStyle.dp(this@BookPagesActivity, 24f), 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun updateControls() {
        primaryButton.text = getString(if (items.isEmpty()) R.string.book_pick else R.string.book_create)
        primaryButton.isEnabled = !busy
        primaryButton.alpha = if (busy) 0.5f else 1f
        addButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        addButton.isEnabled = !busy
        statusView.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // ---- creating the document -------------------------------------------

    private fun createDocument() {
        if (busy || items.isEmpty()) return
        busy = true
        statusView.text = getString(R.string.book_splitting)
        updateControls()
        val sources = items.map {
            ItemSource(it.analysis.copy(gutter = it.view.gutter()), it.uri, it.keepAsOnePage)
        }
        lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    val out = ArrayList<ByteArray>(sources.size * 2)
                    for (source in sources) {
                        // Sampled to 3000 px inside BookSplit; the provider's image is only read.
                        val bytes = readBytes(source.uri)
                        if (source.keepAsOnePage) {
                            out += BookSplit.singlePageJpeg(bytes, source.analysis.rotation)
                        } else {
                            val (left, right) = BookSplit.splitToJpeg(bytes, source.analysis)
                            out += left
                            out += right
                        }
                    }
                    out
                }
                val doc = repo.createDocument(nextBookName(), pages)
                Toast.makeText(
                    this@BookPagesActivity,
                    resources.getQuantityString(
                        R.plurals.book_created, doc.pageCount, doc.pageCount
                    ),
                    Toast.LENGTH_LONG
                ).show()
                startActivity(DocumentViewerActivity.intent(this@BookPagesActivity, doc.id))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = false
                updateControls()
                reportFailure(e)
            }
        }
    }

    /**
     * V2C C2: one localized, durable reason for every capture/Book failure.
     * The raw exception text is only classified; it is never shown or stored.
     */
    private fun reportFailure(failure: Throwable?) {
        val reason = V2cFailureMap.message(this, failure)
        V2cReceiptStore.record(this, "book", getString(R.string.book_title), reason)
        SwSheet.note(
            this,
            getString(R.string.book_title),
            getString(R.string.v2c_book_failed, reason)
        ).show()
    }

    /** Test seam for the same localized failure path without a real scanner. */
    internal fun reportFailureForTest(failure: Throwable?) = reportFailure(failure)

    /** "Book 14 Nov", then "Book 14 Nov (2)" when the name already exists. */
    internal fun nextBookName(today: Date = Date()): String {
        val base = getString(
            R.string.book_doc_name,
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(today)
        )
        val existing = runCatching { repo.listDocuments().map { it.name }.toSet() }
            .getOrDefault(emptySet())
        if (base !in existing) return base
        var suffix = 2
        while ("$base ($suffix)" in existing) suffix++
        return "$base ($suffix)"
    }

    private fun readBytes(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { ImageTools.readPageBytes(it) }
            ?: throw IllegalStateException(getString(R.string.book_read_failed))

    override fun onDestroy() {
        for (item in items) item.view.release()
        items.clear()
        super.onDestroy()
    }

    private class Item(
        val uri: Uri,
        val view: BookSpreadView,
        var keepAsOnePage: Boolean,
        var analysis: BookSplit.Analysis
    ) {
        var onePageSwitch: SwitchCompat? = null
    }

    private class ItemSource(
        val analysis: BookSplit.Analysis,
        val uri: Uri,
        val keepAsOnePage: Boolean
    )

    private class Loaded(
        val uri: Uri,
        val preview: android.graphics.Bitmap,
        val analysis: BookSplit.Analysis
    )

    companion object {
        /** Previews are decodes for the screen only; the create path decodes at 3000 px. */
        private const val PREVIEW_MAX_DIM = 900

        fun intent(context: Context): Intent = Intent(context, BookPagesActivity::class.java)
    }
}
