package com.scanwhirl.app.ui.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OfficeFileCounts
import com.scanwhirl.app.util.SavedSnackbar
import com.scanwhirl.app.util.ShareUtils
import com.scanwhirl.app.util.StatusLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v2 UI: 03_tools_hub — the result of every L4 tool.
 *
 * One screen for a new document copy and for an exported file: Open, "Also save in Documents"
 * (one tap into Documents/ScanWhirl), Share, Save as… (the system Save-as picker), Show in library
 * and Undo. Undo removes only the copy this run created; the source document is never touched.
 * Compress also says so when the produced size is still above the size the person asked for.
 * Built with the kit on the ink background.
 *
 * F1/W3: the ID card result is already saved the moment the layout is chosen, so that flow shows
 * Keep (done) and Discard instead of the save and undo actions, and Back asks "Discard this ID
 * card?" first.
 */
class ToolResultActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var rootView: View
    private lateinit var toolName: String
    private var docIds: MutableList<String> = mutableListOf()
    private var files: MutableList<File> = mutableListOf()
    private var mime = "application/pdf"
    private var undone = false
    private lateinit var summary: TextView
    private lateinit var nameView: TextView
    private lateinit var errorView: TextView
    private lateinit var previewHost: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var saveAs: ActivityResultLauncher<String>
    private var toolId = ""
    private var note: String? = null

    /** T05: the size Compress was asked for, so the result can say it was not reached. */
    private var targetBytes: Long? = null

    /** F1: the ID card flow's own result actions (Keep / Discard). */
    private val idCardFlow: Boolean get() = toolId == "id_card"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        toolId = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        toolName = ToolRegistry.byId(toolId)?.let { getString(it.title) }.orEmpty()
        docIds = intent.getStringArrayListExtra(EXTRA_DOC_IDS)?.toMutableList() ?: mutableListOf()
        files = intent.getStringArrayListExtra(EXTRA_FILES)?.map { File(it) }?.toMutableList() ?: mutableListOf()
        mime = intent.getStringExtra(EXTRA_MIME) ?: defaultMime()
        note = intent.getStringExtra(EXTRA_NOTE)
        targetBytes = intent.getLongExtra(EXTRA_TARGET, 0L).takeIf { it > 0 }
        saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            uri ?: return@registerForActivityResult
            saveTo(uri)
        }
        if (idCardFlow) {
            // F1/W3: leaving an already-saved card asks first; Discard deletes exactly this card.
            onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmDiscardIdCard()
            })
        }
        rootView = buildRoot()
        setContentView(rootView)
        render()
        // v2 UI: 74_motion — the one success moment call for every tool result.
        com.scanwhirl.app.ui.kit.SwMotion.success(this)
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 10f),
                SwStyle.dp(this@ToolResultActivity, 18f), SwStyle.dp(this@ToolResultActivity, 24f)
            )
            applySystemBarsPadding()
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(SwStyle.iconButton(
            this, R.drawable.ic_sym_arrow_back, SwStyle.color(this, R.color.text_hologram_hi)
        ).apply {
            contentDescription = getString(R.string.tools_setup_back)
            layoutParams = LinearLayout.LayoutParams(
                SwStyle.dp(this@ToolResultActivity, 48f), SwStyle.dp(this@ToolResultActivity, 48f)
            )
            setOnClickListener { handleBack() }
        })
        bar.addView(
            SwStyle.text(this, 22f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.tools_result_done)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolResultActivity, 10f)
            }
        )
        root.addView(bar)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        previewHost = FrameLayout(this).apply {
            id = R.id.toolResultPreviewHost
            // fixidcrop: a white ID card page on a white box had no visible paper edge. The card
            // page sits on the ink backdrop, inset by a little padding and framed by a thin line,
            // so the sheet is obvious. Every other tool result keeps its previous preview.
            if (idCardFlow) {
                val pad = SwStyle.dp(this@ToolResultActivity, ID_PREVIEW_PADDING_DP)
                setPadding(pad, pad, pad, pad)
                background = SwStyle.rounded(
                    this@ToolResultActivity,
                    SwStyle.color(this@ToolResultActivity, R.color.void_abyss),
                    radiusDp = 16f,
                    stroke = ID_PREVIEW_LINE
                )
            } else {
                background = SwStyle.rounded(
                    this@ToolResultActivity,
                    SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                    radiusDp = 16f,
                    stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
                )
            }
            clipToOutline = true
        }
        preview = ImageView(this).apply {
            id = R.id.toolResultPreview
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!idCardFlow) setBackgroundColor(Color.WHITE)
            contentDescription = getString(R.string.tools_result_preview)
        }
        previewHost.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        column.addView(previewHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolResultActivity, 200f)
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 14f) })

        nameView = SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.toolResultName
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(nameView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) })

        summary = SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
            id = R.id.toolResultSummary
            setLineSpacing(0f, 1.25f)
        }
        column.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })

        errorView = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger)).apply {
            id = R.id.toolResultError
            isVisible = false
        }
        column.addView(errorView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 8f) })

        if (idCardFlow) {
            // F1/W3: the card is already saved, so there is nothing to Save and nothing to Undo.
            // Two clear actions: Keep (done) and Discard (delete this one card).
            column.addView(
                button(getString(R.string.fixflow_keep), tag = TAG_KEEP, primary = true) { finish() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
            )
            column.addView(
                button(getString(R.string.discard), tag = TAG_DISCARD) { discardIdCard() },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
            )
            return root
        }

        column.addView(
            button(getString(R.string.tools_result_open), R.id.toolResultOpen, primary = true) { open() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
        )
        // T05-RESULT-SLICE: one tap keeps a copy in Documents for the person's own files app and
        // USB, for a document result and for an exported file alike. The system Save-as picker
        // stays next to Share for choosing another place.
        column.addView(
            button(getString(R.string.tools_result_also_save), R.id.toolResultAlsoSave) { alsoSave() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
        )
        column.addView(
            rowOf(
                button(getString(R.string.tools_result_share), R.id.toolResultShare) { share() },
                button(getString(R.string.tools_result_save), R.id.toolResultSave) { save() }
            )
        )
        val library = button(getString(R.string.tools_result_library), R.id.toolResultLibrary) {
            showInLibrary()
        }
        // H7: an exported file (Word, Excel, PowerPoint, images, ZIP) is not a library document
        // and the Library can never list it, so "Show in library" went to a screen where the
        // result was missing. The export lives in the app's own output folder until the user
        // Saves it somewhere, so the smaller honest change is to drop the button, not to point
        // it at another empty folder.
        if (isExportOnlyResult()) library.visibility = View.GONE
        column.addView(
            rowOf(library, button(getString(R.string.tools_result_undo), R.id.toolResultUndo) { undo() })
        )
        return root
    }

    /** H7: a result made of exported files only (no library document behind it). */
    private fun isExportOnlyResult(): Boolean = docIds.isEmpty() && files.isNotEmpty()

    private fun rowOf(left: TextView, right: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = SwStyle.dp(this@ToolResultActivity, 8f)
            })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SwStyle.dp(this@ToolResultActivity, 8f)
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
        }

    private fun button(
        text: String,
        id: Int = View.NO_ID,
        tag: String? = null,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView =
        SwStyle.text(this, 15f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
        ), bold = true).apply {
            if (id != View.NO_ID) this.id = id
            if (tag != null) this.tag = tag
            this.text = text
            gravity = Gravity.CENTER
            maxLines = 2
            minimumHeight = SwStyle.dp(this@ToolResultActivity, 50f)
            background = if (primary) {
                SwStyle.rounded(this@ToolResultActivity, SwStyle.color(this@ToolResultActivity, R.color.cyan_primary), radiusDp = 16f)
            } else {
                SwStyle.rounded(
                    this@ToolResultActivity,
                    SwStyle.color(this@ToolResultActivity, R.color.glass_surface),
                    radiusDp = 16f,
                    stroke = SwStyle.color(this@ToolResultActivity, R.color.sw_cyan_hair)
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    // ---- content ----------------------------------------------------------

    private fun render() {
        val showSizes = toolId == "compress" || toolId == "resize"
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        if (docs.isNotEmpty()) {
            val pages = docs.sumOf { it.pageCount }
            val bytes = docs.sumOf { if (it.pdfFile.exists()) it.pdfFile.length() else 0L }
            nameView.text = if (docs.size == 1) {
                docs.first().name
            } else {
                getString(R.string.tools_result_name_more, docs.first().name, docs.size - 1)
            }
            var line = resources.getQuantityString(R.plurals.tools_result_pages, pages, pages)
            if (docs.size > 1) {
                line += " · " + resources.getQuantityString(
                    R.plurals.tools_result_documents, docs.size, docs.size
                )
            }
            if (showSizes) {
                line = getString(R.string.tools_result_with_size, line, StatusLine.formatBytes(bytes))
            }
            applySummary(line, bytes)
            previewPreview(docs.first().pageFiles.firstOrNull())
        } else if (files.isNotEmpty()) {
            val bytes = files.sumOf { it.length() }
            nameView.text = if (files.size == 1) {
                files.first().name
            } else {
                getString(R.string.tools_result_name_more, files.first().name, files.size - 1)
            }
            var line = resources.getQuantityString(R.plurals.tools_result_files_count, files.size, files.size)
            if (showSizes) {
                line = getString(R.string.tools_result_with_size, line, StatusLine.formatBytes(bytes))
            }
            applySummary(line, bytes)
            val first = files.first()
            if (isTypedFile(first)) {
                showFileCard(first)
            } else {
                preview.isVisible = true
                val image = files.firstOrNull { it.name.endsWith(".jpg") || it.name.endsWith(".png") }
                previewPreview(image)
                if (image == null) preview.setImageResource(iconFor(first))
            }
        } else {
            nameView.text = ""
            summary.text = getString(R.string.tools_result_nothing)
        }
    }

    private fun applySummary(line: String, producedBytes: Long? = null) {
        summary.text = buildString {
            append(line)
            if (!note.isNullOrBlank()) append("\n").append(note)
            targetMiss(producedBytes)?.let { append("\n").append(it) }
        }
    }

    /**
     * T05-RESULT-SLICE: Compress only, and only when the produced file is still bigger than the
     * size the person asked for. "Met" says nothing, and no other tool ever has a target.
     */
    private fun targetMiss(producedBytes: Long?): String? {
        if (toolId != "compress") return null
        val target = targetBytes ?: return null
        if (producedBytes == null || producedBytes <= target) return null
        return getString(R.string.compress_target_missed, StatusLine.formatBytes(target))
    }

    /**
     * True for the results that have no page to render: the generated Office packages, a ZIP,
     * and a PDF the person produced (M6: a lone PDF result used to paint an empty white box;
     * it now gets the same type card as the other file results).
     */
    private fun isTypedFile(file: File): Boolean =
        file.name.endsWith(".docx") || file.name.endsWith(".xlsx") ||
            file.name.endsWith(".pptx") || file.name.endsWith(".zip") ||
            file.name.endsWith(".pdf", ignoreCase = true)

    /**
     * The file-type card (v2 UI run4a): the Office/ZIP/PDF results are real files with no page
     * image, so the preview area shows a big type badge, the file name, its size and
     * page/sheet/slide count instead of an empty white rectangle.
     */
    private fun showFileCard(file: File) {
        preview.isVisible = false
        previewHost.removeViews(1, (previewHost.childCount - 1).coerceAtLeast(0))
        val extension = file.extension.lowercase()
        val accent = when (extension) {
            "docx" -> R.color.file_docx
            "xlsx" -> R.color.file_xlsx
            "pptx" -> R.color.file_pptx
            "pdf" -> R.color.file_pdf
            else -> R.color.file_zip
        }
        val label = when (extension) {
            "docx" -> R.string.tools_result_type_docx
            "xlsx" -> R.string.tools_result_type_xlsx
            "pptx" -> R.string.tools_result_type_pptx
            "pdf" -> R.string.tools_result_type_pdf
            else -> R.string.tools_result_type_zip
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = SwStyle.dp(this@ToolResultActivity, 18f)
            setPadding(pad, pad, pad, pad)
        }
        val badge = SwStyle.text(
            this, 22f, SwStyle.color(this, R.color.sw_disc_ink), bold = true, letterSpacing = 0.08f
        ).apply {
            id = R.id.toolResultTypeBadge
            text = getString(label)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@ToolResultActivity,
                SwStyle.color(this@ToolResultActivity, accent),
                radiusDp = 12f
            )
            val h = SwStyle.dp(this@ToolResultActivity, 18f)
            val v = SwStyle.dp(this@ToolResultActivity, 7f)
            setPadding(h, v, h, v)
        }
        card.addView(badge)
        card.addView(SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.toolResultFileName
            text = file.name
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 12f) })
        val meta = SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
            id = R.id.toolResultFileMeta
            text = StatusLine.formatBytes(file.length())
            gravity = Gravity.CENTER
        }
        card.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 6f) })
        previewHost.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { OfficeFileCounts.count(file) }
            if (count != null && !isFinishing) {
                meta.text = getString(
                    R.string.tools_result_with_size, StatusLine.formatBytes(file.length()), countLabel(extension, count)
                )
            }
        }
    }

    private fun countLabel(extension: String, count: Int): String = when (extension) {
        "docx" -> resources.getQuantityString(R.plurals.tools_result_pages, count, count)
        "xlsx" -> resources.getQuantityString(R.plurals.tools_result_sheets, count, count)
        "pptx" -> resources.getQuantityString(R.plurals.tools_result_slides, count, count)
        else -> resources.getQuantityString(R.plurals.tools_result_files_count, count, count)
    }

    private fun previewPreview(file: File?) {
        if (file == null) {
            preview.setImageResource(R.drawable.ic_sym_description)
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { ImageTools.decodeSampledFile(file, 900) }.getOrNull() }
            if (bitmap != null) preview.setImageBitmap(bitmap) else preview.setImageResource(iconFor(file))
        }
    }

    private fun iconFor(file: File): Int = when {
        file.name.endsWith(".zip") -> R.drawable.ic_sym_archive
        file.name.endsWith(".docx") -> R.drawable.ic_sym_description
        file.name.endsWith(".pptx") -> R.drawable.ic_sym_slideshow
        file.name.endsWith(".xlsx") -> R.drawable.ic_sym_table_chart
        else -> R.drawable.ic_sym_image
    }

    // ---- actions ----------------------------------------------------------

    private fun open() {
        val doc = docIds.firstOrNull()?.let { runCatching { repo.getDocument(it) }.getOrNull() }
        if (doc != null) {
            startActivity(DocumentViewerActivity.intent(this, doc.id))
            return
        }
        val file = files.firstOrNull()
        if (file == null || !file.isFile) return fail(getString(R.string.tools_result_nothing))
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            fail(getString(R.string.tools_result_open_failed))
        }
    }

    private fun share() {
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        if (docs.size == 1) {
            ShareUtils.share(this, docs.first())
            return
        }
        if (docs.size > 1) {
            shareUris(docs.map { it.pdfFile }, "application/pdf")
            return
        }
        if (files.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        if (files.size == 1) {
            ShareUtils.shareFile(this, files.first(), mime)
        } else {
            shareUris(files, mime)
        }
    }

    private fun shareUris(sources: List<File>, type: String) {
        val uris = sources.filter { it.isFile }.map {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        }
        if (uris.isEmpty()) return fail(getString(R.string.tools_result_nothing))
        // fixshare: every Uri must be in ClipData too — the Android 10+ grant model follows it,
        // so a ClipData with only the first file shared one file from a batch.
        val intent = ShareUtils.multiShareIntent(this, uris, type, toolName)
        startActivity(Intent.createChooser(intent, getString(R.string.tools_result_share)))
    }

    private fun save() {
        val name = suggestedName() ?: return fail(getString(R.string.tools_result_nothing))
        saveAs.launch(name)
    }

    /**
     * T05-RESULT-SLICE: "Also save in Documents" — the one-tap copy into Documents/ScanWhirl,
     * with the export transaction's rules (see [DownloadSaver]) and the same confirmation the
     * export result screen uses: Saved / Already saved / "a new copy, the earlier file was kept",
     * and an Undo that removes only the copy this save created.
     */
    private fun alsoSave() {
        val source = sourceForSave() ?: return fail(getString(R.string.tools_result_nothing))
        if (!source.isFile || source.length() == 0L) {
            return fail(getString(R.string.tools_result_nothing))
        }
        val name = suggestedName() ?: source.name
        val type = saveMimeFor(name)
        // A single document result is that document's own PDF, so the copy is recorded against
        // it like any other export of it (delete-with-document then knows the copy).
        val docId = docIds.singleOrNull()
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    DownloadSaver.saveFile(this@ToolResultActivity, source, name, type).also { copy ->
                        if (docId != null) {
                            try {
                                repo.addExport(docId, copy)
                            } catch (_: Exception) {
                                // A failure to record the copy must not turn a finished save into a failure.
                            }
                        }
                    }
                }
                SavedSnackbar.showExport(
                    this@ToolResultActivity, rootView, saved, R.string.result_saved_documents, docId
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(getString(R.string.tools_result_save_failed, e.message ?: ""))
            }
        }
    }

    /** The type the Documents copy is indexed with: a document's PDF, a ZIP, or the result's own. */
    private fun saveMimeFor(name: String): String = when {
        docIds.size == 1 -> "application/pdf"
        name.endsWith(".zip", ignoreCase = true) -> "application/zip"
        else -> mime
    }

    private fun suggestedName(): String? = when {
        docIds.size == 1 -> (docIds.firstOrNull()?.let { repo.getDocument(it)?.name } ?: toolName) + ".pdf"
        docIds.size > 1 -> "$toolName.zip"
        files.size == 1 -> files.first().name
        files.isNotEmpty() -> "$toolName.zip"
        else -> null
    }

    private fun sourceForSave(): File? {
        val doc = docIds.firstOrNull()
        if (doc != null) {
            val documents = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
            if (documents.size == 1) return documents.first().pdfFile
            return zipOf(documents.map { it.pdfFile })
        }
        if (files.size == 1) return files.first()
        return zipOf(files)
    }

    private fun zipOf(sources: List<File>): File? {
        val out = File(cacheDir, "documents/save/${System.currentTimeMillis()}.zip").apply {
            parentFile?.mkdirs()
        }
        return try {
            val taken = mutableSetOf<String>()
            ZipOutputStream(out.outputStream()).use { zip ->
                for (source in sources) {
                    if (!source.isFile) continue
                    var name = source.name
                    var n = 2
                    while (!taken.add(name)) {
                        name = "${source.name.substringBeforeLast('.')} ($n).${source.name.substringAfterLast('.')}"
                        n++
                    }
                    zip.putNextEntry(ZipEntry(name))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            out.delete()
            fail(getString(R.string.tools_result_save_failed, e.message ?: ""))
            null
        }
    }

    private fun saveTo(uri: Uri) {
        val source = sourceForSave() ?: return fail(getString(R.string.tools_result_nothing))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("No access to the destination")
                }
                Toast.makeText(
                    this@ToolResultActivity,
                    getString(R.string.tools_result_saved, source.name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(getString(R.string.tools_result_save_failed, e.message ?: ""))
            }
        }
    }

    private fun showInLibrary() {
        val docId = docIds.firstOrNull()
        startActivity(
            ShellActivity.intent(this, docId).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun undo() {
        if (undone) return
        undone = true
        val outs = files.toList()
        // T10-FIX-PDFA-UNDO data-safety guard: a result carrying BOTH a library document and a
        // generated file is an export whose docIds are inputs, not copies. No tool legitimately
        // creates that shape — document copies pass docIds only, exports pass files only — and
        // deleteDocument permanently purges (no Trash). Undo therefore removes the generated
        // files and keeps the document: the safe direction for an unknown caller.
        val docs = if (outs.isEmpty()) docIds.toList() else emptyList()
        docIds.clear()
        files.clear()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                docs.forEach { runCatching { repo.deleteDocument(it) } }
                outs.forEach { runCatching { it.delete() } }
            }
            Toast.makeText(this@ToolResultActivity, R.string.tools_result_undone, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---- ID card: Keep / Discard (F1/W3) ----------------------------------

    private fun handleBack() {
        if (idCardFlow) confirmDiscardIdCard() else finish()
    }

    /** Back on a saved ID card asks before anything is deleted. */
    private fun confirmDiscardIdCard() {
        if (undone) {
            finish()
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.fixflow_id_discard_title)
            .setMessage(R.string.fixflow_id_discard_msg)
            .setPrimary(R.string.discard) { discardIdCard() }
            .setSecondary(R.string.fixflow_keep)
            .build()
            .show()
    }

    /** Deletes exactly the card this run created (and its preview); nothing else is touched. */
    private fun discardIdCard() {
        if (undone) return
        undone = true
        val docs = docIds.toList()
        docIds.clear()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { docs.forEach { runCatching { repo.deleteDocument(it) } } }
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    private fun fail(message: String) {
        errorView.text = message
        errorView.isVisible = true
    }

    private fun defaultMime(): String = when {
        docIds.isNotEmpty() -> "application/pdf"
        files.any { it.name.endsWith(".zip") } -> "application/zip"
        files.any { it.name.endsWith(".png") } -> "image/png"
        files.any { it.name.endsWith(".docx") } ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        files.any { it.name.endsWith(".pptx") } ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        files.any { it.name.endsWith(".xlsx") } ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "image/jpeg"
    }

    companion object {
        private const val EXTRA_TOOL_ID = "tool_id"
        private const val EXTRA_DOC_IDS = "doc_ids"
        private const val EXTRA_FILES = "files"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_NOTE = "note"

        /** T05: Compress's wanted size in bytes (absent or 0 = no target). */
        private const val EXTRA_TARGET = "target_bytes"

        /** F1: view tags for the ID card's two actions (no new ids needed). */
        const val TAG_KEEP = "fixflow:id_card_keep"
        const val TAG_DISCARD = "fixflow:id_card_discard"

        /** fixidcrop: the card page's dark preview backdrop (ink fill, 1dp #33FFFFFF line). */
        private const val ID_PREVIEW_LINE = 0x33FFFFFF
        private const val ID_PREVIEW_PADDING_DP = 10f

        fun intent(
            context: Context,
            toolId: String,
            docIds: List<String> = emptyList(),
            files: List<File> = emptyList(),
            mime: String? = null,
            note: String? = null,
            /** T05: what Compress was asked to reach, so a miss can be said out loud. */
            targetBytes: Long? = null
        ): Intent = Intent(context, ToolResultActivity::class.java)
            .putExtra(EXTRA_TOOL_ID, toolId)
            .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(docIds))
            .putStringArrayListExtra(EXTRA_FILES, ArrayList(files.map { it.path }))
            .putExtra(EXTRA_MIME, mime)
            .putExtra(EXTRA_NOTE, note)
            .apply { if (targetBytes != null && targetBytes > 0L) putExtra(EXTRA_TARGET, targetBytes) }
    }
}
