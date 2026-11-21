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

        /**
         * S1 defect 1: the launcher (not the legacy MainActivity) offers an unfinished scan
         * once per process, after storage recovery. A rotation recreates ShellActivity but must
         * not show the offer a second time, so the guard lives here, outside the activity.
         */
        @Volatile
        private var coldStartOfferConsumed = false

        /** Guards against stacking two resume sheets from quick repeated Scan taps. */
        @Volatile
        private var resumeOfferShowing = false

        internal fun consumeColdStartOffer(): Boolean {
            if (coldStartOfferConsumed) return false
            coldStartOfferConsumed = true
            return true
        }

        internal fun resetColdStartOfferForTest() {
            coldStartOfferConsumed = false
        }

        internal fun coldStartOfferConsumedForTest(): Boolean = coldStartOfferConsumed

        /**
         * S1 review P0: a static handler for the scanner-unavailable photo picker. It creates a
         * fresh launcher bound to the activity that actually received the picked photos.
         */
        internal val photoFallbackHandler: CropResultHandler = { host, _, uris ->
            if (host is HomeHost && uris.isNotEmpty()) {
                val liveRepo = ScanWhirlApp.from(host).repository
                ScanLauncher(host, liveRepo, host, {}).stageAndReview(uris)
            }
        }

        /**
         * T15: pages one camera scan may return. ML Kit documents no maximum (the real limit
         * is phone memory), so this is ScanWhirl's own ceiling, not Google's. Every page comes
         * back as a full JPEG that is staged, decoded and paginated, so 60 is what a 2 to 3 GB
         * phone can hold in one session; anything longer is built with "Add more", which
         * appends the same document in smaller batches.
         */
        internal const val CAMERA_PAGE_LIMIT = 60
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
        // S1 review P1: consume the once-per-process guard only when the offer is really shown.
        // If the owning screen is rotated away while recovery is pending, its coroutine is
        // cancelled before this point and the recreated launcher can still offer the pages.
        if (!consumeColdStartOffer()) return
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
     * L2-04: the startup offer above is once per process so a rotation cannot
     * stack it, but a person who dismissed it and taps Create/Scan again must
     * see their unfinished pages. Only an explicit Resume or Discard removes
     * the offer; merely navigating or rotating leaves the pages discoverable.
     */
    private fun offerResumeFromScanEntry(): Boolean {
        if (PendingScans.all(activity).isEmpty()) return false
        if (resumeOfferShowing) return true
        resumeOfferShowing = true
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { findUnfinished() }
            if (found == null) {
                resumeOfferShowing = false
                return@launch
            }
            showResumeFromScanEntry(found)
        }
        return true
    }

    private fun showResumeFromScanEntry(found: Unfinished) {
        val pending = found.dir
        val committed = found.committed
        val primary: CharSequence
        val secondary: CharSequence
        if (committed != null) {
            primary = activity.getString(R.string.resume_saved_open)
            secondary = activity.getString(R.string.resume_saved_remove)
        } else {
            primary = activity.getString(R.string.ux130_l2_resume_scan)
            secondary = activity.getString(R.string.ux130_l2_discard_scan)
        }
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(if (committed != null) R.string.resume_saved_title else R.string.resume_scan_title)
            .setMessage(
                if (committed != null) activity.getString(R.string.resume_saved_msg, committed.name)
                else activity.getString(R.string.resume_scan_msg, found.pageCount)
            )
            .setPrimary(primary) {
                resumeOfferShowing = false
                if (committed != null) {
                    activity.startActivity(DocumentViewerActivity.intent(activity, committed.id))
                    pending.deleteRecursively()
                } else {
                    activity.startActivity(
                        ScanReviewActivity.intent(activity, pending.path, null, repo.nextDocumentName())
                    )
                }
            }
            .setSecondary(secondary) {
                resumeOfferShowing = false
                pending.deleteRecursively()
            }
            .build()
        // Dismissing without a decision keeps the staging folder for the next Scan.
        sheet.setOnDismissListener { resumeOfferShowing = false }
        sheet.show()
    }

    /**
     * Opens the Google scanner, mode dependent on auto-enhance setting.
     * Gallery import inside the scanner is disabled for the camera entry: it runs
     * perspective crop and tilted photos came back tilted. Gallery auto-crop is a
     * separate, opt-in entry ([launchGalleryCrop]) where the crop is the point.
     */
    fun launchScanner() {
        // L2-04: staging is the durable source of truth. The cold-start offer keeps
        // its rotation guard, but each explicit Scan entry re-offers unfinished
        // pages until the owner resumes or discards them.
        if (offerResumeFromScanEntry()) return
        val options = cameraOptions()
        try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    startScanning(sender)
                }
                .addOnFailureListener { e ->
                    // S1 defects 5/6: a localized reason and a real way out. Never e.message.
                    scannerUnavailable((e as? ApiException)?.statusCode)
                }
        } catch (e: Exception) {
            scannerUnavailable(null)
        }
    }

    /**
     * The scanner module is absent or refused. It comes from Play services, so say that plainly
     * and offer the same manual photo import Book pages uses instead of a dead tap.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun scannerUnavailable(statusCode: Int?) {
        // L2-04: one truthful sheet, no duplicate Toast and no numeric status code.
        // The Google Play services explanation is the existing reviewed copy.
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.ux130_l2_scanner_unavailable_title)
            .setMessage(R.string.s1_scanner_photo_fallback)
            .setPrimary(R.string.ux130_l2_pick_photos) {
                GalleryCrop.pickPhotosOnly(activity, photoFallbackHandler)
            }
            .setSecondary(R.string.ux130_l2_cancel)
            .build()
            .show()
    }

    /** The camera scan's options, shared with the gallery auto-crop entry. */
    internal fun cameraOptions(): GmsDocumentScannerOptions {
        val cfg = cameraConfig()
        return GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(cfg.galleryImport)
            .setScannerMode(
                if (cfg.fullMode) GmsDocumentScannerOptions.SCANNER_MODE_FULL
                else GmsDocumentScannerOptions.SCANNER_MODE_BASE
            )
            // M08: JPEG pages only. ScanWhirl builds its own verified, searchable PDF from them;
            // asking Google for a PDF too made it encode one at Done that nothing read.
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(cfg.pageLimit)
            .build()
    }

    /** What the camera scan asks for (testable without the ML Kit builder). */
    internal fun cameraConfig(): ScanConfig =
        ScanConfig(
            galleryImport = false,
            pageLimit = CAMERA_PAGE_LIMIT,
            fullMode = Prefs.autoEnhanceScan(activity),
            jpeg = true
        )

    /** T15: a camera session that came back with exactly the page cap. */
    internal fun cameraSessionCapped(pageCount: Int): Boolean = pageCount == CAMERA_PAGE_LIMIT

    /**
     * T15: the session stopped at [CAMERA_PAGE_LIMIT]. The saved document is already verified,
     * so nothing is at risk here: "Add more" opens the existing add-pages flow
     * ([DocumentViewerActivity.insertIntent]) on that same document and "Done" simply closes
     * the sheet, leaving the document saved with the pages it has.
     */
    internal fun offerAddMoreAfterCap(docId: String) {
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.scan_saved)
            .setMessage(activity.getString(R.string.cap_scan_message, CAMERA_PAGE_LIMIT))
            .setPrimary(R.string.cap_scan_add_more) {
                activity.startActivity(DocumentViewerActivity.insertIntent(activity, docId))
            }
            .setSecondary(R.string.cap_scan_done) { /* the saved document keeps its pages */ }
            .build()
            .show()
    }

    /**
     * M08: Google's scanner already offered crop, rotate, filters, retake and add page, so
     * its Done saves straight away — no second edit screen. The pages go through the same
     * durable staging folder and the same verified save transaction as Review's Save, and
     * Home then shows the new document at the top with Undo.
     *
     * F22: the Home scanner opens the saved document right after the save
     * ([openAfterSave]); the photos import keeps the M08 behaviour (Home shows the new card).
     *
     * Pages are copied byte for byte (no decode here): the save transaction validates and
     * normalises each one anyway, so doing it twice only cost time. If the save fails, the
     * staged pages are kept and Review opens on them, so nothing scanned is lost.
     */
    fun saveScanned(uris: List<Uri>, openAfterSave: Boolean = false) {
        // L2-04: a successful scan that returned no pages is not an error and not
        // a document. Offer the same photo fallback instead of saving nothing.
        if (uris.isEmpty()) {
            offerEmptyScanFallback()
            return
        }
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
                // T15: a session that hit the cap stops here instead of opening silently, so
                // the owner can continue in the same document or keep what they have.
                if (cameraSessionCapped(uris.size)) {
                    offerAddMoreAfterCap(doc.id)
                } else if (openAfterSave) {
                    activity.startActivity(DocumentViewerActivity.intent(activity, doc.id))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PERF_TAG, "direct save failed", e)
                reportSaveFailure(e, ((e as? PartialStaging)?.dir ?: staging))
            } catch (e: OutOfMemoryError) {
                // S1 defect 7: an Error is not an Exception. Without this the process died
                // with no explanation. Staged pages are offered back for review, never lost.
                Log.w(PERF_TAG, "direct save ran out of memory", e)
                reportSaveFailure(e, staging, tooLarge = true)
            } finally {
                progress.dismiss()
            }
        }
    }

    /** A scan that succeeded with zero pages: a quiet, actionable fallback. */
    private fun offerEmptyScanFallback() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.ux130_l2_empty_scan_title)
            .setMessage(R.string.ux130_l2_empty_scan_msg)
            .setPrimary(R.string.ux130_l2_pick_photos) {
                GalleryCrop.pickPhotosOnly(activity, photoFallbackHandler)
            }
            .setSecondary(R.string.ux130_l2_cancel)
            .build()
            .show()
    }

    /** Localized reasons only: a raw library/JVM message never reaches the screen. */
    private fun reportSaveFailure(e: Throwable, staging: File?, tooLarge: Boolean = false) {
        val large = tooLarge || ImageTools.isTooLargePhoto(e)
        val reason = activity.getString(if (large) R.string.s1_reason_too_large else R.string.s1_reason_unreadable)
        val kept = staging?.takeIf { PendingScans.pageCount(it) > 0 }
        if (kept != null) {
            host.toast(
                activity.getString(
                    if (large) R.string.s1_scan_too_large_review else R.string.scan_save_failed_review,
                    reason
                ),
                Toast.LENGTH_LONG
            )
            activity.startActivity(ScanReviewActivity.intent(activity, kept.path, null, repo.nextDocumentName()))
        } else {
            host.toast(
                activity.getString(if (large) R.string.s1_scan_too_large else R.string.stage_failed, reason),
                Toast.LENGTH_LONG
            )
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

    /**
     * H2-8: photo [page] (1-based) could not be added, with a reason the screen shows in
     * the person's language. No user-facing English travels in the message.
     */
    internal class StageFailure(
        val page: Int,
        val tooLarge: Boolean,
        cause: Throwable?
    ) : IOException(cause?.message, cause)

    /** Copies the scanned page URIs into a staging folder, then opens the review screen. */
    fun stageAndReview(uris: List<Uri>) {        val progress = host.showProgress()
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
                                    // HEIC/HEIF photos from a phone gallery are re-encoded here.
                                    .writeBytes(ImageTools.normalizeGalleryPhoto(bytes))
                            } catch (e: Exception) {
                                throw StageFailure(index + 1, ImageTools.isTooLargePhoto(e), e)
                            } catch (e: OutOfMemoryError) {
                                throw StageFailure(index + 1, true, e)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // H5-3: cancellation (screen closed or recreated) is not a failure.
                throw e
            } catch (e: Exception) {
                // H2-5/H2-8: a localized reason, never the library's English/JVM text.
                val failed = e as? StageFailure
                val tooLarge = failed?.tooLarge ?: ImageTools.isTooLargePhoto(e)
                val reason = if (failed != null && failed.page > 0) {
                    activity.getString(
                        if (tooLarge) R.string.f8_stage_page_too_large else R.string.f8_stage_page_unreadable,
                        failed.page
                    )
                } else {
                    activity.getString(if (tooLarge) R.string.f8_reason_too_large else R.string.f8_reason_unreadable)
                }
                Toast.makeText(
                    activity,
                    activity.getString(R.string.stage_failed, reason),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: OutOfMemoryError) {
                // H2-8: an Error is not an Exception, so without this the process died. The
                // staging folder above is already deleted, so nothing partial is left behind.
                Toast.makeText(
                    activity,
                    activity.getString(
                        R.string.stage_failed, activity.getString(R.string.f8_reason_too_large)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                progress.dismiss()
            }
        }
    }
}

/** One scan entry's options, in a form a test can read without ML Kit. */
internal data class ScanConfig(
    val galleryImport: Boolean,
    val pageLimit: Int,
    val fullMode: Boolean,
    val jpeg: Boolean
)

/**
 * S1 review P0: a pending crop request is data only — never a lambda that captured a screen.
 * The handlers below receive the LIVE activity at delivery time, so a result delivered after a
 * rotation is handled by the recreated screen instead of a destroyed window/scope.
 */
data class CropRequest(val kind: String, val params: String? = null)

/** A result handler must not capture a particular Activity; all work goes through [activity]. */
typealias CropResultHandler = (activity: AppCompatActivity, request: CropRequest, uris: List<Uri>) -> Unit

/**
 * v2 UI: 01_home_A — gallery auto-crop, shared by Home → Photos, Book pages (L3) and
 * ID card (L4).
 *
 * Real gallery photos are not scans: tables, hands, perspective and backgrounds
 * surround the page. This opens the ML Kit document scanner with gallery import
 * enabled, so the picked photos get the same corner detection, perspective crop,
 * rotation and filters as a camera scan; the scanner returns one cropped JPEG per
 * page in page order.
 *
 * Fallback (scanner module missing or not usable): the same call opens the system
 * photo picker and hands the unchanged photos to `onFallback` (or `onResult` when
 * no fallback was given) so the caller can run its manual CropView path. Nothing
 * is lost and nothing is auto-cropped wrongly.
 *
 * The activity must be a [com.scanwhirl.app.ui.BaseActivity] (it registers the
 * result bridge once) and must still be alive when the scanner returns.
 *
 * @param activity host screen.
 * @param maxPages page limit for this import, clamped to 1..100.
 * @param onResult called with the live activity and the cropped page URIs, in page order.
 * @param onFallback raw picked URIs when the scanner is unavailable; when null the
 *   raw URIs go to [onResult] instead.
 */
fun launchGalleryCrop(
    activity: AppCompatActivity,
    maxPages: Int = 30,
    onResult: CropResultHandler,
    onFallback: CropResultHandler? = null
) {
    GalleryCrop.launch(
        activity, maxPages, CropRequest(activity::class.java.name), onResult, onFallback ?: onResult
    )
}

/**
 * L2-02: "Add from gallery" now uses the same gallery-crop pipeline contract as
 * Home and ID card. The scanner returns corrected JPEGs; they are appended to
 * the live Review screen's staging folder, never committed as a new document
 * and never written back over the picked originals. If the module is missing,
 * the manual picker hands the unchanged photos to the same staging append.
 */
internal fun launchGalleryCropForReview(activity: AppCompatActivity, maxPages: Int = 30) {
    launchGalleryCrop(
        activity,
        maxPages,
        onResult = { live, _, uris -> appendGalleryPages(live, uris) },
        onFallback = { live, _, uris -> appendGalleryPages(live, uris) }
    )
}

/** Appends gallery-crop results to the current Review staging folder, once. */
internal fun appendGalleryPages(activity: AppCompatActivity, uris: List<Uri>) {
    (activity as? ScanReviewActivity)?.appendStagedPages(uris)
}

/** The result bridge behind [launchGalleryCrop] (registered by BaseActivity). */
object GalleryCrop {

    /** How a host screen starts the scanner and the manual fallback picker. */
    internal class Host(
        val startScanner: (IntentSender) -> Unit,
        val pickPhotos: () -> Unit
    )

    /**
     * S1 review P0: data + activity-agnostic handlers only. The handlers are top-level lambdas
     * (or object methods) that receive the live activity, so this map never holds a destroyed
     * Activity and a recreated screen always handles its own result.
     */
    private data class Pending(
        val request: CropRequest,
        val onCropped: CropResultHandler,
        val onFallback: CropResultHandler,
        val boundTo: String
    )

    private const val KEY_TOKEN = "sw_gallery_crop_token"
    private const val KEY_KIND = "sw_gallery_crop_kind"
    private const val KEY_PARAMS = "sw_gallery_crop_params"

    private val hosts = java.util.WeakHashMap<AppCompatActivity, Host>()
    private val pending = LinkedHashMap<String, Pending>()
    private val tokenByActivity = java.util.WeakHashMap<AppCompatActivity, String>()
    /** Set by onSaveInstanceState: the one case where onDestroy must keep the request. */
    private val savedToken = java.util.WeakHashMap<AppCompatActivity, String>()

    /** Tests: fake the scanner (true = started, false = unavailable); null uses ML Kit. */
    @Volatile
    internal var startOverride: ((AppCompatActivity) -> Boolean)? = null

    /** Tests: fake the fallback picker result. */
    @Volatile
    internal var pickOverride: ((AppCompatActivity) -> Unit)? = null

    private fun newToken(): String = java.util.UUID.randomUUID().toString()

    internal fun register(activity: AppCompatActivity, host: Host) {
        hosts[activity] = host
    }

    /**
     * S1 defect 2: persist the pending request in the activity's saved state. On process death
     * neither this map nor the saved state survives, and [deliver] reports the loss instead of
     * dropping the result silently.
     */
    internal fun saveState(activity: AppCompatActivity, outState: android.os.Bundle) {
        val token = tokenByActivity[activity] ?: return
        val waiting = pending[token] ?: return
        outState.putString(KEY_TOKEN, token)
        outState.putString(KEY_KIND, waiting.request.kind)
        outState.putString(KEY_PARAMS, waiting.request.params)
        savedToken[activity] = token
    }

    /**
     * S1 review P0: rebuild the request bound to the recreated screen. The stored handlers are
     * Activity-agnostic; binding is the token mapping plus the [Pending.boundTo] record, and the
     * live activity is supplied again when the result is delivered.
     */
    internal fun restoreState(activity: AppCompatActivity, saved: android.os.Bundle?) {
        val token = saved?.getString(KEY_TOKEN) ?: return
        val waiting = pending[token] ?: return
        if (saved.getString(KEY_KIND) != waiting.request.kind) return
        if (saved.getString(KEY_PARAMS) != waiting.request.params) return
        pending[token] = waiting.copy(boundTo = activity::class.java.name)
        tokenByActivity[activity] = token
    }

    /**
     * C04: screens come and go; the bridge must not keep a destroyed activity (or its pending
     * lambdas, which capture it) in a process-wide map. A rotation is the exception: the request
     * was saved and the new screen re-attaches it, so it must survive this destroy.
     */
    internal fun unregister(activity: AppCompatActivity) {
        hosts.remove(activity)
        val token = tokenByActivity.remove(activity)
        val retained = savedToken.remove(activity)
        if (token != null && (activity.isFinishing || token != retained)) {
            pending.remove(token)
        }
    }

    /** Tests: how many live screens the bridge still holds. */
    internal fun hostCountForTest(): Int = hosts.size

    /** Tests: clear all bridge state between scenarios (the bridge is process-wide). */
    internal fun resetForTest() {
        hosts.clear()
        pending.clear()
        tokenByActivity.clear()
        savedToken.clear()
    }

    private fun showLostResult(activity: AppCompatActivity) {
        android.widget.Toast.makeText(
            activity,
            activity.getString(R.string.s1_gallery_result_lost),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /** The scanner returned cropped pages. */
    internal fun deliver(activity: AppCompatActivity, uris: List<Uri>) {
        val token = tokenByActivity.remove(activity)
        val waiting = token?.let { pending.remove(it) }
        if (waiting == null) {
            showLostResult(activity)
            return
        }
        // Always the CURRENT activity: the stored handler never runs against a dead screen.
        waiting.onCropped(activity, waiting.request, uris)
    }

    /** The scanner was cancelled or returned nothing: keep the caller quiet. */
    internal fun cancel(activity: AppCompatActivity) {
        val token = tokenByActivity.remove(activity) ?: return
        pending.remove(token)
    }

    /** Tests: how many screens are waiting for a gallery-crop result. */
    internal fun pendingCountForTest(): Int = pending.size

    /** The fallback picker returned photos for the caller's manual crop. */
    internal fun deliverFallback(activity: AppCompatActivity, uris: List<Uri>) {
        val token = tokenByActivity.remove(activity)
        val waiting = token?.let { pending.remove(it) }
        if (waiting == null) {
            showLostResult(activity)
            return
        }
        if (uris.isEmpty()) return
        waiting.onFallback(activity, waiting.request, uris)
    }

    internal fun launch(
        activity: AppCompatActivity,
        maxPages: Int,
        request: CropRequest,
        onCropped: CropResultHandler,
        onFallback: CropResultHandler
    ) {
        val token = newToken()
        pending[token] = Pending(request, onCropped, onFallback, activity::class.java.name)
        tokenByActivity[activity] = token
        val started = startOverride?.invoke(activity) ?: startReal(activity, maxPages)
        if (!started) startFallback(activity)
    }

    /**
     * The camera scanner itself is unavailable: open the manual picker with a waiting request so
     * the picked photos actually reach the caller (S1 defect 5).
     */
    internal fun pickPhotosOnly(activity: AppCompatActivity, onPicked: CropResultHandler) {
        val token = newToken()
        val request = CropRequest("camera_photo_fallback")
        pending[token] = Pending(request, onPicked, onPicked, activity::class.java.name)
        tokenByActivity[activity] = token
        startFallback(activity)
    }

    /**
     * S1 review P0 (test seam): true if any of the bridge's maps still holds [activity], directly
     * or through a captured field of a pending request. A destroyed screen must be absent.
     */
    internal fun holdsActivityForTest(activity: AppCompatActivity): Boolean {
        if (hosts.containsKey(activity) || tokenByActivity.containsKey(activity) ||
            savedToken.containsKey(activity)
        ) return true
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun reaches(value: Any?, depth: Int): Boolean {
            if (value == null || depth > 5 || !visited.add(value)) return false
            if (value === activity) return true
            if (value is Array<*>) return value.any { reaches(it, depth + 1) }
            if (value is Iterable<*>) return value.any { reaches(it, depth + 1) }
            if (value is String || value is Number || value is Boolean || value is Char ||
                value.javaClass.isPrimitive
            ) return false
            return try {
                value.javaClass.declaredFields.any { field ->
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) return@any false
                    field.isAccessible = true
                    reaches(field.get(value), depth + 1)
                }
            } catch (_: Throwable) {
                false
            }
        }
        return pending.values.any { reaches(it, 0) }
    }

    /** The scanner IntentSender could not be produced: pick photos for a manual crop. */
    private fun startFallback(activity: AppCompatActivity) {
        val custom = pickOverride
        if (custom != null) {
            custom(activity)
            return
        }
        val host = hosts[activity]
        if (host == null) {
            val token = tokenByActivity.remove(activity)
            if (token != null) pending.remove(token)
            return
        }
        host.pickPhotos()
    }

    private fun startReal(activity: AppCompatActivity, maxPages: Int): Boolean {
        val host = hosts[activity] ?: return false
        val options = galleryOptions(activity, maxPages)
        return try {
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender -> host.startScanner(sender) }
                .addOnFailureListener {
                    // The module is there but refused (no Play services, disabled):
                    // fall back to the picker + manual crop instead of a dead tap.
                    startFallback(activity)
                }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Gallery auto-crop scanner options: gallery import on, cropped JPEGs, page cap. */
    internal fun galleryOptions(
        activity: AppCompatActivity,
        maxPages: Int
    ): GmsDocumentScannerOptions {
        val cfg = galleryConfig(activity, maxPages)
        return GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(cfg.galleryImport)
            .setScannerMode(
                if (cfg.fullMode) GmsDocumentScannerOptions.SCANNER_MODE_FULL
                else GmsDocumentScannerOptions.SCANNER_MODE_BASE
            )
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setPageLimit(cfg.pageLimit)
            .build()
    }

    /** What gallery auto-crop asks for (testable without the ML Kit builder). */
    internal fun galleryConfig(activity: AppCompatActivity, maxPages: Int): ScanConfig =
        ScanConfig(
            galleryImport = true,
            pageLimit = maxPages.coerceIn(1, 100),
            fullMode = Prefs.autoEnhanceScan(activity),
            jpeg = true
        )
}
