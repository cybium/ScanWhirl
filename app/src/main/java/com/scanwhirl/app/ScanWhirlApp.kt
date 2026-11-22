package com.scanwhirl.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.SaveFormat
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.PrivacySweep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScanWhirlApp : Application() {

    lateinit var repository: ScanRepository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Module-visible setter so tests can replace it with a fake-recognizer instance. */
    lateinit var indexer: OcrIndexer
        @androidx.annotation.VisibleForTesting internal set

    companion object {
        /** Only our own screens are locked; Google's ML Kit scanner runs in our process but is not ours. */
        private const val OUR_ACTIVITY_PREFIX = "com.scanwhirl.app."
        /** Book mode capture is the one phone activity allowed to rotate. */
        private const val BOOK_PAGES_ACTIVITY = "com.scanwhirl.app.ui.book.BookPagesActivity"
        /** Android tablet breakpoint: smallestScreenWidthDp >= 600 keeps the platform default. */
        private const val TABLET_SW_DP = 600

        fun from(context: Context): ScanWhirlApp =
            context.applicationContext as ScanWhirlApp
    }

    override fun onCreate() {
        super.onCreate()
        // ORI (owner/lead decision 22 Nov): phones are portrait-only; landscape on short phones
        // broke the scan/review loop. One global policy in the Application instead of a manifest
        // entry on every activity. It only sets requestedOrientation; it never finishes or
        // suppresses recreation, so language/theme changes and process death still save state.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // API 29+: set before Activity.onCreate, so the first frame is already portrait
            // (no landscape flash). Also works under Robolectric, which dispatches pre-create.
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyOrientationPolicy(activity)
            }

            // API < 29 has no pre-create callback; this covers those devices. Setting the same
            // value again on newer devices is a harmless no-op.
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyOrientationPolicy(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Theme + per-app language come from Settings (dark is the default).
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(Prefs.themeMode(this)))
        val tag = Prefs.localeTag(this)
        if (tag.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
        repository = ScanRepository(this)
        // The gold accent/icon is not phone-verified yet: the launcher always
        // resolves to the cyan alias regardless of an older saved preference.
        runCatching {
            // Only touch the launcher when the gold alias is actually on (no work on normal starts).
            val alias = com.scanwhirl.app.ui.kit.SwTheme.enabledLauncherAlias(this)
            if (alias != null && alias.endsWith("Cyan").not()) {
                com.scanwhirl.app.ui.kit.SwTheme.applyLauncherIcon(this, false)
            }
        }
        // S2 item 2: the privacy sweep is disk I/O. It used to run here, on the main thread and
        // before the first frame. It now runs on the existing IO startup path and storage recovery
        // waits for it, so a screen still cannot read a plaintext cache before it is swept.
        // Robolectric tests seed cache fixtures right after onCreate; an asynchronous sweep would
        // delete them mid-test. The sweep itself is covered directly (PrivacySweepTest,
        // S2StampSweepTest), so skip the startup launch in that build as we do for other startup
        // background work, and release the recovery ordering gate immediately.
        if (Build.FINGERPRINT != "robolectric") {
            appScope.launch { runStartupSweep() }
        } else {
            privacySweepDone.complete(Unit)
        }
        indexer = OcrIndexer(
            repository,
            appScope,
            autoRun = Build.FINGERPRINT != "robolectric",
            afterIndexed = { id -> repository.refreshPdfAfterIndex(id) }
        )
        // T41-SEARCH-INDEX: the shipped Home (ShellActivity/LibraryScreen) never asks the
        // indexer to resume, so a document marked pending by an older build, by a tool output
        // that could not reuse its text, or by a process death stayed forever unsearchable
        // ("3 of 15 documents answer a word search"). The legacy Home did this in its own
        // refreshList; the application must do it once per cold start instead of a screen.
        // Robolectric builds keep it off (tests call [LibraryIndexBackfill.run] directly).
        if (Build.FINGERPRINT != "robolectric") startLibraryIndexBackfill()
    }

    /**
     * ORI: lock our screens to portrait on phones only.
     *
     * - Activities outside our Java package (notably Google's ML Kit scanner) are untouched.
     * - Book mode capture is the single allow-listed phone exception.
     * - Tablets (smallestScreenWidthDp >= 600) keep whatever the platform/manifest says
     *   (normally SCREEN_ORIENTATION_UNSPECIFIED, i.e. the user's device setting).
     * - Android 16+ ignores orientation locks on large screens, which is fine.
     *
     * The class name (not the application id) is checked so the policy also holds in the
     * com.scanwhirl.app.test / .demo builds, whose package names differ but whose classes do not.
     */
    private fun applyOrientationPolicy(activity: Activity) {
        val className = activity.javaClass.name
        if (!className.startsWith(OUR_ACTIVITY_PREFIX)) return
        if (className == BOOK_PAGES_ACTIVITY) return
        if (resources.configuration.smallestScreenWidthDp >= TABLET_SW_DP) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /**
     * PERF item 5 safety net: on memory pressure, drop the recognizers a multi-page
     * job may still be caching. Off the main thread; the next job creates fresh ones.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch { OcrEngine.closeRecognizers() }
        }
    }

    /** Completed once the cold-start privacy sweep has finished (success or isolated failure). */
    private val privacySweepDone = CompletableDeferred<Unit>()

    /**
     * S2 item 2: runs the cold-start privacy sweep off the main thread. [threadProbe] is a test
     * seam receiving the thread the sweep actually ran on. The sweep never touches the library,
     * the Vault or user exports; an isolated failure is logged by class only and still releases
     * [privacySweepDone] so startup recovery cannot hang behind it.
     */
    internal suspend fun runStartupSweep(threadProbe: ((String) -> Unit)? = null) =
        withContext(Dispatchers.IO) {
            threadProbe?.invoke(Thread.currentThread().name)
            try {
                PrivacySweep.run(this@ScanWhirlApp)
            } catch (t: Throwable) {
                android.util.Log.w("ScanWhirl", "privacy sweep failed (${t.javaClass.simpleName})")
            } finally {
                privacySweepDone.complete(Unit)
            }
        }

    private val storageRecoveryStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * F1 (hunt H1-1): true once this process has finished its startup storage recovery. The
     * launcher screen waits for it and reloads the Library so a rolled-forward document appears.
     */
    val storageRecovered = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** G1 review: the pass finished (safe to load the Library), even if some folder step failed. */
    val storageRecoveryFinished = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** G1 review: true when a core recovery step failed; startup must not claim a clean pass. */
    val storageRecoveryIncomplete = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Sol re-review: startup isolates ordinary IO failures but never swallows cancellation or an
     * Error. Only the exception class is logged; no user data or message reaches the log.
     */
    private suspend inline fun startupStep(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Lead decision: an Error must not kill startup or stop the remaining steps. Sol
            // re-review: an outer startup failure is just as incomplete as an inner one.
            com.scanwhirl.app.store.ScanRepository.recoveryIncomplete = true
            android.util.Log.w("ScanWhirl", "storage recovery step $name failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * F1 (hunt H1-1): the storage recovery the legacy Home ran on every start (reclaim abandoned
     * saves, finish interrupted permanent deletes and expire Trash, drop dead import staging,
     * roll an interrupted Replace forward) never ran from the shipped launcher. It runs once per
     * process, off the main thread, in the legacy order. Every step is idempotent, so a legacy
     * Home that also runs them is harmless. Safe to call from every launcher onCreate.
     */
    internal fun startStorageRecovery() {
        if (!storageRecoveryStarted.compareAndSet(false, true)) return
        // One reset for the whole pass: every step ORs its failures into this shared flag.
        com.scanwhirl.app.store.ScanRepository.beginStartupRecoveryPass()
        appScope.launch(kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                // Backstop only: every step isolates its own failures. Class only, no user data.
                android.util.Log.w("ScanWhirl", "storage recovery backstop (${t.javaClass.simpleName})")
                com.scanwhirl.app.store.ScanRepository.recoveryIncomplete = true
                storageRecoveryIncomplete.value = true
                storageRecoveryFinished.value = true
            }) {
            try {
                // S2 item 2: the privacy sweep started in onCreate owns the cache. Recovery (and
                // the launcher's Library load behind storageRecoveryFinished) must not read it
                // first. Await the sweep before any recovery step; it never hangs (finally completes).
                privacySweepDone.await()
                // Restore interrupted discard staging BEFORE ordinary orphan-draft cleanup.
                startupStep("discard") {
                    com.scanwhirl.app.store.ImportDiscardStore(this@ScanWhirlApp, repository).recover()
                        .forEach { com.scanwhirl.app.ui.DiscardActions.scheduleExpiry(this@ScanWhirlApp, it) }
                    // Do NOT re-enqueue pending OCR here: enqueue() rewrites each ocr_pending
                    // marker, which is an observable file change on every cold start. Startup
                    // indexing is already owned by startLibraryIndexBackfill(); an Undo requeues
                    // its own restored id via DiscardActions -> TrashActions.queueIndexing.
                }
                startupStep("abandoned-saves") { repository.reapAbandonedSaves() }
                startupStep("trash") { repository.recoverTrash() }
                startupStep("imports") { repository.recoverImports() }
                startupStep("operations") { repository.recoverOperationsSuspend() }
                // SEC1 follow-up: a Vault add that could not purge its plaintext
                // original leaves a durable .adding marker. Finish that purge here,
                // off the main thread and after PrivacySweep has run in onCreate, so
                // "removed next start" is true even when Vault is never opened.
                // recover() needs no key and is safe while the Vault is locked.
                startupStep("vault-purge") {
                    com.scanwhirl.app.store.vault.VaultStore(this@ScanWhirlApp, repository).recover()
                }
            } finally {
                val incomplete = com.scanwhirl.app.store.ScanRepository.recoveryIncomplete
                storageRecoveryIncomplete.value = incomplete
                // Never claim a fully successful pass when a core step failed, but always release
                // the launcher's wait: folder work that failed is kept on disk for the next start.
                storageRecovered.value = !incomplete
                storageRecoveryFinished.value = true
            }
        }
    }

    /**
     * Starts the bounded library index backfill once per process. Runs on a background
     * dispatcher, never on the main thread, and is safe to call more than once.
     */
    internal fun startLibraryIndexBackfill() {
        appScope.launch {
            runCatching { LibraryIndexBackfill.run(this@ScanWhirlApp, repository, indexer) }
        }
    }
}

/**
 * T41-SEARCH-INDEX · makes documents recognised by an older build (or by any save) findable
 * by the Home word search again.
 *
 * Two separated jobs, both off the main thread:
 *  1. the once-per-install sweep: mark every published library document that has no `ocr.txt`
 *     yet as pending (bound [MAX_NEW_MARKS_PER_PASS] documents per launch, so a first start
 *     with a large library cannot queue an unbounded recognition storm; the rest are marked on
 *     later launches because the completion flag is only written after a full pass);
 *  2. resume the pending queue, including markers written before this build existed.
 *
 * It never re-runs recognition for stored text (the indexer skips stored page keys), never
 * changes the storage format, and never touches Vault (separate folder, `vault.flag` legacy
 * PIN documents) or password-locked documents (`locked.meta`): their pages are never read.
 * A battery-saver launch does nothing and leaves the sweep due, as the old Home did.
 */
internal object LibraryIndexBackfill {

    /** Published documents marked for recognition in one launch. */
    internal const val MAX_NEW_MARKS_PER_PASS = 200

    /** Documents inspected per launch; a listing guard, far above a realistic library. */
    private const val MAX_SCANNED_DIRS = 5_000

    private const val META_FILE = "meta.txt"
    private const val TEXT_FILE = "ocr.txt"
    private const val PENDING_FILE = "ocr_pending"
    private const val VAULT_MARKER = "vault.flag"

    /**
     * Runs one startup pass and returns how many documents were newly marked for recognition.
     * [threadProbe] receives the thread running the pass (test seam); [powerSave] and
     * [maxNewMarks] exist so tests can drive the sweep deterministically.
     */
    internal suspend fun run(
        app: Context,
        repo: ScanRepository,
        indexer: OcrIndexer,
        maxNewMarks: Int = MAX_NEW_MARKS_PER_PASS,
        powerSave: () -> Boolean = { isPowerSaveMode(app) },
        threadProbe: ((String) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        threadProbe?.invoke(Thread.currentThread().name)
        // Same policy as the old Home: a battery-saver start does no recognition work and
        // leaves the once-per-install sweep due.
        if (powerSave()) return@withContext 0

        var marked = 0
        if (!Prefs.indexBackfillDone(app)) {
            val missing = unreadDocuments(repo)
            missing.take(maxNewMarks).forEach { id ->
                repo.ocrStore.markPending(id)
                marked++
            }
            // The queue is now small enough to be resumed on later launches: claim the sweep.
            if (missing.size <= maxNewMarks) Prefs.setIndexBackfillDone(app, true)
        }
        indexer.resumePending()
        marked
    }

    /**
     * Newest first, only documents that can be shown in the library, whose text was never
     * stored, and that are not queued already. Read markers (not
     * [ScanRepository.loadDocument]) so a large library costs one `exists` check per
     * directory: no metadata parse, no page listing, no OCR read.
     */
    private fun unreadDocuments(repo: ScanRepository): List<String> =
        repo.docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_SCANNED_DIRS)
            ?.filter { dir ->
                dir.isDirectory &&
                    File(dir, META_FILE).isFile &&
                    !File(dir, TEXT_FILE).exists() &&
                    !File(dir, PENDING_FILE).exists() &&
                    !File(dir, VAULT_MARKER).exists() &&
                    !File(dir, PdfProtection.LOCK_FILE).exists() &&
                    !SaveFormat.isIncomplete(dir)
            }
            ?.map { it.name }
            ?: emptyList()

    private fun isPowerSaveMode(app: Context): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
}
