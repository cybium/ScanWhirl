package com.scanwhirl.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Small persisted app settings (theme, font, language, viewer, sort). */
object Prefs {
    private const val FILE = "scanwhirl_prefs"

    private const val KEY_THEME = "theme_mode"
    private const val KEY_FONT = "font_style"
    private const val KEY_VIEWER = "default_viewer"
    private const val KEY_SORT = "sort_mode"
    private const val KEY_LOCALE = "locale_tag"
    private const val KEY_OCR_LANGUAGE = "ocr_language"
    private const val KEY_GREETING = "show_greeting"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"
    private const val KEY_WALLPAPER_ON = "wallpaper_on"
    private const val KEY_PICKED = "picked_pdfs"
    private const val KEY_INDEX_BACKFILL_DONE = "index_backfill_done"

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"

    const val FONT_DEFAULT = "default"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"

    const val VIEWER_IN_APP = "in_app"
    const val VIEWER_SYSTEM = "system"

    const val SORT_DATE = "date"
    const val SORT_MODIFIED = "modified"
    const val SORT_NAME = "name"
    const val SORT_CATEGORY = "category"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(c: Context): String = sp(c).getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    fun setThemeMode(c: Context, v: String) = sp(c).edit().putString(KEY_THEME, v).apply()

    fun nightMode(value: String): Int = when (value) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun fontStyle(c: Context): String = sp(c).getString(KEY_FONT, FONT_DEFAULT) ?: FONT_DEFAULT
    fun setFontStyle(c: Context, v: String) = sp(c).edit().putString(KEY_FONT, v).apply()

    fun defaultViewer(c: Context): String =
        sp(c).getString(KEY_VIEWER, VIEWER_IN_APP) ?: VIEWER_IN_APP
    fun setDefaultViewer(c: Context, v: String) = sp(c).edit().putString(KEY_VIEWER, v).apply()

    fun sortMode(c: Context): String = sp(c).getString(KEY_SORT, SORT_DATE) ?: SORT_DATE
    fun setSortMode(c: Context, v: String) = sp(c).edit().putString(KEY_SORT, v).apply()

    fun localeTag(c: Context): String = sp(c).getString(KEY_LOCALE, "") ?: ""
    fun setLocaleTag(c: Context, v: String) = sp(c).edit().putString(KEY_LOCALE, v).apply()

    /** Home status line (count · size). Opt-in since Home slice 2: no greeting noise by default. */
    // M08: Status line and Wallpaper are retired (Settings no longer offers them), so an
    // earlier "on" can't leave someone stuck with a feature they can no longer switch off.
    @Suppress("UNUSED_PARAMETER")
    fun showGreeting(c: Context): Boolean = false
    fun setShowGreeting(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GREETING, v).apply()

    fun showWallpaper(c: Context): Boolean = sp(c).getBoolean(KEY_WALLPAPER_ON, false)
    fun setShowWallpaper(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_WALLPAPER_ON, v).apply()
    fun wallpaperUri(c: Context): String = sp(c).getString(KEY_WALLPAPER_URI, "") ?: ""
    fun setWallpaperUri(c: Context, v: String) = sp(c).edit().putString(KEY_WALLPAPER_URI, v).apply()

    /** Auto-enhance on scan: ON uses Google's SCANNER_MODE_FULL, OFF uses BASE. */
    private const val KEY_AUTO_ENHANCE = "auto_enhance_scan"

    // M08: default ON (Google FULL) — chosen by the owner after a same-page device comparison.
    // An explicit earlier choice of OFF is kept.
    fun autoEnhanceScan(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_ENHANCE, true)
    fun setAutoEnhanceScan(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_ENHANCE, v).apply()

    fun pickedPdfs(c: Context): Set<String> =
        sp(c).getStringSet(KEY_PICKED, emptySet()) ?: emptySet()

    fun addPickedPdf(c: Context, uri: String) {
        sp(c).edit().putStringSet(KEY_PICKED, pickedPdfs(c) + uri).apply()
    }

    /** True once the one-time library OCR backfill has been enqueued. Default false. */
    fun indexBackfillDone(c: Context): Boolean =
        sp(c).getBoolean(KEY_INDEX_BACKFILL_DONE, false)
    fun setIndexBackfillDone(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_INDEX_BACKFILL_DONE, v).apply()

    private const val KEY_VAULT_PIN = "vault_pin_sha256"

    private const val KEY_VAULT_SETUP_CONFIRM_PENDING = "vault_setup_confirm_pending"

    /**
     * Non-secret marker (T18, POL-002): a recovery code exists on this phone whose
     * four-character check has not passed yet. Written synchronously with provisioning, so a
     * kill right after setup cannot lose it; cleared only when the check passes. Never holds
     * the code, a passcode or a file name.
     */
    fun vaultSetupConfirmPending(c: Context): Boolean =
        sp(c).getBoolean(KEY_VAULT_SETUP_CONFIRM_PENDING, false)

    /**
     * Returns false when the marker could not be persisted. Provisioning must then fail closed:
     * a Vault whose pending check cannot be recorded is never published (review MUST-FIX 2).
     */
    fun setVaultSetupConfirmPending(c: Context, pending: Boolean): Boolean =
        sp(c).edit().putBoolean(KEY_VAULT_SETUP_CONFIRM_PENDING, pending).commit()

    private const val KEY_AUTO_COPY = "auto_copy_to_documents"

    /** T4.7 (N10): also copy the finished PDF into Documents/ScanWhirl. Off by default. */
    fun autoCopyToDocuments(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_COPY, false)
    fun setAutoCopyToDocuments(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_AUTO_COPY, v).apply()

    private const val KEY_MAKE_SEARCHABLE = "make_searchable"

    /** Make PDFs searchable: embed a hidden OCR text layer. Default ON. */
    fun makeSearchable(c: Context): Boolean = sp(c).getBoolean(KEY_MAKE_SEARCHABLE, true)
    fun setMakeSearchable(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_MAKE_SEARCHABLE, v).apply()

    /** OCR language: "en" (Latin, default), "en+hi" (both recognizers) or "hi" (Devanagari). */
    fun ocrLanguage(c: Context): String = sp(c).getString(KEY_OCR_LANGUAGE, "en") ?: "en"
    fun setOcrLanguage(c: Context, value: String) =
        sp(c).edit().putString(KEY_OCR_LANGUAGE, value).apply()

    fun hasVaultPin(c: Context): Boolean =
        !sp(c).getString(KEY_VAULT_PIN, null).isNullOrBlank()

    /**
     * Stores the legacy 4-digit PIN as a salted PBKDF2-HMAC-SHA256 hash
     * (`pbkdf2$<iterations>$<saltB64>$<hashB64>`), never the PIN itself and never plain SHA-256
     * (a 4-digit PIN is only 10,000 guesses for a bare digest). Security audit finding M-3.
     */
    fun setVaultPin(c: Context, pin: String) = savePreparedVaultPin(c, prepareVaultPin(pin))

    internal class PreparedVaultPin internal constructor(internal val hash: String)

    internal fun prepareVaultPin(pin: String): PreparedVaultPin {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be 4 digits" }
        return PreparedVaultPin(VaultPinHash.create(pin))
    }

    private val vaultPinLock = Any()

    internal fun savePreparedVaultPin(c: Context, prepared: PreparedVaultPin) {
        synchronized(vaultPinLock) {
            sp(c).edit().putString(KEY_VAULT_PIN, prepared.hash).apply()
        }
    }

    /**
     * Verifies the legacy PIN. Older installs carry `sha256("scanwhirl_vault:$pin")`; a correct
     * PIN against that format is upgraded to the salted hash in place, so the weak digest does
     * not survive the first successful check.
     */
    fun verifyVaultPin(c: Context, pin: String): Boolean {
        val checked = prepareVaultPinCheck(c, pin) ?: return false
        return acceptVaultPinCheck(c, checked)
    }

    internal class VaultPinCheck internal constructor(
        internal val expected: String,
        internal val upgrade: String?
    )

    /** Derive and prepare any upgrade without writing until the caller accepts the result. */
    internal fun prepareVaultPinCheck(c: Context, pin: String): VaultPinCheck? {
        val stored = sp(c).getString(KEY_VAULT_PIN, null) ?: return null
        if (!VaultPinHash.verify(stored, pin)) return null
        return VaultPinCheck(stored, if (VaultPinHash.isLegacy(stored)) VaultPinHash.create(pin) else null)
    }

    internal fun acceptVaultPinCheck(c: Context, checked: VaultPinCheck): Boolean = synchronized(vaultPinLock) {
        val prefs = sp(c)
        // A concurrent PIN change wins over a stale successful check or SHA-256 upgrade.
        if (prefs.getString(KEY_VAULT_PIN, null) != checked.expected) return@synchronized false
        checked.upgrade?.let { prefs.edit().putString(KEY_VAULT_PIN, it).apply() }
        true
    }

    internal fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- v2 Library sort (03_tools_hub) ----------------------------------

    private const val KEY_LIB_SORT = "library_sort"
    const val LIB_NEWEST = "newest"
    const val LIB_OLDEST = "oldest"
    const val LIB_NAME_ASC = "name_asc"
    const val LIB_NAME_DESC = "name_desc"
    const val LIB_SIZE = "size"

    fun librarySort(c: Context): String = sp(c).getString(KEY_LIB_SORT, LIB_NEWEST) ?: LIB_NEWEST
    fun setLibrarySort(c: Context, v: String) = sp(c).edit().putString(KEY_LIB_SORT, v).apply()

    // ---- gallery import: auto-crop or keep as-is (01_home_A) ----------------

    private const val KEY_GALLERY_CROP = "gallery_auto_crop"
    fun galleryAutoCrop(c: Context): Boolean = sp(c).getBoolean(KEY_GALLERY_CROP, true)
    fun setGalleryAutoCrop(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GALLERY_CROP, v).apply()

    // ---- documents: block screenshots (SECURITY_AUDIT 2026-11-15) ------------

    private const val KEY_BLOCK_DOC_SCREENSHOTS = "block_doc_screenshots"

    /**
     * Block screenshots/screen recording of **ordinary** documents. Protected
     * (locked) and Vault documents are blocked regardless of this setting (see
     * [SecureView.applyForDocument]); ordinary scans default to screenshot-friendly.
     */
    fun blockDocScreenshots(c: Context): Boolean =
        sp(c).getBoolean(KEY_BLOCK_DOC_SCREENSHOTS, false)

    fun setBlockDocScreenshots(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_BLOCK_DOC_SCREENSHOTS, v).apply()

    // ---- Pro accent theme (Cyan default, Gold for Pro) -----------------------

    private const val KEY_ACCENT = "accent_theme"
    const val ACCENT_CYAN = "cyan"
    const val ACCENT_GOLD = "gold"

    fun accent(c: Context): String = sp(c).getString(KEY_ACCENT, ACCENT_CYAN) ?: ACCENT_CYAN
    fun setAccent(c: Context, v: String) = sp(c).edit().putString(KEY_ACCENT, v).apply()

    // ---- Home quick tools (customisable, max two rows) -----------------------

    private const val KEY_QUICK_TOOLS = "quick_tools"
    const val MAX_QUICK_TOOLS = 12
    // Council §7: the newcomer-safe default row is five tiles — Merge, Compress, Sign,
    // Protect and OCR (`read_text`). `to_word` is no longer a default; it stays reachable
    // in the Tools hub and can be added back with Customize.
    val DEFAULT_QUICK_TOOLS = listOf("merge", "compress", "sign", "protect", "read_text")

    /** The tool ids on Home; defaults to the council's five, capped at two rows. */
    fun quickTools(c: Context): List<String> {
        val stored = sp(c).getString(KEY_QUICK_TOOLS, null)
            ?: return DEFAULT_QUICK_TOOLS
        val ids = stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // A tool that is not READY (Coming soon) can never be a quick tool: an
        // older saved set is filtered here so it disappears on the next launch.
        val ready = ids.filter { isReady(it) }.distinct().take(MAX_QUICK_TOOLS)
        return ready.ifEmpty { DEFAULT_QUICK_TOOLS.filter { isReady(it) } }
    }

    fun setQuickTools(c: Context, ids: List<String>) {
        val clean = ids.distinct().filter { isReady(it) }.take(MAX_QUICK_TOOLS)
        sp(c).edit().putString(KEY_QUICK_TOOLS, clean.joinToString(",")).apply()
    }

    private fun isReady(id: String): Boolean =
        runCatching {
            com.scanwhirl.app.ui.kit.ToolRegistry.byId(id)?.status ==
                com.scanwhirl.app.ui.kit.ToolStatus.READY
        }.getOrDefault(false)

    fun resetQuickTools(c: Context) = sp(c).edit().remove(KEY_QUICK_TOOLS).apply()

    // ---- Recent tools on the Tools tab (T1, owner 2026-11-20) ----------------
    //
    // The last few tools this person actually launched, most recent first, so the everyday
    // work (Compress, OCR, Backup) is one tap from the top of the Tools tab. The list is a
    // device habit, not a document and not a setting to carry: it is deliberately NOT in the
    // Full Backup settings allowlist (FullBackup.portableSettingKeys), and an id this build no
    // longer ships is dropped on read instead of rendering a dead row.

    /** Public so the backup allowlist test can name the key without a private mirror. */
    const val KEY_RECENT_TOOLS = "recent_tools"

    const val MAX_RECENT_TOOLS = 6

    /** Launched tools, most recent first; unknown or not-READY ids are ignored. */
    fun recentTools(c: Context): List<String> {
        val stored = sp(c).getString(KEY_RECENT_TOOLS, null) ?: return emptyList()
        return stored.split(',').map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { isReady(it) }
            .distinct()
            .take(MAX_RECENT_TOOLS)
    }

    /** Moves [id] to the front (an existing entry moves, it is never repeated). */
    fun recordRecentTool(c: Context, id: String) {
        if (!isReady(id)) return
        val next = (listOf(id) + recentTools(c).filter { it != id }).take(MAX_RECENT_TOOLS)
        sp(c).edit().putString(KEY_RECENT_TOOLS, next.joinToString(",")).apply()
    }

    fun clearRecentTools(c: Context) = sp(c).edit().remove(KEY_RECENT_TOOLS).apply()
}
