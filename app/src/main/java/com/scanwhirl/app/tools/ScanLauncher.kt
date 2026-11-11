package com.scanwhirl.app.tools

import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ScanReviewActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * test26 T2.8 — scanning entry points, moved verbatim out of `MainActivity`
 * (`showScanChooser` and the `scannerLauncher` registration stay behind:
 * launchers must exist before STARTED).
 *
 * @param startScanning bridges to the Activity's `scannerLauncher` with the
 *   IntentSender the ML Kit client produced.
 */
class ScanLauncher(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val startScanning: (IntentSender) -> Unit
) {

    /** An unfinished review session and, when its save already committed, that document. */
    internal class Unfinished(val dir: File, val pageCount: Int, val committed: ScanDocument?)

    /**
     * Newest unfinished review session (durable `filesDir/pending_scans` first-class,
     * legacy `cacheDir/pending_*` still honoured). Runs file hashing for the
     * "already saved" check, so call it off the main thread.
     */
    internal fun findUnfinished(): Unfinished? {
        val pending = PendingScans.all(activity).firstOrNull() ?: return null
        return Unfinished(pending, PendingScans.pageCount(pending), repo.committedDocumentFor(pending))
    }

    /**
     * A review screen that died mid-edit leaves its staging folder behind.
     * Offer to resume it; deleting is the user's explicit choice. When the save
     * had already committed, offer to open that document instead of saving again.
     */
    fun offerUnfinishedScan() {
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { findUnfinished() } ?: return@launch
            show(found)
        }
    }

    private fun show(found: Unfinished) {
        val pending = found.dir
        val committed = found.committed
        if (committed != null) {
            SwDialog.card(activity)
                .setTitle(R.string.resume_saved_title)
                .setMessage(activity.getString(R.string.resume_saved_msg, committed.name))
                .setPositiveButton(R.string.resume_saved_open) { _, _ ->
                    activity.startActivity(DocumentViewerActivity.intent(activity, committed.id))
                    // The committed document was verified above; only now do the leftovers go.
                    pending.deleteRecursively()
                }
                .setNegativeButton(R.string.resume_saved_remove) { _, _ -> pending.deleteRecursively() }
                .showSw(isSheet = false)
            return
        }
        SwDialog.card(activity)
            .setTitle(R.string.resume_scan_title)
            .setMessage(activity.getString(R.string.resume_scan_msg, found.pageCount))
            .setPositiveButton(R.string.resume_scan_resume) { _, _ ->
                activity.startActivity(
                    ScanReviewActivity.intent(activity, pending.path, null, repo.nextDocumentName())
                )
            }
            .setNegativeButton(R.string.discard) { _, _ -> pending.deleteRecursively() }
            .showSw(isSheet = false)
    }

    /**
     * Opens the Google scanner, mode dependent on auto-enhance setting.
     * Gallery import inside the scanner is disabled on purpose: it runs
     * perspective crop and tilted photos came back tilted. Photos are
     * imported directly from the scan chooser instead (EXIF only).
     */
    fun launchScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setScannerMode(
                if (Prefs.autoEnhanceScan(activity)) {
                    GmsDocumentScannerOptions.SCANNER_MODE_FULL
                } else {
                    GmsDocumentScannerOptions.SCANNER_MODE_BASE
                }
            )
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setPageLimit(30)
            .build()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    startScanning(sender)
                }
                .addOnFailureListener { e ->
                    val msg = if (e is ApiException) {
                        activity.getString(R.string.scanner_unavailable, e.statusCode)
                    } else {
                        activity.getString(R.string.scanner_failed, e.message)
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.scanner_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the scanned page URIs into a staging folder, then opens the review screen. */
    fun stageAndReview(uris: List<Uri>) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    val staging = PendingScans.create(activity)
                    try {
                        uris.forEachIndexed { index, uri ->
                            try {
                                val bytes = activity.contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
                                    ?: throw IllegalArgumentException("Cannot read this image. Choose it again.")
                                File(staging, String.format(Locale.ROOT, "page_%03d.jpg", index + 1))
                                    // Real JPEG, white alpha and EXIF orientation; no perspective crop.
                                    .writeBytes(ImageTools.normalizePage(bytes))
                            } catch (e: Exception) {
                                throw IllegalArgumentException("Page ${index + 1}: ${e.message}", e)
                            }
                        }
                        staging
                    } catch (t: Throwable) {
                        // Only the folder created above; never resumable as a half-import.
                        staging.deleteRecursively()
                        throw t
                    }
                }
                activity.startActivity(
                    ScanReviewActivity.intent(
                        activity,
                        dir.path,
                        null,
                        repo.nextDocumentName()
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.stage_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }
}
