package com.scanwhirl.app.ui.shell

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.tools.HomeHost
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.tools.MergeFlow
import com.scanwhirl.app.tools.ResizeFlow
import com.scanwhirl.app.tools.ScanLauncher
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ImportSheet
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.launchTool
import com.scanwhirl.app.util.PdfImport
import com.scanwhirl.app.ui.tools.DevicePdfGate
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2 UI: 01_home_A / 03_tools_hub / 06_pro — the v2 shell.
 *
 * Bottom bar: Library · Tools · (Scan disc) · Vault · Me. Library is the home
 * mockup; Tools is the full catalogue; Vault is honest until L2 lands; Me is
 * the restyled Settings entry. The legacy MainActivity stays in the tree for
 * the flows it still owns, but the launcher is this shell.
 */
class ShellActivity : com.scanwhirl.app.ui.BaseActivity(), ToolHost, HomeHost {

    enum class Tab { LIBRARY, TOOLS, VAULT, ME }

    private lateinit var repo: ScanRepository
    private lateinit var contentHost: FrameLayout
    private lateinit var navHost: View
    private lateinit var navItems: Map<Tab, LinearLayout>

    private var tab = Tab.LIBRARY
    private var proOpen = false
    private var lastShown: View? = null

    private lateinit var library: LibraryScreen
    private lateinit var toolsHub: ToolsHubScreen
    private lateinit var vaultScreen: VaultScreen
    private lateinit var meScreen: MeScreen
    private lateinit var proScreen: ProScreen

    private val scanLauncher: ScanLauncher by lazy {
        ScanLauncher(
            activity = this, repo = repo, host = this,
            startScanning = { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        )
    }

    private val mergeFlow: MergeFlow by lazy {
        MergeFlow(
            activity = this, repo = repo, host = this,
            docs = { runCatching { repo.listDocuments() }.getOrDefault(emptyList()) },
            onMerged = { merged ->
                refreshList()
                openDocument(merged.id)
            }
        )
    }

    private val compressFlow: CompressFlow by lazy {
        CompressFlow(
            activity = this, repo = repo, host = this,
            pickPdf = { pickCompressPdf.launch("application/pdf") },
            docs = { runCatching { repo.listDocuments() }.getOrDefault(emptyList()) }
        )
    }

    private val resizeFlow: ResizeFlow by lazy { ResizeFlow(this, repo, this) }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uris = scanResult?.pages.orEmpty().mapNotNull { it.imageUri }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
        } else {
            scanLauncher.stageAndReview(uris)
        }
    }

    private val pickGalleryImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> importPhotos(uris) }

    /**
     * v2 UI: 01_home_A — every photo import (Home and Tools) lands in Review with
     * the picked pages. Never a silent return: an empty pick says so.
     */
    internal fun importPhotos(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.photo_import_none, Toast.LENGTH_SHORT).show()
            return
        }
        scanLauncher.stageAndReview(uris)
    }

    private val pickImportPdf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importPdf(uri) }

    private val pickCompressPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { compressFlow.startFromFile(it) } }

    private val pickResizeImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { resizeFlow.start(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.gradient(
                this@ShellActivity,
                0x1200F0FF,
                SwStyle.color(this@ShellActivity, R.color.rv_bg),
                radiusDp = 0f
            )
        }
        contentHost = FrameLayout(this).apply { id = R.id.swTabContent }
        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        navHost = buildNav()
        root.addView(navHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 100f)))
        setContentView(root)
        root.applySystemBarsPadding()
        // v2 UI: 01_home_A — with the keyboard up the nav bar and scan disc must
        // not float over the chips: hide them and keep the content above the IME.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            navHost.isVisible =
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom == 0
            insets
        }

        library = LibraryScreen(this, this, repo, libraryCallbacks)
        toolsHub = ToolsHubScreen(this, this)
        vaultScreen = VaultScreen(this)
        meScreen = MeScreen(this, object : MeScreen.Callbacks {
            override fun onPro() = showPro()
            override fun onSettings() = startActivity(SettingsActivity.intent(this@ShellActivity))
            override fun onBackup() = startActivity(com.scanwhirl.app.ui.backup.BackupActivity.intent(this@ShellActivity))
            override fun onTrash() = startActivity(TrashActivity.intent(this@ShellActivity))
            override fun onAbout() = showAbout()
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    proOpen -> closePro()
                    tab != Tab.LIBRARY -> showTab(Tab.LIBRARY)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        showTab(Tab.LIBRARY)
        revealFromIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_PRO, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PRO)
            showPro()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        revealFromIntent(intent)
    }

    /**
     * v2 UI: 01_home_A — "Show in library" from a tool result opens the Library tab with that
     * document's card highlighted and scrolled into view.
     */
    private fun revealFromIntent(source: Intent?) {
        val docId = source?.getStringExtra(EXTRA_SHOW_DOC_ID) ?: return
        source.removeExtra(EXTRA_SHOW_DOC_ID)
        showTab(Tab.LIBRARY)
        library.revealDocument(docId)
    }

    override fun onResume() {
        super.onResume()
        if (::library.isInitialized) library.reload()
        // The scan disc breathes only while this screen is in front.
        findViewById<View>(R.id.swNavScan)?.let { com.scanwhirl.app.ui.kit.SwMotion.breathe(it) }
    }

    override fun onStop() {
        // Endless motion must not follow the user onto another screen.
        findViewById<View>(R.id.swNavScan)?.let { com.scanwhirl.app.ui.kit.SwMotion.stop(it) }
        super.onStop()
    }

    // ---- navigation -------------------------------------------------------

    private fun buildNav(): View {
        val container = FrameLayout(this)
        val bar = LinearLayout(this).apply {
            id = R.id.swShellNav
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.rounded(
                this@ShellActivity,
                0xF2161D21.toInt(),
                radiusDp = 24f,
                stroke = SwStyle.color(this@ShellActivity, R.color.sw_cyan_hair)
            )
        }
        val items = LinkedHashMap<Tab, LinearLayout>()
        fun item(tab: Tab, icon: Int, label: Int): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = getString(label)
                setOnClickListener { showTab(tab) }
            }
            cell.addView(
                ImageView(this@ShellActivity).apply {
                    setImageResource(icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(SwStyle.dp(this@ShellActivity, 24f), SwStyle.dp(this@ShellActivity, 24f))
            )
            cell.addView(
                SwStyle.text(this@ShellActivity, 11f, SwStyle.color(this@ShellActivity, R.color.text_hologram_dim), bold = true).apply {
                    text = getString(label)
                    gravity = Gravity.CENTER
                    tag = "label"
                    maxLines = 1
                    // The cell is a quarter of the bar minus the scan-disc gap;
                    // cap the label so a long tab name never clips at 2.0x.
                    val density = resources.displayMetrics.density
                    val scaled = resources.displayMetrics.scaledDensity
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        minOf(11f * scaled, 13f * density)
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ShellActivity, 3f) }
            )
            items[tab] = cell
            return cell
        }

        val libraryItem = item(Tab.LIBRARY, R.drawable.ic_sym_folder_open, R.string.shell_nav_library).apply {
            id = R.id.swNavLibrary
        }
        val toolsItem = item(Tab.TOOLS, R.drawable.ic_sym_apps, R.string.shell_nav_tools).apply {
            id = R.id.swNavTools
        }
        val vaultItem = item(Tab.VAULT, R.drawable.ic_sym_shield_lock, R.string.shell_nav_vault).apply {
            id = R.id.swNavVault
        }
        val meItem = item(Tab.ME, R.drawable.ic_sym_person, R.string.shell_nav_me).apply {
            id = R.id.swNavMe
        }
        bar.addView(libraryItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(toolsItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(SwStyle.dp(this, 86f), 1))
        bar.addView(vaultItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(meItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        navItems = items

        container.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 64f)
            ).apply {
                gravity = Gravity.BOTTOM
                val m = SwStyle.dp(this@ShellActivity, 12f)
                setMargins(m, 0, m, SwStyle.dp(this@ShellActivity, 12f))
            }
        )
        container.addView(buildDisc(), FrameLayout.LayoutParams(
            SwStyle.dp(this, 74f), SwStyle.dp(this, 74f)
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = SwStyle.dp(this@ShellActivity, 2f)
        })
        return container
    }

    private fun buildDisc(): View {
        val disc = FrameLayout(this).apply {
            id = R.id.swNavScan
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.shell_scan_disc_desc)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    SwStyle.color(this@ShellActivity, R.color.sw_disc_light),
                    SwStyle.color(this@ShellActivity, R.color.cyan_primary),
                    SwStyle.color(this@ShellActivity, R.color.sw_disc_dark)
                )
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(
                    SwStyle.dp(this@ShellActivity, 6f),
                    SwStyle.color(this@ShellActivity, R.color.rv_bg)
                )
            }
            setOnClickListener {
                com.scanwhirl.app.ui.kit.SwMotion.expandFrom(this) { scanLauncher.launchScanner() }
            }
            com.scanwhirl.app.ui.kit.SwMotion.press(this)
            com.scanwhirl.app.ui.kit.SwMotion.breathe(this)
        }
        disc.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_document_scanner)
                imageTintList = ColorStateList.valueOf(
                    SwStyle.color(this@ShellActivity, R.color.sw_disc_ink)
                )
            },
            FrameLayout.LayoutParams(SwStyle.dp(this, 34f), SwStyle.dp(this, 34f)).apply {
                gravity = Gravity.CENTER
            }
        )
        return disc
    }
    fun showTab(next: Tab) {
        proOpen = false
        tab = next
        val view: View = when (next) {
            Tab.LIBRARY -> {
                library.reload()
                scroll(library)
            }
            Tab.TOOLS -> scroll(toolsHub).also { toolsHub.clearSearch() }
            Tab.VAULT -> {
                vaultScreen.refreshStatus()
                scroll(vaultScreen)
            }
            Tab.ME -> scroll(meScreen)
        }
        val target = view
        // v2 UI: 74_motion — tabs fade through instead of snapping.
        com.scanwhirl.app.ui.kit.SwMotion.fadeSwitch(contentHost) { setContent(target) }
        paintNav()
    }

    private fun scroll(child: View): ScrollView = scrolls.getOrPut(child) {
        ScrollView(this).apply {
            isFillViewport = true
            // D: the nav bar + the scan disc's overhang are not part of the page,
            // but the last row must clear the disc when scrolled to the end.
            clipToPadding = false
            setPadding(0, 0, 0, SwStyle.dp(this@ShellActivity, 112f))
            addView(
                child,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val scrolls = mutableMapOf<View, ScrollView>()

    private fun setContent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        contentHost.removeAllViews()
        contentHost.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        lastShown = view
    }

    private fun paintNav() {
        val active = SwStyle.color(this, R.color.cyan_primary)
        val idle = SwStyle.color(this, R.color.text_hologram_dim)
        for ((key, cell) in navItems) {
            val on = key == tab && !proOpen
            val icon = if (on) navIconOn[key] else navIconOff[key]
            val glyph = cell.getChildAt(0) as ImageView
            icon?.let { glyph.setImageResource(it) }
            glyph.imageTintList = ColorStateList.valueOf(if (on) active else idle)
            (cell.findViewWithTag<TextView>("label"))?.setTextColor(if (on) active else idle)
        }
    }

    /** The nav's selected (filled) and idle (outlined) glyphs. */
    private val navIconOn = mapOf(
        Tab.LIBRARY to R.drawable.ic_sym_folder_filled,
        Tab.TOOLS to R.drawable.ic_sym_apps_filled,
        Tab.VAULT to R.drawable.ic_sym_shield_lock_filled,
        Tab.ME to R.drawable.ic_sym_person_filled
    )
    private val navIconOff = mapOf(
        Tab.LIBRARY to R.drawable.ic_sym_folder_open,
        Tab.TOOLS to R.drawable.ic_sym_apps,
        Tab.VAULT to R.drawable.ic_sym_shield_lock,
        Tab.ME to R.drawable.ic_sym_person
    )

    // ---- Pro --------------------------------------------------------------

    fun showPro() {
        proOpen = true
        proScreen = ProScreen(this, object : ProScreen.Callbacks {
            override fun onClose() = closePro()
            override fun onBuy() = showBillingSoon()
            override fun onRestore() = showBillingSoon()
            override fun onSeeAllProTools() = showTab(Tab.TOOLS)
        })
        setContent(scroll(proScreen))
        paintNav()
        // The Pro gate slides up as a sheet would; off when animations are off.
        if (com.scanwhirl.app.ui.kit.SwMotion.enabled(this)) {
            proScreen.alpha = 0f
            proScreen.translationY =
                com.scanwhirl.app.ui.kit.SwStyle.dp(this, 24f).toFloat()
            proScreen.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(
                    com.scanwhirl.app.ui.kit.SwMotion.duration(
                        this, com.scanwhirl.app.ui.kit.SwMotion.MEDIUM
                    )
                )
                .start()
        }
    }

    private fun closePro() {
        proOpen = false
        showTab(tab)
    }

    private fun showBillingSoon() {
        SwSheet.builder(this)
            .setTitle(getString(R.string.pro_unlock))
            .setMessage(getString(R.string.pro_buy_coming))
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }

    private fun showAbout() {
        SwSheet.builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message, versionName()))
            .setOptions(
                listOf(getString(R.string.settings_privacy), getString(R.string.about_licences)),
                -1
            ) { which, _ ->
                if (which == 0) openPrivacyPolicy() else showLicences()
            }
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://cybium.github.io/scanwhirl-privacy/")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.privacy_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicences() {
        SwSheet.builder(this)
            .setTitle(R.string.about_licences)
            .setMessage(R.string.about_licences_body)
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    // ---- Library ----------------------------------------------------------

    private val libraryCallbacks = object : LibraryScreen.Callbacks {
        override fun onScan() = scanLauncher.launchScanner()
        override fun onCreatePhotos() = showPhotoImportSheet()
        override fun onCreateImport() {
            ImportSheet.show(
                this@ShellActivity,
                onPhotos = { pickGalleryImages.launch("image/*") },
                onPdf = { pickImportPdf.launch(arrayOf("application/pdf")) }
            )
        }
        override fun onCreateIdCard() {
            ToolRegistry.byId("id_card")?.let { this@ShellActivity.launchTool(it) }
        }
        override fun onAllTools() = showTab(Tab.TOOLS)
        override fun onOpenDoc(doc: ScanDocument) = openDocument(doc.id)
        override fun onShareDoc(doc: ScanDocument) = ShareUtils.share(this@ShellActivity, doc)
        override fun onShareDocs(docs: List<ScanDocument>) = shareDocuments(docs)
        override fun onProRequired() = showPro()
        override fun onMoreDoc(doc: ScanDocument) = showDocActions(doc)
    }

    /** v2 UI: 03_tools_hub — share every selected document's PDF at once. */
    private fun shareDocuments(docs: List<ScanDocument>) {
        if (docs.size == 1) {
            ShareUtils.share(this, docs.single())
            return
        }
        val uris = ArrayList<android.net.Uri>()
        for (doc in docs) {
            runCatching {
                uris += androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", doc.pdfFile
                )
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share_pdf)))
    }

    private fun openDocument(id: String) {
        startActivity(DocumentViewerActivity.intent(this, id))
    }

    /**
     * v2 UI: 01_home_A — Photos: auto-cropped pages (the ML Kit gallery import:
     * corner detection, perspective crop, rotate) or the photos exactly as they are.
     * The last choice is remembered and pre-selected.
     */
    private fun showPhotoImportSheet() {
        val auto = com.scanwhirl.app.util.Prefs.galleryAutoCrop(this)
        SwSheet.builder(this)
            .setTitle(R.string.photos_add_title)
            .setOptions(
                listOf(
                    getString(R.string.photos_auto_crop),
                    getString(R.string.photos_keep)
                ),
                checked = if (auto) 0 else 1
            ) { which, _ ->
                com.scanwhirl.app.util.Prefs.setGalleryAutoCrop(this, which == 0)
                if (which == 0) {
                    com.scanwhirl.app.tools.launchGalleryCrop(
                        this,
                        maxPages = 30,
                        onResult = { uris -> if (uris.isNotEmpty()) scanLauncher.saveScanned(uris) },
                        onFallback = { uris -> if (uris.isNotEmpty()) scanLauncher.stageAndReview(uris) }
                    )
                } else {
                    pickGalleryImages.launch("image/*")
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /** v2 UI: 03_tools_hub — Searchable PDF is a real tool, never a jump to Settings. */
    private fun searchableSheet(doc: ScanDocument) {
        val already = doc.isSearchable
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.make_searchable_title)
            .setMessage(
                if (already) getString(R.string.make_searchable_already, doc.name)
                else getString(R.string.make_searchable_note, doc.name)
            )
            .build()
        if (already) {
            sheet.setPrimary(R.string.sheet_got_it)
        } else {
            sheet.setPrimary(R.string.make_searchable_action) {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { repo.makeSearchable(doc.id) }.getOrDefault(false)
                    }
                    Toast.makeText(
                        this@ShellActivity,
                        if (ok) getString(R.string.make_searchable_done, doc.name)
                        else getString(R.string.make_searchable_unavailable, doc.name),
                        Toast.LENGTH_LONG
                    ).show()
                    library.reload()
                }
            }
            sheet.setSecondary(R.string.sheet_cancel)
        }
        sheet.show()
    }

    private fun showDocActions(doc: ScanDocument) {
        val sheet = SwSheet.builder(this)
            .setTitle(doc.name)
            .setMessage(getString(R.string.shell_doc_actions_note))
            .setPrimary(R.string.shell_open) { openDocument(doc.id) }
            .setSecondary(R.string.share_pdf) { ShareUtils.share(this, doc) }
            .build()
        val delete = SwStyle.text(this, 13.5f, SwStyle.color(this, R.color.laser_danger), bold = true).apply {
            text = getString(R.string.shell_move_to_trash)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@ShellActivity, 40f)
            isClickable = true
            setOnClickListener {
                sheet.dismiss()
                confirmTrash(doc)
            }
        }
        sheet.setContent(delete)
        sheet.show()
    }

    private fun confirmTrash(doc: ScanDocument) {
        SwSheet.builder(this)
            .setTitle(getString(R.string.shell_trash_title, doc.name))
            .setMessage(getString(R.string.shell_trash_note))
            .setPrimary(R.string.shell_move_to_trash) {
                repo.deleteDocument(doc.id)
                library.reload()
                Toast.makeText(this, R.string.shell_moved_to_trash, Toast.LENGTH_SHORT).show()
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    // ---- Import -----------------------------------------------------------

    internal fun importPdf(uri: android.net.Uri) {
        // Lead merge: L4's planned import (page count, range, progress sheet) + L3's protected-PDF path.
        var job: kotlinx.coroutines.Job? = null
        var name: String? = null
        // L4's gate shows its own kit progress sheet after the page decision (no legacy spinner).
        var progress: SwSheet? = null
        job = lifecycleScope.launch {
            try {
                name = withContext(Dispatchers.IO) {
                    runCatching { repo.displayNameOf(uri) }.getOrNull()
                }?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                    ?.takeIf { it.isNotBlank() } ?: "Imported PDF"
                val doc = repo.importPdfAsScanPlanned(
                    uri = uri,
                    name = name,
                    onPlan = { plan ->
                        val decision = DevicePdfGate.decide(this@ShellActivity, plan)
                        if (decision != ScanRepository.ImportDecision.Cancel) {
                            progress = withContext(Dispatchers.Main) {
                                DevicePdfGate.progress(this@ShellActivity) { job?.cancel() }
                                    .also { it.show() }
                            }
                        }
                        decision
                    },
                    onPage = { done, total ->
                        runOnUiThread {
                            progress?.setMessage(
                                DevicePdfGate.progressText(this@ShellActivity, done, total)
                            )
                        }
                    }
                )
                progress?.dismiss()
                if (doc == null) return@launch
                ScanWhirlApp.from(this@ShellActivity).indexer.enqueue(doc.id)
                library.reload()
                Toast.makeText(
                    this@ShellActivity,
                    resources.getQuantityString(R.plurals.import_pdf_done, doc.pageCount, doc.pageCount),
                    Toast.LENGTH_SHORT
                ).show()
                openDocument(doc.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress?.dismiss()
                throw e
            } catch (e: Exception) {
                progress?.dismiss()
                if (e is PdfImport.ImportException && e.reason == PdfImport.Reason.PASSWORD) {
                    // Not a dead end: ask for the password, then import the decrypted pages.
                    LockFlow(this@ShellActivity, repo, this@ShellActivity, { emptyList() }, { _ -> })
                        .importProtectedPdf(uri, name)
                } else {
                    SwSheet.builder(this@ShellActivity)
                        .setTitle(getString(R.string.import_title))
                        .setMessage(e.message ?: getString(R.string.import_failed_save))
                        .setPrimary(R.string.sheet_got_it)
                        .build()
                        .show()
                }
            }
        }
    }

    // ---- ToolHost ---------------------------------------------------------

    override fun open(tool: SwTool) {
        when (tool.id) {
            "scan" -> scanLauncher.launchScanner()
            "photos_pdf" -> showPhotoImportSheet()
            "import_pdf" -> ImportSheet.show(
                this,
                onPhotos = { pickGalleryImages.launch("image/*") },
                onPdf = { pickImportPdf.launch(arrayOf("application/pdf")) }
            )
            "merge" -> mergeFlow.showMergeChooser()
            "compress" -> compressFlow.start()
            "resize" -> pickResizeImage.launch("image/*")
            "vault" -> showTab(Tab.VAULT)
            "folders" -> {
                showTab(Tab.LIBRARY)
                library.focusFolders()
            }
            "batch_rename" -> {
                showTab(Tab.LIBRARY)
                library.startBatchRename()
            }
            "searchable_pdf" -> showDocPicker { doc -> searchableSheet(doc) }
            "enhance", "bw", "to_text", "read_text" -> showDocPicker { doc ->
                if (tool.id == "to_text" || tool.id == "read_text") openText(doc) else openDocument(doc.id)
            }
            else -> comingSoon(tool)
        }
    }

    override fun comingSoon(tool: SwTool) {
        SwSheet.comingSoon(this, tool).show()
    }

    /** v2 UI: 06_pro — a Pro tool in a Free build opens the Pro screen. */
    override fun proGate(tool: SwTool): Boolean {
        if (com.scanwhirl.app.util.ProGate.isPro(this)) return false
        showPro()
        return true
    }

    private fun openText(doc: ScanDocument) {
        startActivity(
            Intent(this, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, doc.id)
                .putExtra(OcrEditorActivity.EXTRA_TITLE, doc.name)
        )
    }

    /** A small glass sheet of saved documents; picking runs [onPick]. */
    private fun showDocPicker(onPick: (ScanDocument) -> Unit) {
        val docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        if (docs.isEmpty()) {
            SwSheet.builder(this)
                .setTitle(getString(R.string.tool_pick_document))
                .setMessage(getString(R.string.tool_pick_document_note))
                .setPrimary(R.string.shell_nav_scan) { scanLauncher.launchScanner() }
                .setSecondary(R.string.sheet_cancel)
                .build()
                .show()
            return
        }
        val sheet = SwSheet.builder(this)
            .setTitle(getString(R.string.tool_pick_document))
            .setSecondary(R.string.sheet_cancel)
            .build()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = LinearLayout(this).apply {
            id = R.id.swDocPickerList
            orientation = LinearLayout.VERTICAL
        }
        var scroll: androidx.core.widget.NestedScrollView? = null
        var filt = ""
        fun render() {
            rows.removeAllViews()
            val q = filt.trim().lowercase()
            for (doc in docs.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(50)) {
                val pages = resources.getQuantityString(R.plurals.pages, doc.pageCount, doc.pageCount)
                val meta = "$pages · ${sizeLabel(doc)} · ${dayLabel(doc)}"
                val card = com.scanwhirl.app.ui.kit.DocCard(this).apply {
                    asCompact()
                    bind(doc, meta, onShare = {}, onMore = {})
                    setOnClickListener {
                        sheet.dismiss()
                        onPick(doc)
                    }
                }
                rows.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = SwStyle.dp(this@ShellActivity, 6f) }
                )
            }
            // v2 UI: 03_tools_hub — a 1-3 document chooser wraps its content;
            // a long one caps at 80% of the screen and scrolls.
            scroll?.layoutParams = if (rows.childCount <= 4) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.8f).toInt()
                )
            }
        }
        if (docs.size > 8) {
            val search = android.widget.EditText(this).apply {
                id = R.id.swDocPickerSearch
                hint = getString(R.string.home_search_hint)
                setHintTextColor(SwStyle.color(this@ShellActivity, R.color.text_hologram_dim))
                setTextColor(SwStyle.color(this@ShellActivity, R.color.text_hologram_hi))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                background = SwStyle.rounded(
                    this@ShellActivity,
                    SwStyle.color(this@ShellActivity, R.color.glass_surface_raised),
                    radiusDp = 14f,
                    stroke = SwStyle.color(this@ShellActivity, R.color.sw_cyan_hair)
                )
                setPadding(
                    SwStyle.dp(this@ShellActivity, 12f), SwStyle.dp(this@ShellActivity, 10f),
                    SwStyle.dp(this@ShellActivity, 12f), SwStyle.dp(this@ShellActivity, 10f)
                )
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        filt = s?.toString().orEmpty()
                        render()
                    }
                })
            }
            body.addView(
                search,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = SwStyle.dp(this@ShellActivity, 8f) }
            )
        }
        scroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = false
            addView(rows)
        }
        body.addView(scroll)
        render()
        sheet.setContent(body)
        sheet.show()
    }

    private fun sizeLabel(doc: ScanDocument): String {
        val bytes = runCatching {
            if (doc.pdfFile.exists()) doc.pdfFile.length() else doc.pageFiles.sumOf { it.length() }
        }.getOrDefault(0L)
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576f)
            bytes >= 1024 -> "${bytes / 1024} KB"
            bytes > 0 -> "$bytes B"
            else -> "0 B"
        }
    }

    private fun dayLabel(doc: ScanDocument): String =
        java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(doc.createdAt))

    // ---- HomeHost ---------------------------------------------------------

    override fun refreshList() {
        if (::library.isInitialized) library.reload()
    }

    override fun showProgress(titleRes: Int): com.scanwhirl.app.ui.kit.SwProgress =
        com.scanwhirl.app.ui.kit.SwProgress.show(this, getString(titleRes))

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    companion object {
        /** A tool result asks the Library tab to highlight this document. */
        const val EXTRA_SHOW_DOC_ID = "show_doc_id"

        /** Opens the Pro screen on launch (Settings' Gold option for Free users). */
        const val EXTRA_OPEN_PRO = "open_pro"

        fun intent(context: android.content.Context, docId: String? = null): Intent =
            Intent(context, ShellActivity::class.java).apply {
                if (!docId.isNullOrBlank()) putExtra(EXTRA_SHOW_DOC_ID, docId)
            }
    }
}
