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
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
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
    ) { uris ->
        if (uris.isNotEmpty()) scanLauncher.stageAndReview(uris)
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
        root.addView(buildNav(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 100f)))
        setContentView(root)
        root.applySystemBarsPadding()

        library = LibraryScreen(this, this, repo, libraryCallbacks)
        toolsHub = ToolsHubScreen(this, this)
        vaultScreen = VaultScreen(this) {
            SwSheet.note(
                this,
                getString(R.string.vault_tab_title),
                getString(R.string.vault_tab_coming)
            ).show()
        }
        meScreen = MeScreen(this, object : MeScreen.Callbacks {
            override fun onPro() = showPro()
            override fun onSettings() = startActivity(SettingsActivity.intent(this@ShellActivity))
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
    }

    override fun onResume() {
        super.onResume()
        if (::library.isInitialized) library.reload()
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
                setOnClickListener { showTab(tab) }
            }
            cell.addView(
                ImageView(this@ShellActivity).apply { setImageResource(icon) },
                LinearLayout.LayoutParams(SwStyle.dp(this@ShellActivity, 24f), SwStyle.dp(this@ShellActivity, 24f))
            )
            cell.addView(
                SwStyle.text(this@ShellActivity, 11f, SwStyle.color(this@ShellActivity, R.color.text_hologram_dim), bold = true).apply {
                    text = getString(label)
                    gravity = Gravity.CENTER
                    tag = "label"
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
            setOnClickListener { scanLauncher.launchScanner() }
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
            Tab.TOOLS -> scroll(toolsHub)
            Tab.VAULT -> scroll(vaultScreen)
            Tab.ME -> scroll(meScreen)
        }
        setContent(view)
        paintNav()
    }

    private fun scroll(child: View): ScrollView = scrolls.getOrPut(child) {
        ScrollView(this).apply {
            isFillViewport = true
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
            (cell.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) active else idle)
            (cell.findViewWithTag<TextView>("label"))?.setTextColor(if (on) active else idle)
        }
    }

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
        override fun onCreatePhotos() = pickGalleryImages.launch("image/*")
        override fun onCreateImport() {
            ImportSheet.show(
                this@ShellActivity,
                onPhotos = { pickGalleryImages.launch("image/*") },
                onPdf = { pickImportPdf.launch(arrayOf("application/pdf")) }
            )
        }
        override fun onCreateIdCard() {
            ToolRegistry.byId("id_card")?.let { comingSoon(it) }
        }
        override fun onAllTools() = showTab(Tab.TOOLS)
        override fun onOpenDoc(doc: ScanDocument) = openDocument(doc.id)
        override fun onShareDoc(doc: ScanDocument) = ShareUtils.share(this@ShellActivity, doc)
        override fun onMoreDoc(doc: ScanDocument) = showDocActions(doc)
    }

    private fun openDocument(id: String) {
        startActivity(DocumentViewerActivity.intent(this, id))
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

    private fun importPdf(uri: android.net.Uri) {
        var job: kotlinx.coroutines.Job? = null
        val progress = SwDialog.progress(this, titleRes = R.string.import_pdf_progress) { job?.cancel() }
        job = lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    runCatching { repo.displayNameOf(uri) }.getOrNull()
                }?.removeSuffix(".pdf")?.removeSuffix(".PDF")
                    ?.takeIf { it.isNotBlank() } ?: "Imported PDF"
                val doc = repo.importPdfAsScan(uri, name)
                ScanWhirlApp.from(this@ShellActivity).indexer.enqueue(doc.id)
                progress.dismiss()
                library.reload()
                Toast.makeText(
                    this@ShellActivity,
                    resources.getQuantityString(R.plurals.import_pdf_done, doc.pageCount, doc.pageCount),
                    Toast.LENGTH_SHORT
                ).show()
                openDocument(doc.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Exception) {
                progress.dismiss()
                SwSheet.builder(this@ShellActivity)
                    .setTitle(getString(R.string.import_title))
                    .setMessage(e.message ?: getString(R.string.import_failed_save))
                    .setPrimary(R.string.sheet_got_it)
                    .build()
                    .show()
            }
        }
    }

    // ---- ToolHost ---------------------------------------------------------

    override fun open(tool: SwTool) {
        when (tool.id) {
            "scan" -> scanLauncher.launchScanner()
            "photos_pdf" -> pickGalleryImages.launch("image/*")
            "import_pdf" -> ImportSheet.show(
                this,
                onPhotos = { pickGalleryImages.launch("image/*") },
                onPdf = { pickImportPdf.launch(arrayOf("application/pdf")) }
            )
            "merge" -> mergeFlow.showMergeChooser()
            "compress" -> compressFlow.start()
            "resize" -> pickResizeImage.launch("image/*")
            "vault" -> showTab(Tab.VAULT)
            "searchable_pdf" -> startActivity(SettingsActivity.intent(this))
            "enhance", "bw", "to_text", "read_text" -> showDocPicker { doc ->
                if (tool.id == "to_text" || tool.id == "read_text") openText(doc) else openDocument(doc.id)
            }
            else -> comingSoon(tool)
        }
    }

    override fun comingSoon(tool: SwTool) {
        SwSheet.comingSoon(this, tool).show()
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
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (doc in docs.take(10)) {
            list.addView(
                SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                    text = doc.name
                    minimumHeight = SwStyle.dp(this@ShellActivity, 48f)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setPadding(
                        SwStyle.dp(this@ShellActivity, 4f), 0,
                        SwStyle.dp(this@ShellActivity, 4f), 0
                    )
                    setOnClickListener {
                        sheet.dismiss()
                        onPick(doc)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ShellActivity, 48f)
                )
            )
        }
        sheet.setContent(list)
        sheet.show()
    }

    // ---- HomeHost ---------------------------------------------------------

    override fun refreshList() {
        if (::library.isInitialized) library.reload()
    }

    override fun showProgress(titleRes: Int): androidx.appcompat.app.AlertDialog =
        SwDialog.progress(this, titleRes = titleRes)

    override fun toast(res: Int, duration: Int) {
        Toast.makeText(this, res, duration).show()
    }

    override fun toast(message: CharSequence, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }
}
