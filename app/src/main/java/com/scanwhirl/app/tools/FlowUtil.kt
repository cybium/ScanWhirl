package com.scanwhirl.app.tools

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
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
        // UI-W6: every one-document chooser uses the shared searchable picker. Its rows carry a
        // real first-page thumbnail, name, pages · size · date and the category; a protected
        // source shows a lock and no metadata. The empty library is an explicit Scan route
        // instead of a toast.
        com.scanwhirl.app.ui.kit.DocumentPicker.show(
            activity = activity,
            title = activity.getString(titleRes),
            docs = docs,
            onEmptyScan = { openHome(activity) },
            onPick = onPicked
        )
    }

    /** The honest route out of an empty picker: Home, where Scan and Import live. */
    fun openHome(activity: AppCompatActivity) {
        activity.startActivity(
            com.scanwhirl.app.ui.shell.ShellActivity.intent(activity)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
