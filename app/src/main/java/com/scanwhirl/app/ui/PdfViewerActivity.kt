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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.databinding.ActivityPdfViewerBinding
import com.scanwhirl.app.databinding.ItemPdfPageBinding
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var vaultMode = false
    @Volatile private var vaultRevoked = false
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

    /** C01: the off-main preview build and its progress sheet. */
    private var loadProgress: com.scanwhirl.app.ui.kit.SwProgress? = null
    private var previewJob: kotlinx.coroutines.Job? = null
    private val pickedCopy by lazy { ViewModelProvider(this)[PickedPdfCopy::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        vaultMode = intent.getBooleanExtra(EXTRA_PRIVATE, false) ||
            intent.getStringExtra(EXTRA_PATH)?.let { isVaultOpenCopy(File(it)) } == true
        if (vaultMode) {
            privateDoc = true
            com.scanwhirl.app.util.SecureView.applyForDocument(this, window, true)
            if (!checkVaultSession()) return
        }
        // Optional full-screen wallpaper; otherwise the light document background shows.
        val wallpaper = com.scanwhirl.app.util.Wallpaper.drawable(this)
        if (wallpaper != null) {
            binding.root.background = wallpaper
            binding.pdfPager.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        binding.toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        binding.btnZoomClose.setOnClickListener { closeZoom() }
        // UX-L4-03: the indicator is the entry to any page, not just the first 40.
        binding.pdfPageIndicator.setOnClickListener { promptGoToPage() }

        binding.pdfPager.layoutManager = LinearLayoutManager(this)
        binding.pdfPager.adapter = pagerAdapter
        binding.pdfPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })
        // F1-FIT: the dock floats over the page and grows to two rows at large
        // text; reserve its measured height (never less than the old 92 dp) so a
        // page cannot end underneath it.
        val dockRoot = findViewById<android.view.View>(R.id.readerDockRoot)
        dockRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserveDockSpace(dockRoot) }
        dockRoot.post { reserveDockSpace(dockRoot) }

        startOpen(savedInstanceState)
    }

    /** F1-FIT: measured dock clearance instead of the old hard-coded 92 dp. */
    private fun reserveDockSpace(dockRoot: android.view.View) {
        val pager = binding.pdfPager
        if (dockRoot.height <= 0) return
        val density = resources.displayMetrics.density
        val bottom = maxOf((92 * density).toInt(), dockRoot.height + (12 * density).toInt())
        if (pager.paddingBottom != bottom) {
            pager.setPadding(pager.paddingLeft, pager.paddingTop, pager.paddingRight, bottom)
        }
    }

    /**
     * C01: resolve the document first, build a locked-scan preview PDF **off the main thread**,
     * then lay the reader out. The preview is cached by document id + a page fingerprint, so a
     * rotation or a re-open reuses it instead of re-encoding every page.
     */
    private fun startOpen(savedInstanceState: Bundle?) {
        val uri = intent.data
        if (intent.getStringExtra(EXTRA_DOC_ID) == null &&
            intent.getStringExtra(EXTRA_PATH) == null && uri != null) {
            previewJob = lifecycleScope.launch {
                try {
                    tempPdf = pickedCopy.open(applicationContext, uri).await()
                    if (!isFinishing && !isDestroyed) afterOpen(savedInstanceState)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val text = (e as? com.scanwhirl.app.util.BoundedCopy.Refused)?.message
                        ?: getString(R.string.l1_copy_unreadable)
                    Toast.makeText(this@PdfViewerActivity, text, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            return
        }
        // A private Vault copy / explicit path has no library document to resolve: no disk work
        // below, so keep its (FLAG_SECURE) open path synchronous and unchanged.
        if (intent.getStringExtra(EXTRA_DOC_ID) == null) {
            afterOpen(savedInstanceState)
            return
        }
        // S2 item 4: everything below reads the library or the cache (getDocument, protection
        // state, page stats, fingerprint stamp). Run it off the main thread so the first frame is
        // the reader chrome, not a frozen window. FLAG_SECURE/Vault handling lives in onCreate and
        // afterOpen and is unchanged.
        previewJob = lifecycleScope.launch {
            val load = withContext(Dispatchers.IO) {
                documentLoadProbe?.invoke(Thread.currentThread().name)
                val docId = requireNotNull(intent.getStringExtra(EXTRA_DOC_ID))
                val repository = (application as ScanWhirlApp).repository
                val doc = repository.getDocument(docId)
                val unknownPassword =
                    repository.protectionState(docId) == com.scanwhirl.app.util.PdfProtection.State.UNKNOWN_PASSWORD
                val needsPreview = doc != null &&
                    (doc.isLocked || unknownPassword) && doc.pageFiles.isNotEmpty()
                var target: File? = null
                var stamp: File? = null
                var fingerprint: String? = null
                var cached = false
                if (needsPreview) {
                    target = previewFile(doc!!)
                    stamp = File(cacheDir, "preview_${doc.id}.fp")
                    fingerprint = pageFingerprint(doc.pageFiles)
                    cached = target.isFile && target.length() > 0L && stamp.isFile &&
                        stamp.readText() == fingerprint
                }
                LoadedDocument(doc, needsPreview, target, stamp, fingerprint, cached)
            }
            if (isFinishing || isDestroyed) return@launch
            if (!load.needsPreview || load.cached) {
                afterOpen(savedInstanceState)
                return@launch
            }
            // Visible work: the first frame is the reader chrome with a progress state.
            val progress = com.scanwhirl.app.ui.kit.SwProgress.show(
                this@PdfViewerActivity, getString(R.string.crash_preview_building)
            )
            loadProgress = progress
            val ok = previewDeferred(load.doc!!, load.target!!, load.stamp!!, load.fingerprint!!).await()
            progress.dismiss()
            loadProgress = null
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                Toast.makeText(
                    this@PdfViewerActivity, R.string.pdf_viewer_failed, Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            afterOpen(savedInstanceState)
        }
    }

    /** Result of the off-main document load in [startOpen]. */
    private data class LoadedDocument(
        val doc: com.scanwhirl.app.model.ScanDocument?,
        val needsPreview: Boolean,
        val target: File?,
        val stamp: File?,
        val fingerprint: String?,
        val cached: Boolean
    )

    private fun afterOpen(savedInstanceState: Bundle?) {
        if (!checkVaultSession()) return
        if (!openPdf()) {
            finish()
            return
        }
        // SECURITY_AUDIT 2026-11-15: a locked/Vault PDF is never screenshot-able;
        // an ordinary PDF follows the user's "Block screenshots of documents".
        com.scanwhirl.app.util.SecureView.applyForDocument(this, window, privateDoc)

        binding.toolbar.title = title
        pagerAdapter.submit(pageCount)

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

    /** Where the locked/Vault preview PDF lives (one per document, reused across recreates). */
    private fun previewFile(doc: com.scanwhirl.app.model.ScanDocument): File =
        File(cacheDir, "preview_${doc.id}.pdf")

    /** True when [file] is a decrypted Vault copy (`cacheDir/vault_open/...`). */
    private fun isVaultOpenCopy(file: File): Boolean = try {
        file.canonicalFile.startsWith(File(cacheDir, "vault_open").canonicalFile)
    } catch (e: Exception) {
        false
    }

    /** Names, sizes and mtimes: any page edit or re-filter changes it, nothing else does. */
    private fun pageFingerprint(pages: List<File>): String =
        pages.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }

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
        loadProgress?.dismiss()
        loadProgress = null
        // The build itself runs in a process-wide scope so a rotation can join it; only this
        // activity's wait is cancelled.
        previewJob?.cancel()
        previewJob = null
        find?.shutdown()
        find = null
        renderExecutor.shutdownNow()
        pageCache.evictAll()
        synchronized(renderLock) {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
        }
        if (vaultMode && isFinishing && !isChangingConfigurations) deleteVaultCopy()
        // The ViewModel owns picked-file scratch and releases it only on a real exit.
        tempPdf = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        checkVaultSession()
    }

    override fun onResume() {
        super.onResume()
        checkVaultSession()
    }

    /** Background renders never touch Views; their UI deliveries recheck on the main thread. */
    private fun vaultReadable(): Boolean =
        !vaultRevoked && (!vaultMode || VaultSession.isUnlocked())

    private fun checkVaultForRender(): Boolean {
        if (vaultReadable()) return true
        // Rendering may be requested while stopped. Do not wait for the next resume
        // to revoke the UI and scratch copy when that request discovers expiry.
        runOnUiThread { checkVaultSession() }
        return false
    }

    private fun checkVaultSession(): Boolean {
        if (!vaultMode || vaultReadable()) return true
        vaultRevoked = true
        if (::binding.isInitialized) {
            binding.root.visibility = View.INVISIBLE
            closeZoom()
            fun clearImages(view: View) {
                if (view is ImageView) view.setImageDrawable(null)
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) clearImages(view.getChildAt(i))
                }
            }
            clearImages(binding.root)
            pagerAdapter.submit(0)
            binding.pdfPager.adapter = null
            binding.pdfPager.recycledViewPool.clear()
            findViewById<ViewGroup>(R.id.readerStripPages).removeAllViews()
            binding.toolbar.title = ""
        }
        previewJob?.cancel()
        find?.shutdown()
        find = null
        renderExecutor.shutdownNow()
        synchronized(renderLock) {
            pageCache.evictAll()
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
            pageCount = 0
        }
        dockFile = null
        deleteVaultCopy()
        finish()
        return false
    }

    private fun deleteVaultCopy() {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        try {
            val root = File(cacheDir, "vault_open").canonicalFile
            val file = File(path).canonicalFile
            // Never delete an arbitrary caller-supplied original.
            if (!file.startsWith(root)) return
            // openDocument decrypts the PDF *and* the page images into one per-document folder.
            // Deleting only the PDF leaves those pages in plaintext until lock or cold start.
            val dir = file.parentFile?.canonicalFile ?: return
            // Never wipe the shared root (or, via a symlink, anything outside vault_open).
            if (dir == root || !dir.startsWith(root)) return
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
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
                    // C01: startOpen built (or reused) this cache copy off the main thread; the
                    // descriptor itself is cheap. A missing preview is an error, never a rebuild here.
                    val preview = previewFile(doc)
                    if (!preview.isFile || preview.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                    preview
                } else {
                    doc.pdfFile
                }
                if (!file.exists() || file.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            path != null -> {
                // C2 item 1: EXTRA_PATH is caller-supplied. Only a file that canonically lives
                // inside this app's own documents (filesDir) or cache (cacheDir) may be opened.
                // Canonical containment rejects `../` and symlinks that escape; anything else
                // gets the honest "could not open" message and the reader finishes.
                if (!isPathInsideAppDirs(path, filesDir, cacheDir)) {
                    throw NoPdfException(R.string.pdf_viewer_failed)
                }
                val file = File(path)
                if (!file.exists() || file.length() == 0L) throw NoPdfException(R.string.pdf_missing)
                title = title.ifBlank { file.nameWithoutExtension }
                // Review H4: a Vault PDF travels as a decrypted cache copy. It is private by
                // the intent marker set only by the Vault, and by its location as a second
                // line of defence, so FLAG_SECURE never depends on the screenshot preference.
                privateDoc = privateDoc ||
                    intent.getBooleanExtra(EXTRA_PRIVATE, false) ||
                    isVaultOpenCopy(file)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            data != null -> {
                title = title.ifBlank { "PDF" }
                // SAF providers often return a pipe; PdfRenderer needs a
                // seekable file, so copy once into cache and open that.
                val tmp = tempPdf?.takeIf { it.isFile && it.length() > 0L }
                    ?: throw NoPdfException(R.string.pdf_missing)
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
        return try {
            synchronized(renderLock) {
                if (!checkVaultForRender()) return null
                renderer?.render(index, maxRenderDim)
            }
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
        val share = findViewById<View>(R.id.readerDockShare)
        if (vaultMode) {
            // A Vault document has no share/export path: its PDF is a decrypted cache copy and
            // FileProvider does not expose vault_open. Hiding and disabling Share is the only
            // way to keep a Vault share from crashing or leaking plaintext.
            share.visibility = View.GONE
            share.isEnabled = false
            share.isClickable = false
            share.setOnClickListener(null)
        } else {
            share.setOnClickListener { shareCurrent() }
        }
        findViewById<View>(R.id.readerDockPages).setOnClickListener { toggleStrip() }
        findViewById<View>(R.id.readerDockText).setOnClickListener { openTextScreen() }
        findViewById<View>(R.id.readerDockSign).setOnClickListener {
            val doc = dockDoc
            if (doc != null) {
                // L5: a saved scan opens the stamp editor armed with Sign — a Pro entry point
                // outside the shell, so Free gets the Pro screen (the same offer as the hub),
                // never the editor itself.
                if (com.scanwhirl.app.ui.kit.ProOffer.require(this)) {
                    startActivity(
                        com.scanwhirl.app.ui.stamp.StampEditorActivity.intent(
                            this,
                            doc.id,
                            com.scanwhirl.app.ui.stamp.StampEditorActivity.TOOL_SIGN
                        )
                    )
                }
            } else {
                // An external PDF is not a library document: save/import it first.
                com.scanwhirl.app.ui.kit.ToolRegistry.byId("sign")?.let {
                    com.scanwhirl.app.ui.kit.SwSheet.comingSoon(this, it).show()
                }
            }
        }
        findViewById<View>(R.id.readerDockMore).setOnClickListener { showReaderMore() }
    }

    private fun shareCurrent() {
        if (!checkVaultSession()) return
        if (vaultMode) {
            // Defensive: a stale listener or a test seam must never offer a vault_open URI.
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        try {
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
        } catch (e: IllegalArgumentException) {
            // FileProvider rejects paths it does not expose. An unshareable file is an honest
            // "nothing to share", never a crashed reader.
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
        // UX-L4-03: More now holds reader-specific actions. Find stays where it already
        // is in the toolbar, so it is not duplicated here; Go to page replaces it.
        val builder = com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.reader_more_note, pageCount))
            .setPrimary(R.string.ux130_l4_go_to_page) { promptGoToPage() }
        if (vaultMode) {
            // The title is private and there is no Vault share path: secure the sheet's own
            // window (FLAG_SECURE on the activity does not cover a dialog's window) and drop
            // the Share action.
            builder.build().secure().show()
        } else {
            builder.setSecondary(R.string.share_pdf) { shareCurrent() }.build().show()
        }
    }

    /**
     * UX-L4-03: any page, not only the first 40 strip thumbnails. The input is a
     * plain integer in 1..pageCount with a localized validation message; an empty
     * or out-of-range entry changes nothing.
     */
    private fun promptGoToPage() {
        if (!checkVaultSession() || pageCount <= 0) return
        // S2 item 10: the kit sheet is lifecycle-managed (no window leaked on rotation) and its
        // primary can validate and stay open. A raw AlertDialog did neither.
        lateinit var sheet: com.scanwhirl.app.ui.kit.SwSheet
        sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.ux130_l4_go_to_page)
            .setMessage(getString(R.string.reader_more_note, pageCount))
            .setInput(
                hint = getString(R.string.ux130_l4_go_to_page),
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            )
            .setPrimaryStayOpen(R.string.ux130_l4_go_to_page) {
                val page = sheet.inputView?.text?.toString()?.trim()?.toIntOrNull()
                if (page == null || page < 1 || page > pageCount) {
                    sheet.inputView?.error = getString(R.string.ux130_l4_go_to_page_invalid, pageCount)
                } else {
                    sheet.dismiss()
                    goToPage(page - 1)
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
        if (vaultMode) sheet.secure()
        sheet.show()
    }

    private fun toggleStrip() {
        if (!checkVaultSession()) return
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
                        synchronized(renderLock) {
                            if (checkVaultForRender()) renderer?.render(index, 120) else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) runOnUiThread {
                        if (checkVaultSession() && thumb.tag == index) thumb.setImageBitmap(bmp)
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
        if (!checkVaultSession()) return
        val cached = pageCache.get(index)
        if (cached != null) {
            showZoom(cached)
            return
        }
        renderExecutor.execute {
            val bmp = renderPage(index) ?: return@execute
            runOnUiThread {
                if (checkVaultSession()) {
                    pageCache.put(index, bmp)
                    showZoom(bmp)
                }
            }
        }
    }

    /** Find's panel steps aside while a page is zoomed, and comes back after. */
    private var findHiddenForZoom = false

    private fun showZoom(bmp: Bitmap) {
        if (!checkVaultSession()) return
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
            if (!checkVaultSession()) return
            binding.pageImage.tag = index
            // UX-L4-03: the tappable preview is named and has a named zoom action.
            binding.pageImage.contentDescription =
                getString(R.string.ux130_l4_page_zoom, index + 1, pageCount)
            binding.pageImage.setOnClickListener { openZoom(index) }
            androidx.core.view.ViewCompat.setAccessibilityDelegate(
                binding.pageImage,
                object : androidx.core.view.AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(
                            androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                                .AccessibilityActionCompat(
                                    R.id.ux130_l4_zoom_page,
                                    getString(R.string.ux130_l4_zoom_page)
                                )
                        )
                    }

                    override fun performAccessibilityAction(
                        host: View,
                        action: Int,
                        args: android.os.Bundle?
                    ): Boolean = if (action == R.id.ux130_l4_zoom_page) {
                        openZoom(index)
                        true
                    } else {
                        super.performAccessibilityAction(host, action, args)
                    }
                }
            )
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
                    if (!checkVaultSession()) return@runOnUiThread
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

        /**
         * Review H4: set only by the Vault when it opens a decrypted private copy. It forces
         * FLAG_SECURE on the reader window regardless of the user's screenshot preference.
         */
        const val EXTRA_PRIVATE = "pdf_private"
        private const val EXTRA_START_PAGE = "start_page"
        private const val EXTRA_START_KEY = "start_page_key"
        private const val EXTRA_FIND_QUERY = "find_query"
        private const val STATE_FIND_OPEN = "find_open"
        private const val STATE_FIND_QUERY = "find_query"
        private const val STATE_FIND_INDEX = "find_index"

        /** S2 item 4 test seam: receives the thread that performs the onCreate document load. */
        @androidx.annotation.VisibleForTesting
        internal var documentLoadProbe: ((String) -> Unit)? = null

        /** Test seam: how an opened descriptor becomes pages. */
        @androidx.annotation.VisibleForTesting
        internal var pagesFactory: (ParcelFileDescriptor) -> PdfPages = { RendererPages(it) }

        /** Test seam: how a locked-scan preview PDF is produced (must run off the main thread). */
        @androidx.annotation.VisibleForTesting
        internal var previewBuilder: (List<File>, File) -> Unit = { pages, out ->
            com.scanwhirl.app.util.PdfGenerator.imagesToPdf(pages, out)
        }

        /**
         * C01: one build per document + page fingerprint, process-wide, so a rotation while the
         * progress sheet is up joins the running job instead of re-encoding every page again.
         */
        private val previewJobs =
            java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>()
        private val previewScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        /**
         * C1 review: let a repeat test open the SAME document from scratch ten times, so every
         * open really takes the off-main preview path instead of hitting the cached PDF/stamp.
         */
        @androidx.annotation.VisibleForTesting
        internal fun clearPreviewCacheForTest(context: Context, docId: String) {
            previewJobs.keys.removeAll { it.startsWith("$docId:") }
            File(context.cacheDir, "preview_$docId.pdf").delete()
            File(context.cacheDir, "preview_$docId.fp").delete()
        }

        private fun previewDeferred(
            doc: com.scanwhirl.app.model.ScanDocument,
            out: File,
            stamp: File,
            fingerprint: String
        ): kotlinx.coroutines.Deferred<Boolean> {
            val key = "${doc.id}:$fingerprint"
            previewJobs[key]?.let { return it }
            val job = previewScope.async {
                try {
                    out.parentFile?.mkdirs()
                    val tmp = File(out.parentFile, out.name + ".build")
                    tmp.delete()
                    previewBuilder(doc.pageFiles, tmp)
                    if (!tmp.isFile || tmp.length() == 0L) {
                        tmp.delete()
                        return@async false
                    }
                    out.delete()
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        return@async false
                    }
                    stamp.writeText(fingerprint)
                    true
                } catch (_: Throwable) {
                    false
                } finally {
                    previewJobs.remove(key)
                }
            }
            previewJobs[key] = job
            return job
        }

        /**
         * C2 item 1: true only when [path] canonically resolves inside this app's own
         * documents (filesDir) or cache (cacheDir). A `../` traversal or a symlink whose
         * target is outside those roots resolves elsewhere and is refused. A path that
         * cannot be resolved at all is refused too (fail closed).
         */
        @androidx.annotation.VisibleForTesting
        internal fun isPathInsideAppDirs(path: String, filesDir: File, cacheDir: File): Boolean = try {
            val file = File(path).canonicalFile
            val roots = listOf(filesDir.canonicalFile, cacheDir.canonicalFile)
            roots.any { root -> file.startsWith(root) }
        } catch (_: Exception) {
            false
        }

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

        /**
         * Review H4: the Vault route. Marks the window private so a decrypted Vault copy is
         * never screenshot-able, whatever the "Block screenshots of documents" setting says.
         */
        fun privateFileIntent(context: Context, path: String, title: String): Intent =
            fileIntent(context, path, title).putExtra(EXTRA_PRIVATE, true)

        /** Opens any content-Uri PDF (device tab / picked file). */
        fun uriIntent(context: Context, uri: String, title: String): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .setData(android.net.Uri.parse(uri))
                .putExtra(EXTRA_TITLE, title)
    }
}
