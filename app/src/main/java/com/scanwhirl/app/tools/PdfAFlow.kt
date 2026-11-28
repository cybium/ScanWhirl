package com.scanwhirl.app.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ProOffer
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.tools.ToolFirstScreenCopy
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.PdfA
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CancellationException
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
 * image-only because a PDF/A archive may not carry the non-embedded OCR font) -> the shared
 * result screen (Open, Share, Save, Show in library). A password-protected document is refused
 * with a clear line, because PDF/A never carries encryption.
 */
object PdfAFlow {

    fun start(activity: AppCompatActivity, repo: ScanRepository) {
        // p2pro #4: the same Pro offer as every other gate, with Buy and Restore. The old
        // "Got it" sheet named the tool but gave the user no way to unlock it.
        if (!ProOffer.require(activity)) return
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
            // VQA V114: "PDF/A" alone is a format noun; say what the copy is for.
            .setMessage(
                ToolFirstScreenCopy.text(activity, "pdfa").orEmpty() + "\n\n" +
                    confirmIdentity(activity, doc) + "\n\n" + activity.getString(R.string.pdfa_note)
            )
            .setPrimary(R.string.pdfa_create) {
                create(activity, repo, doc)
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    /**
     * UI-W6: the chosen source is reviewed before anything is written, by name and pages · size,
     * so two similar library names cannot silently produce the wrong archive copy.
     */
    private fun confirmIdentity(activity: AppCompatActivity, doc: ScanDocument): String {
        val size = doc.cachedSize.takeIf { it > 0 } ?: doc.pdfFile.length()
        return activity.resources.getQuantityString(
            R.plurals.tools_setup_doc_meta,
            doc.pageCount,
            doc.pageCount,
            com.scanwhirl.app.util.StatusLine.formatBytes(size)
        ).let { "${doc.name}\n$it" }
    }

    private fun create(activity: AppCompatActivity, repo: ScanRepository, doc: ScanDocument) {
        // L3-05: the existing progress UI opens before the work starts and closes
        // on success, cancellation and every error. No silent multi-second wait.
        val progress = com.scanwhirl.app.ui.kit.SwProgress.show(
            activity, activity.getString(R.string.progress_title)
        )
        activity.lifecycleScope.launch {
            try {
                val (file, identity) = withContext(Dispatchers.IO) {
                    val exported = exportForResult(activity, repo, doc.id)
                    exported to ToolResultActivity.captureOutputIdentityBlocking(
                        activity, emptyList(), listOf(exported)
                    )
                }
                activity.startActivity(resultIntent(activity, file, identity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(activity, L3FailureCopy.message(activity, e), Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss()
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
    internal fun resultIntent(context: Context, file: File, outputIdentity: String? = null): Intent =
        ToolResultActivity.intent(
            context,
            toolId = "pdfa",
            files = listOf(file),
            mime = "application/pdf",
            outputIdentity = outputIdentity
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
 * The pages are the document's existing JPEGs (never re-encoded, so the look is identical), no
 * text layer is written (a PDF/A archive may not carry the non-embedded OCR Helvetica), and the
 * embedded sRGB profile (`res/raw/srgb.icc`) makes the OutputIntent valid. The result screen
 * then lets the user open, share or save the file wherever they want.
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

        val dir = File(context.cacheDir, "pdfa").apply { mkdirs() }
        val out = File(dir, "${safeName(doc.name)}-pdfa.pdf")
        PdfGenerator.imagesToPdf(
            images = doc.pageFiles,
            outFile = out,
            pdfa = mode
        )
        out
    }

    private fun safeName(name: String): String {
        val cleaned = name.trim().map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").trim().replace(Regex("\\s+"), " ").take(60)
        return cleaned.ifBlank { "Document" }.lowercase(Locale.ROOT).replace(' ', '-')
    }
}

/**
 * L3-05 — one localized cause/action mapping for the four tool sites that used
 * to print a JVM message. Only the bucket matters in the UI; details stay out of
 * the UI entirely (never logged here either, so no secret or document path can
 * leak through a user-visible line).
 */
object L3FailureCopy {
    fun message(context: Context, e: Throwable): String {
        val raw = e.localizedMessage.orEmpty().lowercase(Locale.ROOT)
        return when {
            e is SecurityException || raw.contains("password") || raw.contains("encrypt") ||
                raw.contains("denied") || raw.contains("permission") ||
                raw.contains("corrupt") || raw.contains("malformed") ->
                context.getString(R.string.ux130_l3_failure_unreadable)
            e is OutOfMemoryError || raw.contains("space") || raw.contains("enospc") ||
                raw.contains("quota") ->
                context.getString(R.string.ux130_l3_failure_no_space)
            else -> context.getString(R.string.ux130_l3_failure_generic)
        }
    }
}
