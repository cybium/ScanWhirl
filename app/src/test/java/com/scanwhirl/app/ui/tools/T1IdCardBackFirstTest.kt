package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream

/**
 * T1 capture data safety: a back captured before any front must never be discarded by the
 * layout sheet's dismiss listener; the person is asked for the front instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1IdCardBackFirstTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        app.getSharedPreferences("scanwhirl_id_card", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(color)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun latestTitle(): String = requireNotNull(ShadowDialog.getLatestDialog())
        .findViewById<TextView>(R.id.swSheetTitle).text.toString()

    @Test
    fun `capturing the back first prompts for the front and never finishes`() {
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { it.acceptCapture(IdCardActivity.Side.BACK, jpeg(Color.BLUE)) }
            idle()
            scenario.onActivity { activity ->
                assertEquals("the layout sheet must require the front",
                    app.getString(R.string.tools_id_front_title), latestTitle())
                // Pressing the primary action must not throw the captured back away.
                requireNotNull(ShadowDialog.getLatestDialog())
                    .findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()
                assertFalse("the back capture must not be discarded", activity.isFinishing)
            }
        }
    }
}
