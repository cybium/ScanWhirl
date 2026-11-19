package com.scanwhirl.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.SaveFormat
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.PrivacySweep
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
        fun from(context: Context): ScanWhirlApp =
            context.applicationContext as ScanWhirlApp
    }

    override fun onCreate() {
        super.onCreate()
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
        // Security audit: nothing decrypted or shared survives a cold start. Runs before any
        // screen can read the cache, and never touches the library, the Vault or user exports.
        runCatching { PrivacySweep.run(this) }
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
