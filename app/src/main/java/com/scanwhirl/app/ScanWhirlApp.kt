package com.scanwhirl.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.scanwhirl.app.store.ScanRepository

class ScanWhirlApp : Application() {
    lateinit var repository: ScanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Dark is the app's default look; change MODE_NIGHT_* here for a light default.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        repository = ScanRepository(this)
    }
}
