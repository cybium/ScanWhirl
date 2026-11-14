package com.scanwhirl.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityPdfViewerBinding
import com.scanwhirl.app.databinding.ItemPdfPageBinding
import com.scanwhirl.app.util.ShareUtils
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Read-only PDF viewer: a vertical, continuous, scrollable document (like the
 * rest of the app) with a normal title bar. Pages keep their own aspect ratio.
 * Tap a page for full-screen pinch/double-tap zoom.
 *
 * Retrieval slice 5: [pageIntent] opens a saved scan AT a page, after checking
 * that page against the document's current order and the opened PDF's real
 * page count, and Find ([ReaderFind]) searches the scan's stored OCR text.
 */
class PdfViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfPages? = null

    /** Saved scans only: the page order (file names) when the PDF was opened. */
    private var docPageKeys: List<String>? = null
    /** Vaulted or locked: Find's query is never written into saved state. */
    private var privateDoc = false
    private var find: ReaderFind? = null

    /** Cache copy of an opened content Uri (seekable); deleted with the screen. */
    private var tempPdf: File? = null
    private val renderLock = Any()
    private var pageCount = 0
    private var title = ""

    /** v2 reader dock: the saved scan (when opened from the library) and its PDF. */
    private var dockDoc: com.scanwhirl.app.model.ScanDocument? = null
    private var dockFile: File? = null
    private var stripBuilt = false

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { isDaemon = true }
    }

    /** At most a few full-page bitmaps in memory; the rest are re-rendered. */
    private val pageCache = object : LruCache<Int, Bitmap>(4) {}

    private val maxRenderDim = 1600

    private val pagerAdapter = PagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        // Optional full-screen wallpaper; otherwise the light document background shows.
        val wallpaper = com.scanwhirl.app.util.Wallpaper.drawable(this)
        if (wallpaper != null) {
            binding.root.background = wallpaper
            binding.pdfPager.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        if (!openPdf()) {
            finish()
            return
        }

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        binding.btnZoomClose.setOnClickListener { closeZoom() }

        binding.pdfPager.layoutManager = LinearLayoutManager(this)
        binding.pdfPager.adapter = pagerAdapter
        pagerAdapter.submit(pageCount)

        binding.pdfPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })

        val docId = intent.getStringExtra(EXTRA_DOC_ID)
        val repo = (application as ScanWhirlApp).repository
        val reader = ReaderFind(
            context = this,
            b = binding,
            docId = docId,
            pdfPageCount = pageCount,
            currentPage = { visiblePage() ?: 0 },
            goToPage = ::goToPage,
            load = { id ->
                repo.getDocument(id)?.let { doc ->
                    ReaderFind.Snapshot(doc.pageFiles.map { it.name }, repo.ocrDoc(id), repo.ocrStore.isPending(id))
                }
            }
        )
        find = reader
        reader.bind()
        bindDock()
        binding.toolbar.inflateMenu(R.menu.menu_pdf_viewer)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_find) {
                if (reader.isOpen) reader.close() else reader.open(focus = true)
                true
            } else {
                false
            }
        }

        if (savedInstanceState == null) {
            // A page link from Home: jump only when the page is still that page.
            val requested = intent.getIntExtra(EXTRA_START_PAGE, -1)
            val start = if (requested >= 0) {
                startPage(requested, intent.getStringExtra(EXTRA_START_KEY), pageCount, docPageKeys).also {
                    if (it == null) Toast.makeText(this, R.string.reader_start_changed, Toast.LENGTH_LONG).show()
                }
            } else {
                null
            }
            if (start != null) goToPage(start)
            val q = intent.getStringExtra(EXTRA_FIND_QUERY)
            // landOn is never null here, so Find selects without scrolling: a rejected
            // page stays at page 1, exactly as the message says.
            if (docId != null && !q.isNullOrBlank()) reader.open(prefill = q, landOn = start ?: 0)
        } else {
            val q = savedInstanceState.getString(STATE_FIND_QUERY)
            if (savedInstanceState.getBoolean(STATE_FIND_OPEN)) {
                reader.open(prefill = q, restore = savedInstanceState.getInt(STATE_FIND_INDEX, -1).takeIf { it >= 0 })
            }
        }
        binding.pdfPager.post { updateIndicator() }

        // PM-3.1c: a lock record without a protected PDF (or the reverse) is offered for repair when
        // the reader opens. DEVICE-UNPROVEN: PdfRenderer does not run under Robolectric (F22).
        if (docId != null) {
            repo.getDocument(docId)?.let { doc ->
                ProtectionDialogs.showFor(this, repo, doc) { recreate() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val f = find ?: return
        outState.putBoolean(STATE_FIND_OPEN, f.isOpen)
        // A private (vaulted/locked) document's search words are never persisted.
        if (f.isOpen && !privateDoc) {
            outState.putString(STATE_FIND_QUERY, f.query)
            outState.putInt(STATE_FIND_INDEX, f.index)
        }
    }

    /**
     * The page the reader is on: the one under the middle of the viewport (so the
     * last page counts once it fills the view), else the first visible one.
     */
    private fun visiblePage(): Int? {
        val rv = binding.pdfPager
        val lm = rv.layoutManager as? LinearLayoutManager ?: return null
        val centre = rv.findChildViewUnder(rv.width / 2f, rv.height / 2f)
            ?.let { rv.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        val pos = centre ?: lm.findFirstVisibleItemPosition()
        return if (pos == RecyclerView.NO_POSITION) null else pos.coerceIn(0, maxOf(0, pageCount - 1))
    }

    /**
     * Scrolls so [index] sits at the top. Only the pages that become visible are
     * bound and rendered (unrendered pages hold a page-shaped placeholder, so the
     * jump cannot be undone by a column of zero-height pages).
     */
    private fun goToPage(index: Int) {
        if (index !in 0 until pageCount) return
        val lm = binding.pdfPager.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(index, 0)
        binding.pdfPageIndicator.text = getString(R.string.page_indicator, index + 1, pageCount)
    }

    override fun onDestroy() {
        find?.shutdown()
        find = null
        renderExecutor.shutdownNow()
        pageCache.evictAll()
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        renderer = null
        pfd = null
        tempPdf?.delete()
        tempPdf = null
        super.onDestroy()
    }

    private fun handleBack() {
        when {
            binding.zoomOverlay.visibility == View.VISIBLE -> closeZoom()
            find?.isOpen == true -> find?.close()
            else -> finish()
        }
    }

    private class NoPdfException(@androidx.annotation.StringRes val messageRes: Int) :
        IllegalStateException("No PDF")

    /** Opens the PDF from a scan id, a file path, or a content Uri. */
    private fun openPdf(): Boolean = try {
        val docId = intent.getStringExtra(EXTRA_DOC_ID)
        val path = intent.getStringExtra(EXTRA_PATH)
        val data = intent.data

        val fd = when {
            docId != null -> {
                val doc = (application as ScanWhirlApp).repository.getDocument(docId)
                title = doc?.name ?: title
                if (doc == null) throw NoPdfException(R.string.doc_not_found)
                docPageKeys = doc.pageFiles.map { it.name }
                privateDoc = doc.isVaulted || doc.isLocked
                // PM-3.1c: an encrypted PDF with no lock record (unknown password) cannot be rendered
                // either, so it is previewed from its pages exactly like a locked one.
                val unknownPassword = (application as ScanWhirlApp).repository.protectionState(docId) ==
                    com.scanwhirl.app.util.PdfProtection.State.UNKNOWN_PASSWORD
                val file = if ((doc.isLocked || unknownPassword) && doc.pageFiles.isNotEmpty()) {
                    val preview = File(cacheDir, "preview_${doc.id}.pdf")
                    com.scanwhirl.app.util.PdfGenerator.imagesToPdf(doc.pageFiles, preview)
                    preview
                } else {
                    doc.pdfFile
                }
                if (!file.exists() || file.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            path != null -> {
                val file = File(path)
                if (!file.exists() || file.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                title = title.ifBlank { file.nameWithoutExtension }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            data != null -> {
                title = title.ifBlank { "PDF" }
                // SAF providers often return a pipe; PdfRenderer needs a
                // seekable file, so copy once into cache and open that.
                val dir = File(cacheDir, "pdf_open").apply { mkdirs() }
                val tmp = File(dir, "view_${System.nanoTime()}.pdf")
                try {
                    contentResolver.openInputStream(data)?.use { input ->
                        java.io.FileOutputStream(tmp).use { out -> input.copyTo(out) }
                    } ?: throw NoPdfException(R.string.pdf_missing)
                    if (tmp.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                } catch (e: NoPdfException) {
                    tmp.delete()
                    throw e
                } catch (e: Exception) {
                    tmp.delete()
                    throw NoPdfException(R.string.pdf_missing)
                }
                tempPdf = tmp
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> throw NoPdfException(R.string.pdf_missing)
        }

        pfd = fd
        renderer = pagesFactory(fd)
        pageCount = renderer?.pageCount ?: 0
        if (pageCount <= 0) throw IllegalStateException("Empty PDF")
        true
    } catch (e: NoPdfException) {
        Toast.makeText(this, e.messageRes, Toast.LENGTH_LONG).show()
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        renderer = null
        pfd = null
        tempPdf?.delete()
        tempPdf = null
        false
    } catch (e: Exception) {
        Toast.makeText(this, R.string.pdf_viewer_failed, Toast.LENGTH_LONG).show()
        try {
            renderer?.close()
        } catch (_: Exception) {
        }
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        renderer = null
        pfd = null
        tempPdf?.delete()
        tempPdf = null
        false
    }

    /** Renders one page to a bitmap at screen-ish resolution. Serialized. */
    private fun renderPage(index: Int): Bitmap? {
        val r = renderer ?: return null
        return try {
            synchronized(renderLock) { r.render(index, maxRenderDim) }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateIndicator() {
        if (pageCount <= 0) return
        val shown = visiblePage() ?: return
        binding.pdfPageIndicator.text =
            getString(R.string.page_indicator, shown + 1, pageCount)
    }

    /**
     * v2 UI: 07_reader_dock — Share · Pages · Text · Sign · More, a page strip
     * and the lock badge in the title. Every action is real or a Coming soon
     * sheet; Sign waits for L5.
     */
    private fun bindDock() {
        dockDoc = intent.getStringExtra(EXTRA_DOC_ID)
            ?.let { (application as ScanWhirlApp).repository.getDocument(it) }
        val path = intent.getStringExtra(EXTRA_PATH)
        dockFile = dockDoc?.pdfFile ?: path?.let { File(it) } ?: tempPdf

        val doc = dockDoc
        if (doc != null && (doc.isLocked || doc.isVaulted)) {
            binding.toolbar.setLogo(R.drawable.ic_sym_lock)
            binding.toolbar.logo?.let {
                DrawableCompat.setTint(
                    it, ContextCompat.getColor(this, R.color.gold_pro_primary)
                )
            }
            binding.toolbar.logoDescription = getString(R.string.home_protected)
        }

        // The dock lives in layout/view_reader_dock.xml (an <include>), so it has
        // no ViewBinding fields: find it by id, which is also how tests reach it.
        findViewById<View>(R.id.readerDockShare).setOnClickListener { shareCurrent() }
        findViewById<View>(R.id.readerDockPages).setOnClickListener { toggleStrip() }
        findViewById<View>(R.id.readerDockText).setOnClickListener { openTextScreen() }
        findViewById<View>(R.id.readerDockSign).setOnClickListener {
            com.scanwhirl.app.ui.kit.ToolRegistry.byId("sign")?.let {
                com.scanwhirl.app.ui.kit.SwSheet.comingSoon(this, it).show()
            }
        }
        findViewById<View>(R.id.readerDockMore).setOnClickListener { showReaderMore() }
    }

    private fun shareCurrent() {
        val doc = dockDoc
        if (doc != null) {
            ShareUtils.share(this, doc)
            return
        }
        val file = dockFile
        if (file != null && file.exists()) {
            ShareUtils.shareFile(this, file, "application/pdf")
        } else {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTextScreen() {
        val id = intent.getStringExtra(EXTRA_DOC_ID)
        if (id == null) {
            com.scanwhirl.app.ui.kit.SwSheet.note(
                this,
                getString(R.string.reader_dock_text),
                getString(R.string.reader_text_unavailable)
            ).show()
            return
        }
        startActivity(
            Intent(this, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
                .putExtra(OcrEditorActivity.EXTRA_TITLE, title)
        )
    }

    private fun showReaderMore() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.reader_more_note, pageCount))
            .setPrimary(R.string.reader_find) { find?.open(focus = true) }
            .setSecondary(R.string.share_pdf) { shareCurrent() }
            .build()
            .show()
    }

    private fun toggleStrip() {
        val strip = findViewById<View>(R.id.readerStrip)
        if (strip.visibility == View.VISIBLE) {
            strip.visibility = View.GONE
            return
        }
        if (!stripBuilt) {
            stripBuilt = true
            val density = resources.displayMetrics.density
            val thumbW = (38 * density).toInt()
            val thumbH = (50 * density).toInt()
            val margin = (5 * density).toInt()
            val outline = com.scanwhirl.app.ui.kit.SwStyle.rounded(
                this, ContextCompat.getColor(this, R.color.sw_paper_thumb), 5f
            )
            val shown = minOf(pageCount, 40)
            for (index in 0 until shown) {
                val thumb = ImageView(this).apply {
                    tag = index
                    contentDescription = getString(R.string.page_indicator, index + 1, pageCount)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = outline
                    setPadding(0, 0, 0, 0)
                }
                findViewById<android.view.ViewGroup>(R.id.readerStripPages).addView(
                    thumb,
                    LinearLayout.LayoutParams(thumbW, thumbH).apply { marginEnd = margin }
                )
                renderExecutor.execute {
                    val bmp = try {
                        synchronized(renderLock) { renderer?.render(index, 120) }
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) runOnUiThread {
                        if (thumb.tag == index) thumb.setImageBitmap(bmp)
                    }
                }
            }
        }
        strip.visibility = View.VISIBLE
    }

    /** Height/width of the last rendered page; A4 portrait until one is known. */
    @Volatile
    private var lastAspect = 1.414f

    /** Placeholder height for a page that has not rendered yet (no bitmap allocated). */
    private fun placeholderHeight(): Int {
        val w = binding.pdfPager.width - (16 * resources.displayMetrics.density).toInt()
        return if (w > 0) (w * lastAspect).roundToInt() else 0
    }

    private fun openZoom(index: Int) {
        val cached = pageCache.get(index)
        if (cached != null) {
            showZoom(cached)
            return
        }
        renderExecutor.execute {
            val bmp = renderPage(index) ?: return@execute
            pageCache.put(index, bmp)
            runOnUiThread { showZoom(bmp) }
        }
    }

    /** Find's panel steps aside while a page is zoomed, and comes back after. */
    private var findHiddenForZoom = false

    private fun showZoom(bmp: Bitmap) {
        binding.zoomImage.setImageBitmap(bmp)
        binding.zoomOverlay.visibility = View.VISIBLE
        if (binding.findPanel.visibility == View.VISIBLE) {
            findHiddenForZoom = true
            binding.findPanel.visibility = View.GONE
        }
    }

    private fun closeZoom() {
        binding.zoomOverlay.visibility = View.GONE
        binding.zoomImage.setImageDrawable(null)
        if (findHiddenForZoom) {
            findHiddenForZoom = false
            binding.findPanel.visibility = View.VISIBLE
        }
    }

    private inner class PagerAdapter : RecyclerView.Adapter<VH>() {
        private var count = 0

        fun submit(n: Int) {
            count = n
            notifyDataSetChanged()
        }

        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)
    }

    private inner class VH(private val binding: ItemPdfPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(index: Int) {
            binding.pageImage.tag = index
            binding.pageImage.setOnClickListener { openZoom(index) }
            val cached = pageCache.get(index)
            if (cached != null) {
                binding.pageImage.minimumHeight = 0
                binding.pageImage.setImageBitmap(cached)
                return
            }
            binding.pageImage.setImageDrawable(null)
            binding.pageImage.minimumHeight = placeholderHeight()
            renderExecutor.execute {
                val bmp = renderPage(index)
                if (bmp != null && bmp.width > 0) lastAspect = bmp.height.toFloat() / bmp.width
                runOnUiThread {
                    if (bmp == null) return@runOnUiThread
                    pageCache.put(index, bmp)
                    if (binding.pageImage.tag == index) {
                        binding.pageImage.minimumHeight = 0
                        binding.pageImage.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    /** The opened PDF's pages. [PdfRenderer] in the app; a fake in JVM tests. */
    internal interface PdfPages : java.io.Closeable {
        val pageCount: Int
        fun render(index: Int, maxDim: Int): Bitmap?
    }

    private class RendererPages(fd: ParcelFileDescriptor) : PdfPages {
        private val r = PdfRenderer(fd)
        override val pageCount: Int get() = r.pageCount
        override fun render(index: Int, maxDim: Int): Bitmap? = r.openPage(index).use { page ->
            val longest = maxOf(page.width, page.height)
            val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(w.toFloat() / page.width, h.toFloat() / page.height)
            }
            page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
        override fun close() = r.close()
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_PATH = "pdf_path"
        private const val EXTRA_TITLE = "pdf_title"
        private const val EXTRA_START_PAGE = "start_page"
        private const val EXTRA_START_KEY = "start_page_key"
        private const val EXTRA_FIND_QUERY = "find_query"
        private const val STATE_FIND_OPEN = "find_open"
        private const val STATE_FIND_QUERY = "find_query"
        private const val STATE_FIND_INDEX = "find_index"

        /** Test seam: how an opened descriptor becomes pages. */
        @androidx.annotation.VisibleForTesting
        internal var pagesFactory: (ParcelFileDescriptor) -> PdfPages = { RendererPages(it) }

        fun intent(context: Context, docId: String, title: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_TITLE, title)

        /**
         * A saved scan opened at [pageIndex] (0-based, current order), which must still
         * be stored as [pageKey]; Find opens with [query] when it is not blank.
         */
        fun pageIntent(
            context: Context,
            docId: String,
            title: String,
            pageIndex: Int,
            pageKey: String,
            query: String?
        ): Intent = intent(context, docId, title)
            .putExtra(EXTRA_START_PAGE, pageIndex)
            .putExtra(EXTRA_START_KEY, pageKey)
            .apply { if (!query.isNullOrBlank()) putExtra(EXTRA_FIND_QUERY, query) }

        /**
         * The page to open at, or null when the request no longer holds: out of the
         * PDF's real range, no page order (external/PDF-only), the order and the PDF
         * disagree in length, or the page at that position is no longer [key].
         */
        internal fun startPage(requested: Int, key: String?, pdfPages: Int, pageKeys: List<String>?): Int? = when {
            key == null || pageKeys == null -> null
            requested < 0 || requested >= pdfPages -> null
            pageKeys.size != pdfPages -> null
            pageKeys[requested] != key -> null
            else -> requested
        }

        fun fileIntent(context: Context, path: String, title: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)

        /** Opens any content-Uri PDF (device tab / picked file). */
        fun uriIntent(context: Context, uri: String, title: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .setData(android.net.Uri.parse(uri))
                .putExtra(EXTRA_TITLE, title)
    }
}
