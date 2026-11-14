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
import android.os.SystemClock
import android.util.Log
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.AutoExport
import com.scanwhirl.app.ui.home.HomeReveal
import com.scanwhirl.app.util.Durable
import kotlinx.coroutines.CancellationException
import java.io.IOException
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

    companion object {
        const val PERF_TAG = "ScanPerf"
    }

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
            com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
                .setTitle(R.string.resume_saved_title)
                .setMessage(activity.getString(R.string.resume_saved_msg, committed.name))
                .setPrimary(R.string.resume_saved_open) {
                    activity.startActivity(DocumentViewerActivity.intent(activity, committed.id))
                    // The committed document was verified above; only now do the leftovers go.
                    pending.deleteRecursively()
                }
                .setSecondary(R.string.resume_saved_remove) { pending.deleteRecursively() }
                .build()
                .show()
            return
        }
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.resume_scan_title)
            .setMessage(activity.getString(R.string.resume_scan_msg, found.pageCount))
            .setPrimary(R.string.resume_scan_resume) {
                activity.startActivity(
                    ScanReviewActivity.intent(activity, pending.path, null, repo.nextDocumentName())
                )
            }
            .setSecondary(R.string.discard) { pending.deleteRecursively() }
            .build()
            .show()
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
            // M08: JPEG pages only. ScanWhirl builds its own verified, searchable PDF from them;
            // asking Google for a PDF too made it encode one at Done that nothing read.
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
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

    /**
     * M08: Google's scanner already offered crop, rotate, filters, retake and add page, so
     * its Done saves straight away — no second edit screen. The pages go through the same
     * durable staging folder and the same verified save transaction as Review's Save, and
     * Home then shows the new document at the top with Undo.
     *
     * Pages are copied byte for byte (no decode here): the save transaction validates and
     * normalises each one anyway, so doing it twice only cost time. If the save fails, the
     * staged pages are kept and Review opens on them, so nothing scanned is lost.
     */
    fun saveScanned(uris: List<Uri>) {
        val progress = host.showProgress(R.string.review_saving)
        activity.lifecycleScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var staging: File? = null
            try {
                val dir = withContext(Dispatchers.IO) { copyScannedPages(uris) }
                staging = dir
                val tCopied = SystemClock.elapsedRealtime()
                val name = withContext(Dispatchers.IO) { repo.nextDocumentName() }
                val doc = repo.saveScan(PendingScans.pages(dir).map { Uri.fromFile(it) }, null, name, reviewDir = dir)
                val tSaved = SystemClock.elapsedRealtime()
                val note = withContext(Dispatchers.IO) {
                    AutoExport.afterSave(activity, repo, doc).also { dir.deleteRecursively() }
                }
                Log.i(PERF_TAG, "perf direct_save pages=${uris.size} copy_ms=${tCopied - t0} " +
                    "save_ms=${tSaved - tCopied} total_ms=${SystemClock.elapsedRealtime() - t0}")
                // Free text recognition in the background; it never invalidates the saved PDF.
                ScanWhirlApp.from(activity).indexer.enqueue(doc.id)
                HomeReveal.request(doc.id)
                host.scanSaved(doc, note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PERF_TAG, "direct save failed", e)
                val kept = ((e as? PartialStaging)?.dir ?: staging)?.takeIf { PendingScans.pageCount(it) > 0 }
                if (kept != null) {
                    host.toast(activity.getString(R.string.scan_save_failed_review, e.message), Toast.LENGTH_LONG)
                    activity.startActivity(ScanReviewActivity.intent(activity, kept.path, null, repo.nextDocumentName()))
                } else {
                    host.toast(activity.getString(R.string.stage_failed, e.message), Toast.LENGTH_LONG)
                }
            } finally {
                progress.dismiss()
            }
        }
    }

    /** Bounded byte copy of each scanned page into a new durable staging folder, in order. */
    internal fun copyScannedPages(uris: List<Uri>): File {
        val staging = PendingScans.create(activity)
        try {
            uris.forEachIndexed { index, uri ->
                val bytes = activity.contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
                    ?: throw IllegalArgumentException("Page ${index + 1} could not be read. Scan it again.")
                val target = File(staging, String.format(Locale.ROOT, "page_%03d.jpg", index + 1))
                // Written aside and renamed, so a half-written page is never listed.
                val part = File(staging, target.name + ".part")
                Durable.writeFile(part, bytes)
                if (!part.renameTo(target)) throw IOException("Could not stage page ${index + 1}.")
            }
            return staging
        } catch (t: Throwable) {
            // Keep any page already copied (the scanner's own copy may be gone); an empty folder goes.
            if (PendingScans.pageCount(staging) == 0) {
                staging.deleteRecursively()
                throw t
            }
            throw PartialStaging(staging, t)
        }
    }

    /** Copying stopped part-way; [dir] holds the pages that did arrive. */
    internal class PartialStaging(val dir: File, cause: Throwable) : IOException(cause.message, cause)

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
