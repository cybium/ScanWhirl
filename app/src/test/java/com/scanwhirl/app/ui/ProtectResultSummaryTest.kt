package com.scanwhirl.app.ui

import android.app.Application
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Protect result screen shows a summary, not a meaningless Before 0 B -> After 15 KB. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ProtectResultSummaryTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a protect summary replaces the before and after sizes`() {
        val summary = "Book 14 Nov\nProtected with AES-256"
        val intent = ExportResultActivity.intent(
            context = app,
            outputPath = java.io.File(app.cacheDir, "protected.pdf").path,
            previewPath = null,
            mime = "application/pdf",
            displayName = "Book 14 Nov.pdf",
            beforeBytes = 0L,
            afterBytes = 15_000L,
            width = 0,
            height = 0,
            title = "Protect PDF",
            warning = null,
            docId = null,
            summary = summary
        )
        val activity = Robolectric.buildActivity(ExportResultActivity::class.java, intent).setup().get()
        val stats = activity.findViewById<TextView>(R.id.resultStats)
        assertEquals(summary, stats.text.toString())
        assertFalse(stats.text.toString().contains("Before"))
    }
}
