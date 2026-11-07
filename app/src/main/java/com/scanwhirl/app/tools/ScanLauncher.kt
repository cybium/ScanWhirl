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
import com.scanwhirl.app.store.ScanRepository
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

    /**
     * A review screen that died mid-edit leaves its staging folder behind.
     * Offer to resume it; deleting is the user's explicit choice.
     */
    fun offerUnfinishedScan() {
        val pending = activity.cacheDir.listFiles { f ->
            f.isDirectory &&
                f.name.startsWith("pending_") &&
                !f.listFiles { p -> p.extension.equals("jpg", true) }.isNullOrEmpty()
        }?.maxByOrNull { it.lastModified() } ?: return
        val count = pending.listFiles { p -> p.extension.equals("jpg", true) }?.size ?: 0
        SwDialog.card(activity)
            .setTitle(R.string.resume_scan_title)
            .setMessage(activity.getString(R.string.resume_scan_msg, count))
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
                    val staging = File(activity.cacheDir, "pending_${System.currentTimeMillis()}")
                    staging.mkdirs()
                    uris.forEachIndexed { index, uri ->
                        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("could not read a scanned page")
                        if (bytes.isEmpty()) throw IllegalStateException("a scanned page was empty")
                        File(staging, String.format(Locale.ROOT, "page_%03d.jpg", index + 1))
                            // EXIF rotation only: gallery photos are never cropped or tilted.
                            .writeBytes(ImageTools.normalizePage(bytes))
                    }
                    staging
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
