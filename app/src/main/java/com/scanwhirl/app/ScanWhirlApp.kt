package com.scanwhirl.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.PrivacySweep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // Cold-start repair runs once, off the main thread, before any screen trusts the disk:
        // interrupted permanent deletes, dead import staging, half-finished Replace publishes and
        // the Vault's own recovery. Skipped on Robolectric, where tests drive it explicitly.
        if (Build.FINGERPRINT != "robolectric") {
            appScope.launch(Dispatchers.IO) { startupRecovery() }
        }
    }

    /**
     * Everything a cold start must finish on disk, in order. Blocking; call it on an IO thread.
     * Exposed so a test can run exactly the work the app runs at startup.
     */
    internal fun startupRecovery() {
        runCatching { repository.recoverTrash() }
        runCatching { repository.recoverImports() }
        runCatching { repository.recoverOperations() }
        // B5: finish an "Add to Vault" that died after the encrypted entry was published, purge a
        // Trash copy of anything now in the Vault, and drop abandoned staging folders.
        runCatching { VaultStore(this, repository).recover() }
    }
}
