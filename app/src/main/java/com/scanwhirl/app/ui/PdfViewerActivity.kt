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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityPdfViewerBinding
import com.scanwhirl.app.databinding.ItemPdfPageBinding
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Read-only PDF viewer: a vertical, continuous, scrollable document (like the
 * rest of the app) with a normal title bar. Pages keep their own aspect ratio.
 * Tap a page for full-screen pinch/double-tap zoom.
 */
class PdfViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Any()
    private var pageCount = 0
    private var title = ""

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
        binding.pdfPager.post { updateIndicator() }
    }

    override fun onDestroy() {
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
        super.onDestroy()
    }

    private fun handleBack() {
        if (binding.zoomOverlay.visibility == View.VISIBLE) closeZoom() else finish()
    }

    /** Opens the PDF from a scan id, a file path, or a content Uri. */
    private fun openPdf(): Boolean = try {
        val docId = intent.getStringExtra(EXTRA_DOC_ID)
        val path = intent.getStringExtra(EXTRA_PATH)
        val data = intent.data

        val fd = when {
            docId != null -> {
                val doc = (application as ScanWhirlApp).repository
                    .listDocuments().find { it.id == docId }
                val file = doc?.pdfFile
                title = doc?.name ?: title
                if (file == null || !file.exists() || file.length() == 0L) {
                    throw IllegalStateException("No PDF")
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            path != null -> {
                val file = File(path)
                if (!file.exists() || file.length() == 0L) throw IllegalStateException("No PDF")
                title = title.ifBlank { file.nameWithoutExtension }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            data != null -> {
                title = title.ifBlank { "PDF" }
                contentResolver.openFileDescriptor(data, "r")
                    ?: throw IllegalStateException("No PDF")
            }
            else -> throw IllegalStateException("No source")
        }

        pfd = fd
        renderer = PdfRenderer(fd)
        pageCount = renderer?.pageCount ?: 0
        if (pageCount <= 0) throw IllegalStateException("Empty PDF")
        true
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
        false
    }

    /** Renders one page to a bitmap at screen-ish resolution. Serialized. */
    private fun renderPage(index: Int): Bitmap? {
        val r = renderer ?: return null
        return try {
            synchronized(renderLock) {
                r.openPage(index).use { page ->
                    val longest = maxOf(page.width, page.height)
                    val scale = if (longest > maxRenderDim) maxRenderDim.toFloat() / longest else 1f
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
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateIndicator() {
        if (pageCount <= 0) return
        val lm = binding.pdfPager.layoutManager as? LinearLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION) return
        val shown = pos.coerceIn(0, pageCount - 1)
        binding.toolbar.subtitle =
            getString(R.string.page_indicator, shown + 1, pageCount)
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

    private fun showZoom(bmp: Bitmap) {
        binding.zoomImage.setImageBitmap(bmp)
        binding.zoomOverlay.visibility = View.VISIBLE
    }

    private fun closeZoom() {
        binding.zoomOverlay.visibility = View.GONE
        binding.zoomImage.setImageDrawable(null)
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
                binding.pageImage.setImageBitmap(cached)
                return
            }
            binding.pageImage.setImageDrawable(null)
            renderExecutor.execute {
                val bmp = renderPage(index)
                runOnUiThread {
                    if (bmp == null) return@runOnUiThread
                    pageCache.put(index, bmp)
                    if (binding.pageImage.tag == index) {
                        binding.pageImage.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_DOC_ID = "doc_id"
        private const val EXTRA_PATH = "pdf_path"
        private const val EXTRA_TITLE = "pdf_title"

        fun intent(context: Context, docId: String, title: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .putExtra(EXTRA_DOC_ID, docId)
                .putExtra(EXTRA_TITLE, title)

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
