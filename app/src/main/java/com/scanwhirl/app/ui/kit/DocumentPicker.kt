package com.scanwhirl.app.ui.kit

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.ui.tools.DevicePdfGate
import com.scanwhirl.app.util.CategoryLabels
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-W6 — the one reusable "choose a document" picker.
 *
 * Every tool that needs an existing document (Protect/Unlock, Save as PDF/A, Compress, Merge,
 * Insert pages, Stamp/Sign, Read text) shows this instead of a plain name-only list. A row is a
 * real first-page thumbnail, the name (wrapping), "N pages · size · date" and the category chip.
 * A protected or Vault document never decodes its page and never shows page/size/date/category:
 * it shows a lock and the spoken reason instead (WINNER_SPEC "Locked / Vault": no preview or
 * metadata leak). Selection is by the document **id** in the row tag, so two files with the same
 * name still open the exact one that was tapped.
 *
 * The sheet is dismissed before [onPick] runs, and a pick suppresses [onDismiss]; Back, Close,
 * an outside tap or a rotation therefore leave nothing selected. An empty library (or a search
 * with no match) is an explicit state, never a silent no-op.
 */
class DocumentPicker private constructor(
    private val activity: AppCompatActivity,
    private val dialog: BottomSheetDialog,
    private val docs: List<ScanDocument>,
    @StringRes private val noEligibleTitle: Int?,
    private val onPick: (ScanDocument) -> Unit,
    private val onDismiss: (() -> Unit)?
) {

    /** The sheet's content root (tests and TalkBack pane title). */
    val view: View

    /** The search field; filtering is live and case-insensitive on the document name. */
    val searchView: EditText

    private val list: LinearLayout
    private val body: FrameLayout
    private val emptyHost: LinearLayout
    private val emptyTitle: TextView
    private val emptyBody: TextView
    private val emptyScan: TextView
    private val emptyImport: TextView
    private val clearSearch: TextView
    private val dismissActions = DismissActions(dialog)

    /** The document id chosen last; null until a row is tapped and after a cancel. */
    var selectedDocumentId: String? = null
        private set

    /** Rows currently shown (search-filtered), in order. */
    val rows: List<LinearLayout>
        get() = (0 until list.childCount).mapNotNull { list.getChildAt(it) as? LinearLayout }

    private var query = ""
    private var picked = false
    private var hasScanAction = false
    private var hasImportAction = false

    /**
     * OWNER_QA item 1: the shared "From this phone" route. SAF launchers must be
     * registered before the host Activity is STARTED, so a bottom sheet built on
     * tap cannot use the Activity as its LifecycleOwner. A tiny private owner keeps
     * just this registration: INITIALIZED at register time (the registry requires
     * that), then STARTED immediately so the result callback is delivered. It is
     * destroyed with the sheet, which unregisters the launcher.
     */
    private val deviceResultOwner = PickerResultOwner()
    private val deviceLauncher: ActivityResultLauncher<Array<String>> =
        activity.activityResultRegistry.register(
            "sw_doc_picker_device_${System.identityHashCode(deviceResultOwner)}",
            deviceResultOwner,
            ActivityResultContracts.OpenDocument()
        ) { uri -> pendingDeviceUri?.also { pendingDeviceUri = null }?.invoke(uri) }

    /** Set only while the SAF picker is open; null on cancel. */
    private var pendingDeviceUri: ((Uri?) -> Unit)? = null

    private class PickerResultOwner : LifecycleOwner {
        private val ownerLifecycle = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = ownerLifecycle
        fun start() {
            if (ownerLifecycle.currentState == Lifecycle.State.INITIALIZED) {
                ownerLifecycle.currentState = Lifecycle.State.RESUMED
            }
        }

        fun destroy() {
            if (ownerLifecycle.currentState != Lifecycle.State.DESTROYED) {
                ownerLifecycle.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    init {
        val context = activity
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()

        val root = LinearLayout(context).apply {
            id = R.id.docPickerSheetRoot
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (10 * density).toInt(), padH, (18 * density).toInt())
            background = SwStyle.rounded(
                context,
                0xFF161D21.toInt(),
                radiusDp = 28f,
                stroke = SwStyle.color(context, R.color.sw_cyan_hair_strong)
            )
        }

        root.addView(View(context).apply {
            id = R.id.swSheetGrab
            background = SwStyle.rounded(context, SwStyle.color(context, R.color.sw_sheet_grab), 999f)
            layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (5 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (14 * density).toInt()
            }
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            SwStyle.text(context, 20f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
                id = R.id.docPickerTitle
                typeface = SwType.bold(context)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            SwStyle.iconButton(context, R.drawable.ic_sym_close, SwStyle.color(context, R.color.text_hologram_dim)).apply {
                id = R.id.docPickerClose
                contentDescription = context.getString(R.string.sheet_close)
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
                setOnClickListener { dismissActions.dismiss() }
            }
        )
        root.addView(header)

        searchView = EditText(context).apply {
            id = R.id.docPickerSearch
            hint = context.getString(R.string.w6_picker_search_hint)
            setHintTextColor(SwStyle.color(context, R.color.text_hologram_dim))
            setTextColor(SwStyle.color(context, R.color.text_hologram_hi))
            typeface = SwType.regular(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = SwStyle.rounded(
                context,
                SwStyle.color(context, R.color.glass_surface),
                14f,
                stroke = SwStyle.color(context, R.color.sw_field_border)
            )
            val ph = (14 * density).toInt()
            setPadding(ph, (12 * density).toInt(), ph, (12 * density).toInt())
            minHeight = (48 * density).toInt()
            includeFontPadding = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
        }
        root.addView(
            searchView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (12 * density).toInt() }
        )

        // A short, fixed window keeps the sheet on screen at 200% and lets a long library scroll.
        val bodyH = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        body = FrameLayout(context)
        root.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bodyH)
                .apply { topMargin = (10 * density).toInt() }
        )

        list = LinearLayout(context).apply {
            id = R.id.docPickerList
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            id = R.id.docPickerScroll
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(list)
        }
        body.addView(
            scroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        emptyHost = LinearLayout(context).apply {
            id = R.id.docPickerEmpty
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isVisible = false
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
        }
        body.addView(
            emptyHost,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        emptyTitle = SwStyle.text(context, 17f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.docPickerEmptyTitle
            gravity = Gravity.CENTER
        }
        emptyHost.addView(emptyTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        emptyBody = SwStyle.text(context, 13.5f, SwStyle.color(context, R.color.text_hologram_mid)).apply {
            id = R.id.docPickerEmptyBody
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        emptyHost.addView(emptyBody, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() })
        emptyScan = emptyAction(context, primary = true).apply {
            id = R.id.docPickerEmptyScan
            text = context.getString(R.string.w6_picker_empty_scan)
        }
        emptyHost.addView(emptyScan, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() })
        emptyImport = emptyAction(context, primary = false).apply {
            id = R.id.docPickerEmptyImport
            text = context.getString(R.string.w6_picker_empty_import)
        }
        emptyHost.addView(emptyImport, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })
        clearSearch = emptyAction(context, primary = false).apply {
            id = R.id.docPickerClear
            text = context.getString(R.string.w6_picker_clear_search)
            isVisible = false
            setOnClickListener {
                searchView.setText("")
                searchView.requestFocus()
            }
        }
        emptyHost.addView(clearSearch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })

        view = root
        dialog.setContentView(root)
        dialog.window?.setDimAmount(0.66f)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            dialog.behavior.isFitToContents = false
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        deviceResultOwner.start()
        dismissActions.add { if (!picked) onDismiss?.invoke() }
        dismissActions.add { deviceResultOwner.destroy() }
        context.attachDialogLifecycle(dialog, dismissActions)
        render()
    }

    private fun emptyAction(context: AppCompatActivity, primary: Boolean): TextView =
        SwStyle.text(
            context,
            15f,
            SwStyle.color(context, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi),
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = if (primary) {
                SwStyle.rounded(context, SwStyle.color(context, R.color.cyan_primary), 16f)
            } else {
                SwStyle.rounded(
                    context,
                    SwStyle.color(context, R.color.glass_surface),
                    16f,
                    stroke = SwStyle.color(context, R.color.sw_cyan_hair)
                )
            }
            minimumHeight = SwStyle.dp(context, 48f)
            val padV = SwStyle.dp(context, 10f)
            setPadding(SwStyle.dp(context, 12f), padV, SwStyle.dp(context, 12f), padV)
            isClickable = true
            isFocusable = true
        }

    /** Rebuilds the visible rows for the current query. Keeps the selection by id. */
    private fun render() {
        val q = query.trim()
        val filtered = if (q.isEmpty()) docs else docs.filter { it.name.contains(q, ignoreCase = true) }
        list.removeAllViews()
        for (doc in filtered) list.addView(row(doc))

        val noMatch = docs.isNotEmpty() && filtered.isEmpty()
        val showEmpty = filtered.isEmpty()
        val noEligible = docs.isEmpty() && noEligibleTitle != null
        val emptyLibrary = docs.isEmpty() && noEligibleTitle == null
        (view.findViewById<View>(R.id.docPickerScroll))?.isVisible = !showEmpty
        emptyHost.isVisible = showEmpty
        if (showEmpty) {
            emptyTitle.text = view.context.getString(
                when {
                    noMatch -> R.string.w6_picker_no_match_title
                    noEligibleTitle != null -> noEligibleTitle
                    else -> R.string.w6_picker_empty_title
                }
            )
            emptyBody.text = when {
                noMatch -> view.context.getString(R.string.w6_picker_no_match_body)
                noEligible -> view.context.getString(R.string.kt1_no_eligible_body)
                emptyLibrary -> view.context.getString(R.string.w6_picker_empty_body)
                else -> null
            }
            emptyBody.isVisible = noMatch || noEligible || emptyLibrary
            // A search with no match offers only Clear. An empty library AND a non-empty library
            // with no eligible documents both offer the host's real Scan/Import routes: the
            // latter was a dead end (an honest title with no way forward).
            clearSearch.isVisible = noMatch
            emptyScan.isVisible = (emptyLibrary || noEligible) && hasScanAction
            emptyImport.isVisible = (emptyLibrary || noEligible) && hasImportAction
        }
    }

    private fun row(doc: ScanDocument): LinearLayout {
        val context = activity
        val masked = doc.isLocked || doc.isVaulted
        val selectedHere = doc.id == selectedDocumentId
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = R.id.docPickerRow
            tag = doc.id
            isClickable = true
            isFocusable = true
            isSelected = selectedHere
            minimumHeight = SwStyle.dp(context, 76f)
            val pad = SwStyle.dp(context, 10f)
            setPadding(pad, pad, pad, pad)
            background = if (selectedHere) {
                SwStyle.gradient(
                    context,
                    SwStyle.color(context, R.color.glass_surface_raised),
                    SwStyle.color(context, R.color.glass_surface),
                    16f,
                    SwStyle.color(context, R.color.cyan_primary)
                )
            } else {
                SwStyle.card(context, 16f)
            }
            setOnClickListener {
                if (picked) return@setOnClickListener
                picked = true
                selectedDocumentId = doc.id
                paintSelection()
                dismissActions.dismiss()
                onPick(doc)
            }
            SwMotion.press(this)
        }

        // The tick is the row's first child: selection is never colour alone.
        row.addView(ImageView(context).apply {
            id = R.id.docPickerCheck
            setImageResource(R.drawable.ic_sym_check)
            imageTintList = android.content.res.ColorStateList.valueOf(
                SwStyle.color(context, R.color.cyan_primary)
            )
            visibility = if (selectedHere) View.VISIBLE else View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(SwStyle.dp(context, 22f), SwStyle.dp(context, 22f))
            .apply { marginEnd = SwStyle.dp(context, 10f) })

        val thumbFrame = FrameLayout(context).apply {
            // Council §4 / UI_100 §G1.6: every picker thumb sits on the shared dark
            // canvas with a 3dp inset and a 1dp page edge — never a near-white tile.
            // A protected/Vault document keeps the same canvas; its lock glyph below
            // is the only thing that changes.
            background = SwStyle.rounded(
                context, SwStyle.color(context, R.color.sw_cc_canvas), 4f,
                stroke = SwStyle.color(context, R.color.sw_cc_page_edge)
            )
            setForeground(
                SwStyle.rounded(
                    context, Color.TRANSPARENT, 4f,
                    stroke = SwStyle.color(context, R.color.sw_cc_page_edge)
                )
            )
            setPadding(
                SwStyle.dp(context, 3f), SwStyle.dp(context, 3f),
                SwStyle.dp(context, 3f), SwStyle.dp(context, 3f)
            )
            clipToOutline = true
        }
        val thumb = ImageView(context).apply {
            id = R.id.docPickerThumb
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbFrame.addView(thumb, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (masked) {
            // No page image for a protected/Vault file: a lock and its spoken reason only.
            val reason = context.getString(
                if (doc.isVaulted) R.string.w6_picker_vault_reason else R.string.w6_picker_locked_reason
            )
            thumbFrame.addView(ImageView(context).apply {
                id = R.id.docPickerLock
                setImageResource(R.drawable.ic_sym_lock)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    SwStyle.color(context, R.color.gold_pro_primary)
                )
                contentDescription = reason
                scaleType = ImageView.ScaleType.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                val p = SwStyle.dp(context, 14f)
                setMargins(p, p, p, p)
            })
        } else {
            val firstPage = doc.pageFiles.firstOrNull()
            if (firstPage != null && firstPage.exists()) {
                PageThumbLoader.load(
                    firstPage,
                    (56f * context.resources.displayMetrics.density * 2).toInt(),
                    ScanFilter.ORIGINAL,
                    thumb,
                    fitExactly = true,
                    skipStale = true
                )
            } else {
                thumb.setImageDrawable(null)
            }
        }
        row.addView(thumbFrame, LinearLayout.LayoutParams(
            SwStyle.dp(context, 56f), SwStyle.dp(context, 79f)
        ).apply { marginEnd = SwStyle.dp(context, 12f) })

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(SwStyle.text(context, 15.5f, SwStyle.color(context, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.docPickerName
            text = doc.name
            // Names wrap instead of disappearing: four lines, then an ellipsis for a pathological one.
            // Whole-word breaks only: never split "annot_test" mid-identifier.
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            //noinspection WrongConstant
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        })
        if (!masked) {
            val size = doc.cachedSize.takeIf { it > 0 } ?: doc.pdfFile.length()
            val whenAt = doc.createdAt.takeIf { it > 0 } ?: doc.cachedModified
            val date = if (whenAt > 0) {
                DateUtils.formatDateTime(
                    context,
                    whenAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                )
            } else {
                ""
            }
            info.addView(
                SwStyle.text(context, 12.5f, SwStyle.color(context, R.color.text_hologram_dim)).apply {
                    id = R.id.docPickerMeta
                    text = context.resources.getQuantityString(
                        R.plurals.w6_picker_doc_meta, doc.pageCount, doc.pageCount,
                        StatusLine.formatBytes(size), date
                    )
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(context, 3f) }
            )
            val category = CategoryLabels.display(context, doc.category)
            if (category.isNotBlank()) {
                info.addView(
                    SwStyle.text(context, 11f, SwStyle.color(context, R.color.text_hologram_mid), bold = true).apply {
                        id = R.id.docPickerCategory
                        text = category
                        maxLines = 1
                        letterSpacing = 0f
                        setPadding(
                            SwStyle.dp(context, 8f), SwStyle.dp(context, 2f),
                            SwStyle.dp(context, 8f), SwStyle.dp(context, 2f)
                        )
                        background = pill(context)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = SwStyle.dp(context, 5f) }
                )
            }
        }
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun pill(context: AppCompatActivity): GradientDrawable = SwStyle.pill(
        context,
        SwStyle.color(context, R.color.text_hologram_mid),
        SwStyle.color(context, R.color.glass_surface_raised),
        SwStyle.color(context, R.color.sw_cyan_hair)
    )

    private fun paintSelection() {
        for (row in rows) {
            val on = row.tag == selectedDocumentId
            row.isSelected = on
            row.findViewById<View>(R.id.docPickerCheck)?.visibility =
                if (on) View.VISIBLE else View.INVISIBLE
            row.background = if (on) {
                SwStyle.gradient(
                    activity,
                    SwStyle.color(activity, R.color.glass_surface_raised),
                    SwStyle.color(activity, R.color.glass_surface),
                    16f,
                    SwStyle.color(activity, R.color.cyan_primary)
                )
            } else {
                SwStyle.card(activity, 16f)
            }
        }
    }

    // ---- OWNER_QA item 1: uniform "From this phone" (SAF) --------------------

    /**
     * Opens the SAF picker, then imports the chosen file through the SAME path the
     * app's Import PDF door uses (ScanRepository.importPdfAsScanPlanned + the shared
     * DevicePdfGate). The sheet stays open until the imported library document is
     * ready; that document is then handed to the caller's [onPick] exactly as if the
     * person had tapped it. Cancelling SAF leaves the picker as it was.
     */
    private fun onDeviceFileChosen(uri: Uri?) {
        pendingDeviceUri = null
        if (uri == null) return
        val repo = ScanRepository(activity)
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { repo.displayNameOf(uri) }.getOrNull()
            }?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.trim()?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.ck_picker_imported_file)
            // Honest copy: say plainly that a ScanWhirl copy is made and the phone's
            // original is left untouched before any byte is written.
            SwDialog.card(activity)
                .setTitle(R.string.ck_picker_import_title)
                .setMessage(activity.getString(R.string.ck_picker_import_msg, name))
                .setPositiveButton(R.string.continue_label) { _, _ -> runDeviceImport(uri, name) }
                .setNegativeButton(android.R.string.cancel, null)
                .showSw(isSheet = false)
        }
    }

    private fun runDeviceImport(uri: Uri, name: String) {
        val repo = ScanRepository(activity)
        var progress: SwSheet? = null
        var cancelled = false
        activity.lifecycleScope.launch {
            try {
                val doc = repo.importPdfAsScanPlanned(
                    uri = uri,
                    name = name,
                    onPlan = { plan ->
                        val decision = DevicePdfGate.decide(activity, plan)
                        if (decision != ScanRepository.ImportDecision.Cancel) {
                            progress = withContext(Dispatchers.Main) {
                                DevicePdfGate.progress(activity) { cancelled = true }
                                    .also { it.show() }
                            }
                        }
                        decision
                    },
                    onPage = { done, total ->
                        activity.runOnUiThread {
                            progress?.setMessage(DevicePdfGate.progressText(activity, done, total))
                        }
                    }
                )
                progress?.dismiss()
                if (doc == null || cancelled || picked) return@launch
                picked = true
                dismissActions.dismiss()
                onPick(doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                progress?.dismiss()
                throw e
            } catch (e: Exception) {
                progress?.dismiss()
                SwDialog.card(activity)
                    .setTitle(R.string.ck_picker_import_failed_title)
                    .setMessage(R.string.ck_picker_import_failed_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .showSw(isSheet = false)
            }
        }
    }

    /** A named row outside the document list, e.g. "From a PDF file". */
    class ExtraAction(val label: CharSequence, val onClick: () -> Unit)

    companion object {

        /**
         * Shows the picker over [activity].
         *
         * @param extraAction a fixed last action (e.g. "From a PDF file") that is not a document.
         * @param onEmptyScan what the empty state's Scan action does; null hides it.
         * @param onEmptyImport what the empty state's Import action does; null hides it.
         * @param noEligibleTitle title for a nonempty library with no eligible documents.
         * Pass it only when the library is not empty and [docs] is the empty filtered list.
         * @param onPick runs after the sheet closes, with the exact [ScanDocument] tapped.
         * @param onDismiss runs only when the sheet closes without a pick (Back, Close, outside).
         */
        fun show(
            activity: AppCompatActivity,
            title: CharSequence,
            docs: List<ScanDocument>,
            extraAction: ExtraAction? = null,
            onEmptyScan: (() -> Unit)? = null,
            onEmptyImport: (() -> Unit)? = null,
            @StringRes noEligibleTitle: Int? = null,
            onPick: (ScanDocument) -> Unit,
            onDismiss: (() -> Unit)? = null
        ): DocumentPicker {
            val dialog = BottomSheetDialog(activity)
            val picker = DocumentPicker(activity, dialog, docs, noEligibleTitle, onPick, onDismiss)
            picker.view.findViewById<TextView>(R.id.docPickerTitle)?.text = title
            picker.hasScanAction = onEmptyScan != null
            picker.hasImportAction = onEmptyImport != null
            picker.emptyScan.isVisible = onEmptyScan != null
            picker.emptyImport.isVisible = onEmptyImport != null
            picker.emptyScan.setOnClickListener {
                if (picker.picked) return@setOnClickListener
                picker.picked = true
                picker.dismissActions.dismiss()
                onEmptyScan?.invoke()
            }
            picker.emptyImport.setOnClickListener {
                if (picker.picked) return@setOnClickListener
                picker.picked = true
                picker.dismissActions.dismiss()
                onEmptyImport?.invoke()
            }
            val root = picker.view as LinearLayout
            // OWNER_QA item 1: ONE uniform device route for every caller of the shared
            // picker — no per-tool wiring. It is pinned below the list like the older
            // extra action, so a long library or a 200% font cannot push it off-screen.
            val fromPhone = picker.emptyAction(activity, primary = false).apply {
                id = R.id.docPickerFromPhone
                text = activity.getString(R.string.ck_picker_from_phone)
                contentDescription = text
                setOnClickListener {
                    if (picker.picked) return@setOnClickListener
                    picker.pendingDeviceUri = { uri -> picker.onDeviceFileChosen(uri) }
                    picker.deviceLauncher.launch(arrayOf("application/pdf", "image/*"))
                }
            }
            root.addView(fromPhone, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(activity, 8f) })
            if (extraAction != null) {
                val extra = picker.emptyAction(activity, primary = false).apply {
                    id = R.id.docPickerExtra
                    text = extraAction.label
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (picker.picked) return@setOnClickListener
                        picker.picked = true
                        picker.dismissActions.dismiss()
                        extraAction.onClick()
                    }
                }
                root.addView(extra, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(activity, 8f) })
            }
            // Pin the actions: only the document body shrinks/scrolls, so at 200%
            // font (or with a long title) they can never be pushed off-screen.
            // Keep the dialog's own LayoutParams type (CoordinatorLayout) and change
            // only the height: replacing it with a LinearLayout param crashes the parent.
            root.layoutParams?.let { lp ->
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                root.layoutParams = lp
            }
            picker.body.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = SwStyle.dp(activity, 10f) }
            picker.render()
            androidx.core.view.ViewCompat.setAccessibilityPaneTitle(
                picker.view, title.toString()
            )
            dialog.show()
            return picker
        }
    }
}
