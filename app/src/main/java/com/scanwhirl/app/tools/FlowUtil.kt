package com.scanwhirl.app.tools

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import java.util.Locale

/**
 * Shared helpers for the tool flows extracted from `MainActivity` in test26
 * Phase 2. Bodies moved verbatim; only the receiver changed.
 */
internal object FlowUtil {

    /** Reads a content Uri fully; null when unreadable. */
    fun readUriBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun intText(value: Int): String = String.format(Locale.ROOT, "%d", value)

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('_')
        return cleaned.ifBlank { "image" }.take(60)
    }

    /** Name for an imported device PDF: real file name when we can read it. */
    fun devicePdfName(repo: ScanRepository, uri: Uri): String =
        repo.displayNameOf(uri)
            ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: "Imported PDF"

    /** Shared "pick one of these scans" sheet (Compress, Pro tools, Lock, Vault). */
    fun pickScan(
        activity: AppCompatActivity,
        titleRes: Int,
        docs: List<ScanDocument>,
        onPicked: (ScanDocument) -> Unit
    ) {
        if (docs.isEmpty()) {
            Toast.makeText(activity, R.string.empty_title, Toast.LENGTH_SHORT).show()
            return
        }
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(titleRes)
            .setOptions(docs.map { it.name }) { which, _ ->
                onPicked(docs[which])
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }
}
