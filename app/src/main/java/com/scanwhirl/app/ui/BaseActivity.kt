package com.scanwhirl.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs

/**
 * Applies the user's font-style theme overlay before the activity inflates.
 * All screens extend this so the Settings choice applies everywhere.
 */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        when (Prefs.fontStyle(this)) {
            Prefs.FONT_SERIF -> setTheme(R.style.Theme_ScanWhirl_Serif)
            Prefs.FONT_MONO -> setTheme(R.style.Theme_ScanWhirl_Mono)
        }
        super.onCreate(savedInstanceState)
    }
}
