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
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
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
            .setPageLimit(30)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    scanSpread.launch(IntentSenderRequest.Builder(sender).build())
                }
                .addOnFailureListener { e ->
                    val message = if (e is ApiException) {
                        getString(R.string.book_scanner_fallback)
                    } else {
                        getString(R.string.book_scanner_fallback)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    pickImages.launch("image/*")
                }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.book_scanner_fallback, Toast.LENGTH_LONG).show()
            pickImages.launch("image/*")
        }
    }

    private fun showAddSpread() {
        SwSheet.builder(this)
            .setTitle(R.string.book_add_spread)
            .setMessage(R.string.book_add_spread_note)
            .setPrimary(R.string.book_scan_camera) { launchBookScanner() }
            .setSecondary(R.string.book_from_photos) { launchBookScanner() }
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
                            val gutter = BookSplit.scanGutter(bitmap)
                            val rotateTop = if (gutter.horizontal) {
                                TextRotation.choose(bitmap, gutter)
                            } else {
                                true
                            }
                            Loaded(uri, bitmap, gutter, rotateTop)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                for (loadedPage in loaded) {
                    if (isFinishing || isDestroyed) {
                        loadedPage.bitmap.recycle()
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
        val bitmap = loaded.bitmap
        val gutter = loaded.gutter
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
                    gutter.horizontal -> R.string.book_gutter_horizontal
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
        val item = Item(uri, spread, gutter.singlePage, loaded.rotateTop)
        if (gutter.lowConfidence && !gutter.singlePage) spread.pulseLine()
        card.addView(
            spread,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = SwStyle.dp(this@BookPagesActivity, 6f)
            }
        )

        if (gutter.horizontal && !gutter.singlePage) {
            val rotate = SwStyle.text(
                this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true
            ).apply {
                text = getString(R.string.book_rotate)
                gravity = Gravity.CENTER
                minHeight = SwStyle.dp(this@BookPagesActivity, 40f)
                setOnClickListener {
                    item.rotateTop = !item.rotateTop
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
        val sources = items.map { ItemSource(it.view.gutter(), it.uri, it.keepAsOnePage, it.rotateTop) }
        lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    val out = ArrayList<ByteArray>(sources.size * 2)
                    for (source in sources) {
                        // Sampled to 3000 px inside BookSplit; the provider's image is only read.
                        val bytes = readBytes(source.uri)
                        if (source.keepAsOnePage) {
                            out += BookSplit.singlePageJpeg(bytes)
                        } else {
                            val (left, right) = BookSplit.splitToJpeg(
                                bytes, source.gutter, source.rotateTop
                            )
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
                SwSheet.note(
                    this@BookPagesActivity,
                    getString(R.string.book_title),
                    getString(R.string.book_failed, e.message.orEmpty())
                ).show()
            }
        }
    }

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
        var rotateTop: Boolean
    ) {
        var onePageSwitch: SwitchCompat? = null
    }

    private class ItemSource(
        val gutter: BookSplit.Gutter,
        val uri: Uri,
        val keepAsOnePage: Boolean,
        val rotateTop: Boolean
    )

    private class Loaded(
        val uri: Uri,
        val bitmap: android.graphics.Bitmap,
        val gutter: BookSplit.Gutter,
        val rotateTop: Boolean
    )

    companion object {
        /** Previews are decodes for the screen only; the create path decodes at 3000 px. */
        private const val PREVIEW_MAX_DIM = 900

        fun intent(context: Context): Intent = Intent(context, BookPagesActivity::class.java)
    }
}
