package com.scanwhirl.app.ui.shell

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
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
import com.scanwhirl.app.ui.DocumentActionDispatcher
import com.scanwhirl.app.ui.DocumentActionsSheet
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ImportSheet
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.ProtectionDialogs
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.TrashActions
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
import kotlinx.coroutines.flow.first
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
    private var discardActions: com.scanwhirl.app.ui.DiscardActions? = null
    private lateinit var navItems: Map<Tab, LinearLayout>

    private var tab = Tab.LIBRARY

    /** SH1 #3: the look this instance was built with. A change needs an immediate redraw. */
    private var appliedFont: String = ""
    private var appliedLanguage: String = ""

    /** Fail-closed guard: one look change can trigger at most one recreate per instance. */
    private var lookRecreateIssued = false

    private var proOpen = false
    private var proRequest = 0
    private var buying = false
    private var lastProState: Boolean? = null
    private var lastShown: View? = null

    /** L1-06: one searchable-PDF operation per instance; a second tap is a no-op. */
    private var searchableInFlight = false

    /** L1-06: a bounded restore action; the X/Close on the sheet is always available. */
    private var restoreInFlight = false

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
        onScannerPages(uris)
    }

    /**
     * F22: Google's scanner already showed the crop, the filters and every page, so its Done
     * saves straight through [ScanLauncher.saveScanned] and the new document opens. The app's
     * second review screen (ScanReviewActivity) is no longer part of the camera scan.
     */
    internal fun onScannerPages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.scan_no_pages, Toast.LENGTH_SHORT).show()
        } else {
            scanLauncher.saveScanned(uris, openAfterSave = true)
        }
    }

    private val pickGalleryImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val fromCreate = pickerReturnFocus
        pickerReturnFocus = false
        importPhotos(uris)
        // W7: a cancelled photo picker imports nothing and hands focus back to Create.
        if (uris.isEmpty() && fromCreate) focusCreateDisc()
    }

    /**
     * W7: true while a picker opened from the Create sheet (or a Create-labelled import) is in
     * front. A Cancel then returns keyboard and TalkBack focus to the Create button instead of
     * leaving it nowhere.
     */
    private var pickerReturnFocus = false

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
    ) { uri ->
        val fromCreate = pickerReturnFocus
        pickerReturnFocus = false
        if (uri != null) importPdf(uri) else if (fromCreate) focusCreateDisc()
    }

    private val pickCompressPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { compressFlow.startFromFile(it) } }

    private val pickResizeImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { resizeFlow.start(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        appliedFont = com.scanwhirl.app.util.Prefs.fontStyle(this)
        appliedLanguage = com.scanwhirl.app.util.AppLanguage.currentTag(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The shipped look is painted first and stays until an optional wallpaper has
            // been decoded off the main thread (loadWallpaper below): no flash or jank.
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
        navHost.minimumHeight = SwStyle.dp(this, 100f)
        root.addView(navHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        loadWallpaper(root)
        // v2 UI: 01_home_A — with the keyboard up the nav bar and scan disc must not
        // float over the chips. One listener only: the shared helper already keeps the
        // base padding, the cutout/top inset and max(nav bar, IME) at the bottom, and
        // hides the dock as its side effect. A second listener here would replace it
        // (A6 #17: the search field sat behind the keyboard).
        root.applySystemBarsPadding { insets ->
            navHost.isVisible = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
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
        // Play's localized price: this query only fills ProBilling's cache. No Pro screen exists
        // yet, and the callback may arrive before or after one does, so it must never touch UI.
        com.scanwhirl.app.util.ProBilling.prefetchPrice(this)

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

        // SH1 #2: the selected tab is part of the screen's state, not a fresh-start choice.
        val startTab = savedInstanceState?.getString(KEY_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: Tab.LIBRARY
        showTab(startTab)
        // L1-02: the rest of the library's place is restored only after the async data load
        // in showTab() has produced a real document list (LibraryScreen.applyPendingRestore).
        if (savedInstanceState != null && startTab == Tab.LIBRARY) {
            library.restoreLibraryState(
                com.scanwhirl.app.ui.shell.LibraryUiState(
                    query = savedInstanceState.getString(KEY_QUERY).orEmpty(),
                    folder = savedInstanceState.getString(KEY_FOLDER),
                    category = savedInstanceState.getString(KEY_CATEGORY),
                    sort = savedInstanceState.getString(KEY_SORT)
                        ?: com.scanwhirl.app.util.Prefs.LIB_NEWEST,
                    selection = savedInstanceState.getStringArray(KEY_SELECTION)?.toList().orEmpty(),
                    selectionMode = savedInstanceState.getBoolean(KEY_SELECTION_MODE, false),
                    scrollY = savedInstanceState.getInt(KEY_SCROLL, 0)
                )
            )
        }
        // F1 (hunt H1-1): storage recovery once per process, off the main thread; the Library
        // reloads when it finishes so a document an interrupted Replace took away is back.
        val app = ScanWhirlApp.from(this)
        app.startStorageRecovery()
        // S1 defect 1 / review P1: the launcher owns the "unfinished scan" offer once the
        // recovery that can restore a document has finished. The once-per-process guard lives in
        // ScanLauncher.show(), so a rotation before the offer is shown does not lose it.
        if (!app.storageRecoveryFinished.value) {
            lifecycleScope.launch {
                // The pass always finishes (possibly incomplete); a failed folder step is retried
                // next start and is never allowed to leave the launcher waiting forever.
                app.storageRecoveryFinished.first { it }
                if (::library.isInitialized) library.reload()
                scanLauncher.offerUnfinishedScan()
            }
        } else {
            scanLauncher.offerUnfinishedScan()
        }
        revealFromIntent(intent)
        lastProState = com.scanwhirl.app.util.ProGate.isPro(this)
        openProFromIntent(intent)
    }

    /**
     * SH1 review P1/P2: decode the chosen photo off the main thread, exactly like the
     * legacy Home does, then apply it under a translucent ink scrim so the shell's text
     * keeps its contrast. The gradient set in onCreate remains until the decode finishes,
     * so a slow photo can never flash a wrong background or block the UI thread.
     */
    private fun loadWallpaper(root: LinearLayout) {
        lifecycleScope.launch {
            val photo = withContext(Dispatchers.IO) {
                wallpaperProbe?.invoke()
                com.scanwhirl.app.util.Wallpaper.drawable(this@ShellActivity)
            }
            if (isFinishing || isDestroyed || photo == null) return@launch
            val ink = SwStyle.color(this@ShellActivity, R.color.rv_bg)
            val scrim = ColorDrawable(
                Color.argb(SCRIM_ALPHA, Color.red(ink), Color.green(ink), Color.blue(ink))
            )
            root.background = LayerDrawable(arrayOf(photo, scrim))
        }
    }

    /** SH1 review P2: the Pro deep link can also arrive on a live shell. */
    private fun openProFromIntent(source: Intent?) {
        if (source?.getBooleanExtra(EXTRA_OPEN_PRO, false) == true) {
            source.removeExtra(EXTRA_OPEN_PRO)
            showPro()
        }
    }

    /** SH1 #2 + L1-02: keep the tab and the library's place through rotation/recreate. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_TAB, tab.name)
        if (::library.isInitialized) {
            val state = library.captureLibraryState()
            outState.putString(KEY_QUERY, state.query)
            outState.putString(KEY_FOLDER, state.folder)
            outState.putString(KEY_CATEGORY, state.category)
            outState.putString(KEY_SORT, state.sort)
            outState.putStringArray(KEY_SELECTION, state.selection.toTypedArray())
            outState.putBoolean(KEY_SELECTION_MODE, state.selectionMode)
            outState.putInt(KEY_SCROLL, state.scrollY)
        }
        super.onSaveInstanceState(outState)
    }

    /** L1-02: the library's pinned scroll anchor, read from the shell's own ScrollView. */
    internal fun libraryScrollY(): Int =
        if (::library.isInitialized) scrolls[library]?.scrollY ?: 0 else 0

    /** L1-02: restore the anchor after the recreated list has been laid out. */
    internal fun restoreLibraryScrollY(y: Int) {
        if (!::library.isInitialized) return
        val scroller = scroll(library)
        scroller.post { scroller.scrollTo(0, y) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        revealFromIntent(intent)
        openProFromIntent(intent)
    }

    /**
     * v2 UI: 01_home_A — "Show in library" from a tool result opens the Library tab with that
     * document's card highlighted and scrolled into view.
     */
    private fun revealFromIntent(source: Intent?) {
        val docId = source?.getStringExtra(EXTRA_SHOW_DOC_ID) ?: return
        source.removeExtra(EXTRA_SHOW_DOC_ID)
        // SH1 #7: the extra may come from another app or a stale back stack. Only a real,
        // readable, unprotected document may be revealed; unknown values are ignored.
        if (docId.isBlank()) return
        val doc = runCatching { repo.getDocument(docId) }.getOrNull() ?: return
        if (doc.isLocked || doc.isVaulted) return
        showTab(Tab.LIBRARY)
        library.revealDocument(doc.id)
    }

    private var billingUnsub: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        // Replay lands first: a recreated screen catches Busy/Owned without a second sheet.
        billingUnsub = com.scanwhirl.app.util.ProBilling.subscribe { state, replay ->
            if (isFinishing || isDestroyed) return@subscribe
            if (replay) {
                setBuying(com.scanwhirl.app.util.ProBilling.isPurchaseInFlight())
                if (state == com.scanwhirl.app.util.ProBilling.State.Owned) {
                    syncProState()
                    // A purchase that finished while this screen was stopped (behind Play's sheet,
                    // or before a rotation) must not leave a Free paywall on screen: redraw it.
                    if (proOpen && com.scanwhirl.app.util.ProGate.isPro(this)) showPro()
                }
                return@subscribe
            }
            showBillingState(state)
        }
        // p2pro #1: a silent foreground reconcile changes Pro without publishing a purchase
        // State. Rebind the cached Free/Pro tiles whenever an authoritative answer changes it.
        com.scanwhirl.app.util.ProBilling.setEntitlementChangedListener { syncProState() }
    }

    override fun onResume() {
        super.onResume()
        // SH1 #3: Settings changes the font and the app language on its own screen. The shell
        // underneath is stopped, not rebuilt, so it would keep the old look until a cold
        // start. recreate() carries the selected tab through onSaveInstanceState.
        if (!lookRecreateIssued &&
            (appliedFont != com.scanwhirl.app.util.Prefs.fontStyle(this) ||
                appliedLanguage != com.scanwhirl.app.util.AppLanguage.currentTag(this))
        ) {
            lookRecreateIssued = true
            recreate()
            return
        }
        syncProState()
        // Cheap once-per-process ownership check: a purchase on another phone / after a reinstall
        // unlocks, and a refund locks Pro again (release builds only).
        com.scanwhirl.app.util.ProBilling.refreshOwned(this)
        if (::library.isInitialized) library.reload()
        // T27-FIX-TRASH: a move made on the document screen closed that screen; its
        // "Moved to Trash" Undo is shown here, on the next resume.
        TrashActions.takePending()?.let { showMovedNotice(it) }
        showDiscardNotice()
        // The scan disc breathes only while this screen is in front.
        findViewById<View>(R.id.swNavScan)?.let { com.scanwhirl.app.ui.kit.SwMotion.breathe(it) }
    }

    /** Re-binds tool tiles when Free/Pro changes (purchase or the test switch). */
    internal fun syncProState() {
        val pro = com.scanwhirl.app.util.ProGate.isPro(this)
        if (lastProState == pro) return
        val hadState = lastProState != null
        lastProState = pro
        if (!hadState) return
        if (::library.isInitialized) library.refreshProState()
        if (::toolsHub.isInitialized) toolsHub.refreshProState()
        if (::meScreen.isInitialized) meScreen.refreshProState()
    }

    override fun onStop() {
        billingUnsub?.invoke()
        billingUnsub = null
        com.scanwhirl.app.util.ProBilling.setEntitlementChangedListener(null)
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
            // G6: the dock grows with wrapped labels instead of clipping them.
            minimumHeight = SwStyle.dp(this@ShellActivity, 64f)
            background = SwStyle.rounded(
                this@ShellActivity,
                0xF2161D21.toInt(),
                radiusDp = 24f,
                stroke = SwStyle.color(this@ShellActivity, R.color.sw_cyan_hair)
            )
        }
        val items = LinkedHashMap<Tab, LinearLayout>()
        fun item(tab: Tab, icon: Int, label: Int): LinearLayout {
            val destination = tab
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = getString(label)
                setOnClickListener { showTab(tab) }
            }
            // L1-03: real accessibility state, not a manually appended word. The full
            // label stays in contentDescription; the selected tab is a selectable node.
            cell.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isSelected = this@ShellActivity.tab == destination
                    info.addAction(AccessibilityNodeInfo.ACTION_SELECT)
                }
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
                    // LEAD DECISION (G6): no dp cap, no ellipsis. >=12sp and up to two
                    // wrapped lines; the dock grows and clearance is measured from it.
                    maxLines = 2
                    ellipsize = null
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    //noinspection WrongConstant
                    breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
        // G6: cells wrap their labels; the bar (and dock) grows with them at 200 %.
        bar.addView(libraryItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(toolsItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(SwStyle.dp(this, 86f), 1))
        bar.addView(vaultItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(meItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navItems = items

        container.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
            // The disc is the primary action: after a sheet closes, focus must be able to
            // return to it even on a touch-mode phone (the focus ring never shows on touch).
            isFocusableInTouchMode = true
            contentDescription = getString(R.string.create_disc_desc)
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
            // UI-B1 (#2): the centre action opens the Create sheet; the camera is one choice
            // inside it, next to ID card, Passport, QR code, photos and PDF.
            setOnClickListener {
                com.scanwhirl.app.ui.kit.SwMotion.expandFrom(this) { showCreateSheet() }
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
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(SwStyle.dp(this, 34f), SwStyle.dp(this, 34f)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = SwStyle.dp(this@ShellActivity, 8f)
            }
        )
        // L1-03: a visible, base-12sp caption. It lives INSIDE the disc's single focus
        // stop, so TalkBack still hears one named Scan action rather than a second node.
        disc.addView(
            SwStyle.text(this, 12f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
                id = R.id.uxL1ScanCaption
                text = getString(R.string.ux130_l1_scan_caption)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                includeFontPadding = false
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = SwStyle.dp(this@ShellActivity, 7f)
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
            // The dock is a sibling under the page, so a hard clip at the fold looks
            // like the last row was sliced. Padding equal to the dock (measured, not
            // a magic number) lets that row scroll fully above the disc.
            clipToPadding = false
            setPadding(0, 0, 0, dockClearancePx())
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val pad = dockClearancePx()
                if (v.paddingBottom != pad) {
                    v.post {
                        if (v.paddingBottom != dockClearancePx()) {
                            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dockClearancePx())
                        }
                    }
                }
            }
            addView(
                child,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    /**
     * How far the last row must sit above the viewport bottom so it clears the dock.
     * The nav host's laid-out height already holds the bar and the scan disc; the
     * bar's own bottom margin is the breathing room under that. Read from the dock
     * views so a resize cannot clip the last row again. Zero while the keyboard
     * has hidden the dock.
     */
    internal fun dockClearancePx(): Int {
        if (!::navHost.isInitialized || !navHost.isVisible) return 0
        val bar = findViewById<View>(R.id.swShellNav)
        val hostH = when {
            navHost.height > 0 -> navHost.height
            (navHost.layoutParams?.height ?: 0) > 0 -> navHost.layoutParams.height
            else -> SwStyle.dp(this, 100f)
        }
        val margin = (bar?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
            ?: SwStyle.dp(this, 12f)
        return hostH + margin + discOverlapPx()
    }

    /**
     * How far the floating dock actually covers the page bottom. The dock is a sibling under the
     * page, so this is normally zero; a dock that is later drawn over the page returns its real
     * overlap and the Pro screen's pinned offer lifts by exactly that.
     */
    internal fun dockOverlapPx(): Int {
        if (!::navHost.isInitialized || !navHost.isVisible) return 0
        if (contentHost.height <= 0 || navHost.height <= 0) return 0
        val root = navHost.parent as? View ?: return 0
        val dockTop = offsetIn(navHost, root)
        val contentBottom = offsetIn(contentHost, root) + contentHost.height
        return (contentBottom - dockTop).coerceAtLeast(0)
    }

    /** How many pixels the scan disc is drawn above the page. Zero while it sits in the nav host. */
    private fun discOverlapPx(): Int {
        if (navHost.height <= 0 || contentHost.height <= 0) return 0
        val disc = findViewById<View>(R.id.swNavScan) ?: return 0
        val root = navHost.parent as? View ?: return 0
        val discTop = offsetIn(disc, root)
        val contentBottom = offsetIn(contentHost, root) + contentHost.height
        return (contentBottom - discTop).coerceAtLeast(0)
    }

    private fun offsetIn(view: View, ancestor: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== ancestor) {
            y += current.top
            val parent = current.parent as? View ?: break
            y -= parent.scrollY
            current = parent
        }
        return y
    }

    private val scrolls = mutableMapOf<View, ScrollView>()

    private fun setContent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        contentHost.removeAllViews()
        contentHost.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // 24dp (sw_space_6) fade into the page ink, so a card at the fold dissolves
        // instead of being sliced on the dock. Not clickable: touches pass through.
        contentHost.addView(
            View(this).apply {
                tag = DOCK_FADE
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, SwStyle.color(this@ShellActivity, R.color.rv_bg))
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.sw_space_6)
            ).apply { gravity = Gravity.BOTTOM }
        )
        lastShown = view
    }

    private fun paintNav() {
        val active = SwStyle.color(this, R.color.cyan_primary)
        val idle = SwStyle.color(this, R.color.text_hologram_dim)
        for ((key, cell) in navItems) {
            val on = key == tab && !proOpen
            cell.isSelected = on
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
        val request = ++proRequest
        val screen = ProScreen(this, object : ProScreen.Callbacks {
            override fun onClose() = closePro()
            override fun onBuy() = startBilling()
            override fun onRestore() = restoreBilling()
            override fun onSeeAllProTools() = showTab(Tab.TOOLS)
        }, active = com.scanwhirl.app.util.ProGate.isPro(this))
        proScreen = screen
        // Play's localized price: the launch-time query usually finishes before this screen
        // exists, so apply the cached answer now and refresh it on this exact instance.
        com.scanwhirl.app.util.ProBilling.cachedPrice(this)?.let { screen.setPrice(it) }
        // A purchase may still be in flight after a rotation; keep its Buy row disabled.
        screen.setBuying(com.scanwhirl.app.util.ProBilling.isPurchaseInFlight())
        // H3 (device sweep 2026-11-19): the Pro screen owns its own scroll area and pins the price
        // and Unlock row above the dock, so the offer is readable on entry. It therefore replaces
        // the shell's generic scroll wrapper instead of sitting inside it.
        screen.keepClearOfDock { dockOverlapPx() }
        setContent(screen)
        paintNav()
        com.scanwhirl.app.util.ProBilling.price(this) { price ->
            // An older response (or a screen that is gone) must not overwrite this one.
            if (price != null && request == proRequest && proOpen &&
                !isFinishing && !isDestroyed
            ) {
                screen.setPrice(price)
            }
        }
    }

    private fun closePro() {
        proOpen = false
        proRequest++
        showTab(tab)
    }

    private fun startBilling() {
        // One in-flight purchase: a double tap must not open two Play sheets.
        // The outcome is delivered to the onStart subscriber, not a callback that
        // would keep this Activity alive after rotation.
        if (buying || com.scanwhirl.app.util.ProBilling.isPurchaseInFlight()) return
        setBuying(true)
        com.scanwhirl.app.util.ProBilling.buy(this) { }
    }

    private fun setBuying(busy: Boolean) {
        buying = busy
        if (::proScreen.isInitialized) proScreen.setBuying(busy)
    }

    private fun restoreBilling() {
        // L1-06: one restore in flight; the outcome still reaches the R1 subscriber.
        if (restoreInFlight) return
        restoreInFlight = true
        com.scanwhirl.app.util.ProBilling.restore(this) { restoreInFlight = false }
    }

    /** One short sheet per outcome; a cancel stays silent. */
    private fun showBillingState(state: com.scanwhirl.app.util.ProBilling.State) {
        when (state) {
            com.scanwhirl.app.util.ProBilling.State.Owned -> {
                setBuying(false)
                restoreInFlight = false
                // p2pro #1: Tools/Library/Me cache Free/Pro; a successful Buy must rebind them
                // now, not only after the next resume or app restart.
                syncProState()
                Toast.makeText(this, R.string.pro_unlocked, Toast.LENGTH_SHORT).show()
                showPro()
            }
            com.scanwhirl.app.util.ProBilling.State.Pending -> {
                setBuying(false)
                SwSheet.builder(this)
                    .setTitle(getString(R.string.pro_pending_title))
                    .setMessage(getString(R.string.pro_pending))
                    .setPrimary(R.string.sheet_got_it)
                    .build().show()
            }
            com.scanwhirl.app.util.ProBilling.State.Finishing ->
                Toast.makeText(this, R.string.pro_finishing, Toast.LENGTH_SHORT).show()
            com.scanwhirl.app.util.ProBilling.State.Busy -> setBuying(true)
            com.scanwhirl.app.util.ProBilling.State.None -> {
                setBuying(com.scanwhirl.app.util.ProBilling.isPurchaseInFlight())
                restoreInFlight = false
                if (proOpen &&
                    com.scanwhirl.app.util.ProBilling.lastOperation() ==
                    com.scanwhirl.app.util.ProBilling.Op.RESTORE
                ) {
                    SwSheet.builder(this)
                        .setTitle(getString(R.string.pro_restore_title))
                        .setMessage(getString(R.string.pro_restore_none))
                        .setPrimary(R.string.sheet_got_it)
                        .build().show()
                }
            }
            is com.scanwhirl.app.util.ProBilling.State.Failed -> {
                setBuying(false)
                restoreInFlight = false
                // L1-06: no dead end. Retry and Restore call the existing bounded methods;
                // the sheet's own Close (swSheetClose) dismisses without either action.
                SwSheet.builder(this)
                    .setTitle(getString(R.string.ux130_l1_purchase_failed))
                    .setMessage(state.message)
                    .setPrimary(R.string.ux130_l1_purchase_retry) { startBilling() }
                    .setSecondary(R.string.ux130_l1_purchase_restore) { restoreBilling() }
                    .build().show()
            }
        }
    }

    private fun showAbout() {
        // UI-B1 (#1): the Home trust pill moved here, so the promise is one tap away
        // (IDENTITY.md: "No internet permission. No ads. No account."; the shipped short form
        // is the existing trust string, now stated on About and on the empty Library).
        // C1 (2026-11-20 owner): about_message carries the complete short privacy line too;
        // this last line keeps the shared trust pill on About.
        val message = getString(R.string.about_message, versionName()) +
            "\n\n" + getString(R.string.trust_offline_no_ads)
        SwSheet.builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(message)
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
                // W7: the Create sheet's photos row now opens the OS photo picker in one tap.
                // The auto-crop / keep-as-they-are choice keeps its own route here, so the
                // ML Kit gallery auto-crop is still reachable (Library's Import, then Photos).
                onPhotos = { showPhotoImportSheet() },
                onPdf = { pickImportPdf.launch(arrayOf("application/pdf")) }
            )
        }
        override fun onCreateIdCard() {
            ToolRegistry.byId("id_card")?.let { this@ShellActivity.launchTool(it) }
        }
        override fun onAllTools() = showTab(Tab.TOOLS)
        override fun onOpenDoc(doc: ScanDocument) = openDocument(doc.id)
        override fun onShareDoc(doc: ScanDocument) = shareRow(doc)
        override fun onShareDocs(docs: List<ScanDocument>) = shareDocuments(docs)
        override fun onProRequired() = showPro()
        override fun onMoreDoc(doc: ScanDocument) = showDocActions(doc)
        // T40-FIX-SNACK-ANCHOR: the same anchored notice the single-row path uses.
        override fun onMovedToTrash(moved: TrashActions.Moved) = showMovedNotice(moved)
    }

    /**
     * UI-B1 (#2): the centre Scan action opens one Create sheet. Every row routes to the same
     * launcher the old Home tiles used; QR keeps the registry's Pro gate through launchTool.
     * Back/Close/outside tap launches nothing and returns focus to the Scan button.
     *
     * W7: Import PDF and Import photos are one tap each — the named row opens its own OS
     * picker. No second Photos/PDF chooser stands between the row and the picker, and the
     * photos route can no longer reach the Play-services scanner through "Auto-crop pages"
     * (that option stays in the Library's Add photos sheet).
     */
    private fun showCreateSheet() {
        val sheet = CreateSheet(this, object : CreateSheet.Actions {
            override fun onScan() = scanLauncher.launchScanner()

            override fun onIdCard() {
                ToolRegistry.byId("id_card")?.let { this@ShellActivity.launchTool(it) }
            }

            override fun onPassport() {
                ToolRegistry.byId("passport")?.let { this@ShellActivity.launchTool(it) }
            }

            override fun onQr() {
                ToolRegistry.byId("qr_code")?.let { this@ShellActivity.launchTool(it) }
            }

            override fun onImportPhotos() {
                pickerReturnFocus = true
                pickGalleryImages.launch("image/*")
            }

            override fun onImportPdf() {
                pickerReturnFocus = true
                pickImportPdf.launch(arrayOf("application/pdf"))
            }
        })
        sheet.show { chosenSomething ->
            if (chosenSomething) return@show
            // Back or Close launches nothing: keyboard and TalkBack focus return to Scan.
            focusCreateDisc()
        }
    }

    /** W7: focus back on the Create button, the control that opened the sheet or picker. */
    private fun focusCreateDisc() {
        val disc = findViewById<View>(R.id.swNavScan) ?: return
        disc.post {
            disc.requestFocus()
            androidx.core.view.ViewCompat.performAccessibilityAction(
                disc,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                null
            )
        }
    }

    /** v2 UI: 03_tools_hub — share every selected document's PDF at once. */
    private fun shareDocuments(docs: List<ScanDocument>) {
        if (docs.size == 1) {
            ShareUtils.share(this, docs.single())
            return
        }
        // H2-1: every PDF goes out under its document's own name (never the stored scan.pdf),
        // copied into one fresh share folder. fixshare: the helper's intent carries every Uri in
        // ClipData too, so the Android 10+ grant covers all of them.
        ShareUtils.shareDocuments(this, docs)
    }

    private fun openDocument(id: String) {
        startActivity(DocumentViewerActivity.intent(this, id))
    }

    /**
     * W1: a Library row's Share is the same bound output as the document panel's: the row's id is
     * re-read at the tap, an inconsistent lock is repaired instead of exported silently, an
     * unsaved draft asks first, and a vanished file says so instead of opening an empty chooser.
     */
    private fun shareRow(doc: ScanDocument) {
        ProtectionDialogs.guardExport(this, repo, doc, onResolved = { library.reload() }) {
            DocumentActionDispatcher.dispatch(
                activity = this,
                repo = repo,
                docId = doc.id,
                action = DocumentActionDispatcher.Action.SHARE,
                onFinishEditing = { bound -> openDocument(bound.id) }
            ) { bound -> ShareUtils.share(this, bound) }
        }
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
                        activity = this,
                        maxPages = 30,
                        // S1 review P0: the handler runs on whichever instance receives the result.
                        onResult = { host, _, uris ->
                            if (host is ShellActivity && uris.isNotEmpty()) {
                                host.applyGalleryCrop(uris, autoCropped = true)
                            }
                        },
                        onFallback = { host, _, uris ->
                            if (host is ShellActivity && uris.isNotEmpty()) {
                                host.applyGalleryCrop(uris, autoCropped = false)
                            }
                        }
                    )
                } else {
                    pickGalleryImages.launch("image/*")
                }
            }
            .setSecondary(R.string.sheet_cancel)
            .build()
            .show()
    }

    /** S1 review P0: applies a crop result on the live screen, not a captured old one. */
    private fun applyGalleryCrop(uris: List<android.net.Uri>, autoCropped: Boolean) {
        if (autoCropped) scanLauncher.saveScanned(uris) else scanLauncher.stageAndReview(uris)
    }

    /**
     * L1-06: a named, durable outcome instead of a vanishing Toast. The work is bounded
     * to one run, progress is shown immediately and always dismissed, and Open/Share appear
     * only after the repository confirms the text layer actually exists. Nothing here routes
     * into the result Undo, so a failed operation can never delete the source document.
     */
    private fun searchableSheet(doc: ScanDocument) {
        if (doc.isSearchable) {
            SwSheet.builder(this)
                .setTitle(R.string.make_searchable_title)
                .setMessage(getString(R.string.ux130_l1_text_layer_already))
                .setPrimary(R.string.sheet_got_it)
                .build().show()
            return
        }
        if (searchableInFlight) return
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.make_searchable_title)
            .setMessage(getString(R.string.make_searchable_note, doc.name))
            .build()
        sheet.setPrimary(R.string.make_searchable_action) {
            if (searchableInFlight) return@setPrimary
            searchableInFlight = true
            sheet.dismiss()
            val progress = showProgress(R.string.ux130_l1_adding_text_layer)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { searchableProbe?.invoke(doc.id) ?: repo.makeSearchable(doc.id) }
                        .getOrDefault(false)
                }
                val verified = ok && repo.getDocument(doc.id)?.isSearchable == true
                progress.dismiss()
                searchableInFlight = false
                if (isFinishing || isDestroyed) return@launch
                library.reload()
                if (verified) {
                    val fresh = repo.getDocument(doc.id) ?: doc
                    SwSheet.builder(this@ShellActivity)
                        .setTitle(R.string.make_searchable_title)
                        .setMessage(getString(R.string.ux130_l1_text_layer_added, fresh.name))
                        .setPrimary(R.string.open) { openDocument(fresh.id) }
                        .setSecondary(R.string.share_pdf) { shareRow(fresh) }
                        .build().show()
                } else {
                    // No success text, no Open/Share, no Undo: the document is untouched.
                    SwSheet.builder(this@ShellActivity)
                        .setTitle(R.string.make_searchable_title)
                        .setMessage(getString(R.string.ux130_l1_text_layer_failed))
                        .setPrimary(R.string.ux130_l1_purchase_close)
                        .build().show()
                }
            }
        }
        sheet.setSecondary(R.string.sheet_cancel)
        sheet.show()
    }

    /**
     * B5: a Library row's ⋮ opens the SAME named Actions sheet as the document
     * screen's dock ([DocumentActionsSheet.show]), bound to this row's id - one
     * vocabulary, one set of handlers, nothing picked twice.
     *
     * This screen never closes, so:
     *  - [DocumentActionsSheet.show]'s onChange reloads the list: a rename, a category
     *    move, a copy or a protection change is visible without leaving Library;
     *  - its onGone means the document left the list (moved to Trash, or a legacy Vault
     *    item permanently deleted). The sheet posts that move exactly as it does for the
     *    viewer; consume it here and draw the same anchored Undo notice every other
     *    in-shell move uses (T40), never the sheet's unanchored bar.
     *
     * T27-FIX-TRASH stays: the row hands over to [DocumentActionsSheet]'s confirmation,
     * which moves to Trash and stays restorable - it never purges.
     */
    private fun showDocActions(doc: ScanDocument) {
        DocumentActionsSheet.show(
            activity = this,
            docId = doc.id,
            onChange = { library.reload() },
            onGone = {
                // Called after the sheet's own onChange, so the list is already reloading.
                TrashActions.takePending()?.let { showMovedNotice(it) }
            }
        )
    }

    /**
     * The "Moved to Trash" snackbar with Undo, anchored above the nav bar.
     *
     * T40-FIX-SNACK-ANCHOR: this is the one place an in-shell move shows its notice - a
     * Library row's Actions sheet, a library multi-selection
     * ([LibraryScreen.Callbacks.onMovedToTrash]) and a move made on the document screen
     * (the pending notice on resume) all land here, so the bar can never be drawn under
     * the nav again.
     */
    private fun showMovedNotice(moved: TrashActions.Moved) {
        val root = findViewById<View>(android.R.id.content) ?: return
        TrashActions.showMoved(this, root, navHost, moved) { library.reload() }
    }

    private fun showDiscardNotice() {
        if (!::library.isInitialized || !::navHost.isInitialized) return
        val root = findViewById<View>(android.R.id.content) ?: return
        val host = discardActions ?: com.scanwhirl.app.ui.DiscardActions(
            this, root, navHost
        ) { library.reload() }.also { discardActions = it }
        host.showPending()
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
                // F5/W2: the first Back from this viewer asks whether to keep the import.
                startActivity(DocumentViewerActivity.intent(this@ShellActivity, doc.id, freshImport = true))
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
                        .setMessage(importFailureMessage(e))
                        .setPrimary(R.string.sheet_got_it)
                        .build()
                        .show()
                }
            }
        }
    }

    /**
     * H2-5: the live Import route names the reason in the person's language, from the
     * reviewed import_failed_* strings. The library's English sentence (or any other
     * exception's JVM text) is never shown; only the category is logged.
     */
    internal fun importFailureMessage(e: Throwable): String {
        val failure = e as? PdfImport.ImportException
        android.util.Log.w("ScanWhirlImport", "import failed: ${failure?.reason ?: e.javaClass.simpleName}")
        return when (failure?.reason) {
            PdfImport.Reason.PASSWORD -> getString(R.string.import_failed_password)
            PdfImport.Reason.DAMAGED -> if (failure.page > 0) {
                getString(R.string.import_failed_page, failure.page, failure.pages)
            } else {
                getString(R.string.import_failed_damaged)
            }
            PdfImport.Reason.EMPTY -> getString(R.string.import_failed_empty)
            PdfImport.Reason.TOO_LARGE ->
                getString(
                    R.string.ux130_l1_import_too_large,
                    com.scanwhirl.app.util.SizeUnits.format(PdfImport.MAX_BYTES)
                )
            PdfImport.Reason.LOW_MEMORY -> getString(R.string.import_failed_low_memory)
            PdfImport.Reason.TOO_MANY_PAGES ->
                getString(R.string.import_failed_too_many, failure.pages, PdfImport.MAX_PAGES)
            PdfImport.Reason.NO_SPACE -> getString(R.string.import_failed_no_space)
            PdfImport.Reason.NO_ACCESS -> getString(R.string.import_failed_no_access)
            PdfImport.Reason.READ_FAILED -> getString(R.string.import_failed_read)
            PdfImport.Reason.SAVE_FAILED, null -> getString(R.string.import_failed_save)
        }
    }

    // ---- ToolHost ---------------------------------------------------------

    override fun open(tool: SwTool) {
        when (tool.id) {
            "scan" -> scanLauncher.launchScanner()
            "photos_pdf" -> showPhotoImportSheet()
            // W7: the tool row named Import PDF opens the PDF picker itself, exactly like the
            // Create sheet's row. No intermediate chooser.
            "import_pdf" -> {
                pickerReturnFocus = true
                pickImportPdf.launch(arrayOf("application/pdf"))
            }
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
        // P2pro privacy: a password-protected or Vault document must never reach the OCR/reader
        // tools through this chooser. The shared DocumentPicker callers apply the same filter;
        // without it, read_text/to_text could display a protected document's pages and text.
        val docs = runCatching { repo.listDocuments() }
            .getOrDefault(emptyList())
            .filter { !it.isLocked && !it.isVaulted }
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
                hint = getString(R.string.ux130_l1_search_hint)
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
        // L1: the shell used binary KB/MB here while every other readout uses the shared
        // decimal formatter. One helper, so a size cannot disagree between two screens.
        return com.scanwhirl.app.util.SizeUnits.format(bytes)
    }

    /** SH1 #4: visible dates follow the app's language, not the device locale. */
    internal fun appLocale(): java.util.Locale {
        val tag = com.scanwhirl.app.util.AppLanguage.currentTag(this)
        return if (tag.isEmpty()) java.util.Locale.getDefault()
        else java.util.Locale.forLanguageTag(tag)
    }

    private fun dayLabel(doc: ScanDocument): String =
        java.text.SimpleDateFormat("d MMM", appLocale()).format(java.util.Date(doc.createdAt))

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

        /** SH1 review P1: test seam invoked on the IO thread just before a wallpaper decode. */
        @JvmField internal var wallpaperProbe: (() -> Unit)? = null

        /** L1-06: test seam for a repository searchable result (success/failure injection). */
        @JvmField internal var searchableProbe: ((String) -> Boolean)? = null

        /** SH1 review P2: ~65% ink over a photo keeps the shell's text readable. */
        internal const val SCRIM_ALPHA = 166

        /** SH1 #2: saved selection of the bottom bar across recreation. */
        private const val KEY_TAB = "sh1_selected_tab"

        /** L1-02: the rest of the library's place across recreation. */
        private const val KEY_QUERY = "uxl1_library_query"
        private const val KEY_FOLDER = "uxl1_library_folder"
        private const val KEY_CATEGORY = "uxl1_library_category"
        private const val KEY_SORT = "uxl1_library_sort"
        private const val KEY_SELECTION = "uxl1_library_selection"
        private const val KEY_SELECTION_MODE = "uxl1_library_selection_mode"
        private const val KEY_SCROLL = "uxl1_library_scroll"

        /** Tag of the non-interactive fade painted just above the dock. */
        const val DOCK_FADE = "swDockFade"

        fun intent(context: android.content.Context, docId: String? = null): Intent =
            Intent(context, ShellActivity::class.java).apply {
                if (!docId.isNullOrBlank()) putExtra(EXTRA_SHOW_DOC_ID, docId)
            }
    }
}
