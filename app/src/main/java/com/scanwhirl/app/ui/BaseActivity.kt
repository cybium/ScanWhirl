package com.scanwhirl.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs

/**
 * Base activity: applies font overlays before inflation and gives every screen the same
 * plain ink background as Home (M08 — the star-field backdrop made Settings, the
 * document screen and the editors look like a different app).
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
        window.setBackgroundDrawableResource(R.color.rv_bg)
    }
}
