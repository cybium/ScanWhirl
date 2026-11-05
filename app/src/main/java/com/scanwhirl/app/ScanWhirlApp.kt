package com.scanwhirl.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Prefs

class ScanWhirlApp : Application() {
    lateinit var repository: ScanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Theme + per-app language come from Settings (dark is the default).
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(Prefs.themeMode(this)))
        val tag = Prefs.localeTag(this)
        if (tag.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
        repository = ScanRepository(this)
    }
}
