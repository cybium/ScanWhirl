package com.scanwhirl.app.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.PdfA
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * v2 UI: 03_tools_hub — "Save as PDF/A" (Convert group, Pro).
 *
 * Pick a document -> setup sheet -> a PDF/A-2b copy of the document (same pages, same look,
 * invisible OCR text kept when searchable PDFs are on) -> the shared result screen (Open,
 * Share, Save, Show in library). A password-protected document is refused with a clear line,
 * because PDF/A never carries encryption.
 */
object PdfAFlow {

    fun start(activity: AppCompatActivity, repo: ScanRepository) {
        if (!ProGate.isPro(activity)) {
            SwSheet.builder(activity)
                .setTitle(R.string.pro_suite_title)
                .setMessage(R.string.pdfa_pro_message)
                .setPrimary(R.string.sheet_got_it)
                .build()
                .show()
            return
        }
        val docs = repo.listDocuments()
        FlowUtil.pickScan(activity, R.string.pdfa_pick, docs) { doc -> confirm(activity, repo, doc) }
    }

    private fun confirm(activity: AppCompatActivity, repo: ScanRepository, doc: ScanDocument) {
        if (doc.isLocked) {
            SwSheet.note(
                activity,
                activity.getString(R.string.tool_pdfa),
                activity.getString(R.string.pdfa_locked)
            ).show()
            return
        }
        SwSheet.builder(activity)
            .setTitle(R.string.tool_pdfa)
            .setMessage(R.string.pdfa_note)
            .setPrimary(R.string.pdfa_create) {
                create(activity, repo, doc)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun create(activity: AppCompatActivity, repo: ScanRepository, doc: ScanDocument) {
        activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { exportForResult(activity, repo, doc.id) }
                activity.startActivity(resultIntent(activity, file))
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.pdfa_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * T10-FIX-PDFA-UNDO (data loss): the result screen shows the generated PDF/A **file** only.
     *
     * Passing the source document id as `docIds` made the result screen name/open/share the
     * source and, worse, its Undo called `deleteDocument(sourceId)`, which permanently purges
     * (no Trash): "Save as PDF/A" then "Undo" destroyed the user's original scan. A PDF/A export
     * creates no library document, so it is an export-only result.
     */
    internal fun resultIntent(context: Context, file: File): Intent =
        ToolResultActivity.intent(
            context,
            toolId = "pdfa",
            files = listOf(file),
            mime = "application/pdf"
        )

    /** Writes the PDF/A copy and moves it where Open/Share can hand it over (see below). */
    internal suspend fun exportForResult(context: Context, repo: ScanRepository, docId: String): File {
        val exported = PdfAExport(context, repo).export(docId)
        return publishedCopy(context, exported)
    }

    /**
     * Moves the finished PDF/A under `cacheDir/documents/`, the tree the app's FileProvider
     * registers as "cache_docs". Open and Share hand the result to another app through a
     * content:// URI, and `cacheDir/pdfa/` has no FileProvider root, so Open/Share threw
     * "Failed to find configured root" once the result screen described the PDF/A file itself.
     * A move keeps one copy; the fallback covers a filesystem where rename cannot cross over.
     * The `pdfa` scratch folder is wiped by the cold-start privacy sweep; the published copy
     * follows the share retention rule.
     */
    private fun publishedCopy(context: Context, file: File): File {
        // X3: one folder per export so two documents with the same name (the PDF/A name is
        // derived from the document name) never overwrite a copy the result screen is sharing.
        val out = File(ShareUtils.newShareDirectory(context), file.name)
        if (file.renameTo(out)) return out
        file.copyTo(out, overwrite = false)
        file.delete()
        return out
    }
}

/**
 * Builds the PDF/A-2b copy of one document into the app cache.
 *
 * The pages are the document's existing JPEGs (never re-encoded, so the look is identical), the
 * invisible OCR text layer is carried when searchable PDFs are enabled, and the embedded sRGB
 * profile (`res/raw/srgb.icc`) makes the OutputIntent valid. The result screen then lets the
 * user open, share or save the file wherever they want.
 */
class PdfAExport(private val context: Context, private val repo: ScanRepository) {

    suspend fun export(docId: String): File = withContext(Dispatchers.IO) {
        val doc = repo.getDocument(docId)
            ?: throw IllegalArgumentException("This document no longer exists")
        require(!doc.isLocked) { context.getString(R.string.pdfa_locked) }
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }

        val icc = context.resources.openRawResource(R.raw.srgb).use { it.readBytes() }
        val created = doc.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        val mode = PdfA.Mode(
            iccProfile = icc,
            title = doc.name,
            createdAt = created,
            modifiedAt = now,
            documentId = PdfA.documentId(doc.name, created),
            timeZone = TimeZone.getDefault()
        )

        val layers = textLayers(doc)
        val dir = File(context.cacheDir, "pdfa").apply { mkdirs() }
        val out = File(dir, "${safeName(doc.name)}-pdfa.pdf")
        PdfGenerator.imagesToPdf(
            images = doc.pageFiles,
            outFile = out,
            textLayers = layers,
            pdfa = mode
        )
        out
    }

    /** Page index -> OCR geometry, only for pages whose geometry matches the page image. */
    private fun textLayers(doc: ScanDocument): Map<Int, OcrPage> {
        if (!Prefs.makeSearchable(context)) return emptyMap()
        val geo = repo.ocrGeometry(doc.id)
        if (geo.isEmpty()) return emptyMap()
        val layers = LinkedHashMap<Int, OcrPage>()
        doc.pageFiles.forEachIndexed { index, page ->
            geo[page.name]?.let { layers[index] = it }
        }
        return layers
    }

    private fun safeName(name: String): String {
        val cleaned = name.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().replace(Regex("\\s+"), " ").take(60)
        return cleaned.ifBlank { "Document" }.lowercase(Locale.ROOT).replace(' ', '-')
    }
}
