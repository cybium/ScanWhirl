package com.scanwhirl.app.tools

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.BoundedCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Shared helpers for the tool flows extracted from `MainActivity` in test26
 * Phase 2. Bodies moved verbatim; only the receiver changed.
 */
internal object FlowUtil {

    /**
     * P1: a picked image is copied into a private cache file with a hard cap and
     * off the main thread. The old `readUriBytes` read an arbitrary photo fully
     * into memory on the UI thread, so a large camera image could OOM/ANR before
     * the resize sheet even opened. The caller reads the bounded copy and releases it.
     *
     * @throws BoundedCopy.Refused when the source is unreadable or over the cap;
     *   its [BoundedCopy.Refused.result] tells the caller which localized reason to show.
     */
    internal suspend fun copyPickedImage(context: Context, uri: Uri, label: String): File {
        val dir = File(context.cacheDir, "documents/resize_input").apply { mkdirs() }
        val stem = sanitizeFileName(label).substringBeforeLast('.').ifBlank { "image" }
        val token = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val target = File(dir, "${stem}_$token.img")
        val result = withContext(Dispatchers.IO) {
            BoundedCopy.copy(context, uri, target, BoundedCopy.IMAGE_CAP)
        }
        BoundedCopy.requireOk(result, context)
        return target
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
        @StringRes noEligibleTitle: Int? = null,
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
            noEligibleTitle = noEligibleTitle,
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
