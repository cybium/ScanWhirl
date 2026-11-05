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
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.databinding.ActivityPdfViewerBinding
import com.scanwhirl.app.databinding.ItemPdfPageBinding
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Read-only PDF viewer: renders the real `scan.pdf` (not the page JPEGs) with
 * the platform [PdfRenderer], one page per screen, swipe between pages and
 * pinch/double-tap to zoom. This is what the "view" action opens; the page
 * grid stays available separately as the editor.
 *
 * Accepts either an in-app scan id or a content-Uri (for the device PDFs tab).
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Any()
    private var pageCount = 0
    private var title = ""
    private var currentPos = 0

    private val renderExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pdf-render").apply { isDaemon = true }
    }

    /** At most a few full-page bitmaps in memory; the rest are re-rendered. */
    private val pageCache = object : LruCache<Int, Bitmap>(3) {}

    private val maxRenderDim = 1600

    private lateinit var snapHelper: PagerSnapHelper
    private val pagerAdapter = PagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!openPdf()) {
            finish()
            return
        }

        binding.pdfTitle.text = title
        binding.btnClose.setOnClickListener { finish() }

        binding.pdfPager.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.pdfPager.adapter = pagerAdapter
        snapHelper = PagerSnapHelper().also { it.attachToRecyclerView(binding.pdfPager) }

        pagerAdapter.submit(pageCount)
        binding.pdfPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateIndicator()
            }
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
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            data != null -> contentResolver.openFileDescriptor(data, "r")
                ?: throw IllegalStateException("No PDF")
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
        val snap = snapHelper.findSnapView(lm)
        val pos = if (snap != null) lm.getPosition(snap) else currentPos
        currentPos = pos.coerceIn(0, pageCount - 1)
        binding.pdfPageIndicator.text =
            getString(R.string.page_indicator, currentPos + 1, pageCount)
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
                    if (binding.pageImage.tag == index) {
                        pageCache.put(index, bmp)
                        binding.pageImage.setImageBitmap(bmp)
                    } else {
                        // Recycled before the render finished; let GC reclaim it.
                        pageCache.put(index, bmp)
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
    }
}
