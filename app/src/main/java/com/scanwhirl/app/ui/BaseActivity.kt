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

    /**
     * v2 UI: 01_home_A — the one bridge for [com.scanwhirl.app.tools.launchGalleryCrop]:
     * every BaseActivity screen can open the gallery auto-crop without registering
     * its own activity result.
     */
    private val galleryCropLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = if (result.resultCode == RESULT_OK && result.data != null) {
            com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
                .fromActivityResultIntent(result.data)
                ?.pages.orEmpty().mapNotNull { it.imageUri }
        } else {
            emptyList()
        }
        if (uris.isNotEmpty()) {
            com.scanwhirl.app.tools.GalleryCrop.deliver(this, uris)
        } else {
            com.scanwhirl.app.tools.GalleryCrop.cancel(this)
        }
    }

    private val manualCropPhotos = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        com.scanwhirl.app.tools.GalleryCrop.deliverFallback(this, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        VaultAppLifecycle.install(application)
        when (Prefs.fontStyle(this)) {
            Prefs.FONT_SERIF -> setTheme(R.style.Theme_ScanWhirl_Serif)
            Prefs.FONT_MONO -> setTheme(R.style.Theme_ScanWhirl_Mono)
        }
        super.onCreate(savedInstanceState)
        com.scanwhirl.app.tools.GalleryCrop.register(
            this,
            com.scanwhirl.app.tools.GalleryCrop.Host(
                startScanner = { sender ->
                    galleryCropLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                    )
                },
                pickPhotos = { manualCropPhotos.launch("image/*") }
            )
        )
        // Edge-to-edge on every Android version; screens then pad themselves via
        // View.applySystemBarsPadding() so the result is identical everywhere.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(R.color.rv_bg)
    }

    /** v2 UI: 74_motion — every drill-in uses the kit screen transition. */
    override fun startActivity(intent: android.content.Intent) {
        super.startActivity(intent)
        com.scanwhirl.app.ui.kit.SwMotion.drillIn(this)
    }

    override fun onDestroy() {
        // C04: the gallery-crop bridge is process-wide; a destroyed screen must not stay in it
        // (its Host/Pending lambdas capture this activity).
        com.scanwhirl.app.tools.GalleryCrop.unregister(this)
        super.onDestroy()
    }
}
