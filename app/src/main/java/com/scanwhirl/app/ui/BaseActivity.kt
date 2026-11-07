package com.scanwhirl.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs

/**
 * Base activity for the Orbital design system.
 * Sets the window background to [SpaceBackgroundDrawable] so all screens
 * share a continuous cosmic backdrop, and applies font overlays before inflation.
 */
abstract class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        when (Prefs.fontStyle(this)) {
            Prefs.FONT_SERIF -> setTheme(R.style.Theme_ScanWhirl_Serif)
            Prefs.FONT_MONO -> setTheme(R.style.Theme_ScanWhirl_Mono)
        }
        super.onCreate(savedInstanceState)
        // Edge-to-edge on every Android version; screens then pad themselves via
        // View.applySystemBarsPadding() so the result is identical everywhere.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(SpaceBackgroundDrawable())
    }
}
