package com.scanwhirl.app.ui.tools

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
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
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImageTools
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
 * One screen for a new document copy and for an exported file: Open, Share, Save (the system
 * Save-as picker), Show in library and Undo. Undo removes only the copy this run created; the
 * source document is never touched. Built with the kit on the ink background.
 */
class ToolResultActivity : BaseActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var toolName: String
    private var docIds: MutableList<String> = mutableListOf()
    private var files: MutableList<File> = mutableListOf()
    private var mime = "application/pdf"
    private var undone = false
    private lateinit var summary: TextView
    private lateinit var errorView: TextView
    private lateinit var preview: ImageView
    private lateinit var saveAs: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        val toolId = intent.getStringExtra(EXTRA_TOOL_ID).orEmpty()
        toolName = ToolRegistry.byId(toolId)?.let { getString(it.title) }.orEmpty()
        docIds = intent.getStringArrayListExtra(EXTRA_DOC_IDS)?.toMutableList() ?: mutableListOf()
        files = intent.getStringArrayListExtra(EXTRA_FILES)?.map { File(it) }?.toMutableList() ?: mutableListOf()
        mime = intent.getStringExtra(EXTRA_MIME) ?: defaultMime()
        saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            uri ?: return@registerForActivityResult
            saveTo(uri)
        }
        setContentView(buildRoot())
        render()
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
            setOnClickListener { finish() }
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

        preview = ImageView(this).apply {
            id = R.id.toolResultPreview
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            background = SwStyle.rounded(this@ToolResultActivity, Color.WHITE, radiusDp = 16f)
            clipToOutline = true
        }
        column.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolResultActivity, 200f)
        ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 14f) })

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

        column.addView(
            button(getString(R.string.tools_result_open), R.id.toolResultOpen, primary = true) { open() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@ToolResultActivity, 54f)
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 16f) }
        )
        column.addView(
            rowOf(
                button(getString(R.string.tools_result_share), R.id.toolResultShare) { share() },
                button(getString(R.string.tools_result_save), R.id.toolResultSave) { save() }
            )
        )
        column.addView(
            rowOf(
                button(getString(R.string.tools_result_library), R.id.toolResultLibrary) { showInLibrary() },
                button(getString(R.string.tools_result_undo), R.id.toolResultUndo) { undo() }
            )
        )
        return root
    }

    private fun rowOf(left: TextView, right: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, SwStyle.dp(this@ToolResultActivity, 50f), 1f).apply {
                marginEnd = SwStyle.dp(this@ToolResultActivity, 8f)
            })
            addView(right, LinearLayout.LayoutParams(0, SwStyle.dp(this@ToolResultActivity, 50f), 1f).apply {
                marginStart = SwStyle.dp(this@ToolResultActivity, 8f)
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@ToolResultActivity, 10f) }
        }

    private fun button(text: String, id: Int, primary: Boolean = false, onClick: () -> Unit): TextView =
        SwStyle.text(this, 15f, SwStyle.color(
            this, if (primary) R.color.sw_disc_ink else R.color.text_hologram_hi
        ), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
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
        val docs = docIds.mapNotNull { runCatching { repo.getDocument(it) }.getOrNull() }
        if (docs.isNotEmpty()) {
            val pages = docs.sumOf { it.pageCount }
            val bytes = docs.sumOf { if (it.pdfFile.exists()) it.pdfFile.length() else 0L }
            summary.text = if (docs.size == 1) {
                getString(R.string.tools_result_summary_one_doc, pages, StatusLine.formatBytes(bytes))
            } else {
                getString(R.string.tools_result_summary_docs, docs.size, pages, StatusLine.formatBytes(bytes))
            }
            previewPreview(docs.first().pageFiles.firstOrNull())
        } else if (files.isNotEmpty()) {
            val bytes = files.sumOf { it.length() }
            summary.text = if (files.size == 1) {
                getString(R.string.tools_result_summary_one_file, StatusLine.formatBytes(bytes))
            } else {
                getString(R.string.tools_result_summary_files, files.size, StatusLine.formatBytes(bytes))
            }
            val image = files.firstOrNull { it.name.endsWith(".jpg") || it.name.endsWith(".png") }
            previewPreview(image)
            if (image == null) preview.setImageResource(iconFor(files.first()))
        } else {
            summary.text = getString(R.string.tools_result_nothing)
        }
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
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData(
                ClipDescription(toolName, arrayOf(type)),
                ClipData.Item(uris.first())
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.tools_result_share)))
    }

    private fun save() {
        val name = suggestedName() ?: return fail(getString(R.string.tools_result_nothing))
        saveAs.launch(name)
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
        startActivity(
            Intent(this, ShellActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun undo() {
        if (undone) return
        undone = true
        val docs = docIds.toList()
        val outs = files.toList()
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
        else -> "image/jpeg"
    }

    companion object {
        private const val EXTRA_TOOL_ID = "tool_id"
        private const val EXTRA_DOC_IDS = "doc_ids"
        private const val EXTRA_FILES = "files"
        private const val EXTRA_MIME = "mime"

        fun intent(
            context: Context,
            toolId: String,
            docIds: List<String> = emptyList(),
            files: List<File> = emptyList(),
            mime: String? = null
        ): Intent = Intent(context, ToolResultActivity::class.java)
            .putExtra(EXTRA_TOOL_ID, toolId)
            .putStringArrayListExtra(EXTRA_DOC_IDS, ArrayList(docIds))
            .putStringArrayListExtra(EXTRA_FILES, ArrayList(files.map { it.path }))
            .putExtra(EXTRA_MIME, mime)
    }
}
