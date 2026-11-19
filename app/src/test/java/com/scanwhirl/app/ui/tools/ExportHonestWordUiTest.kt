package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.util.OcrEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * T37-EXPORT-HONEST: a Hindi document read with the English text language produced no text and
 * only "Read the text first". The Word setup screen must now name the setting and offer one tap
 * into it, not leave the person to find Settings alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportHonestWordUiTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        // ML Kit does not run in the JVM: fail recognition exactly like a missing text model, so
        // the export reaches the no-text path.
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Hindi page")
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @Test
    fun `to word with no recognised text offers the text language setting`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_word")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the setup screen must load the document", waitUntil {
                    activity.findViewById<View>(R.id.toolSetupRun)?.isShown == true
                })
                activity.findViewById<View>(R.id.toolSetupRun).performClick()

                assertTrue("the no-text sheet must appear", waitUntil {
                    val dialog = ShadowDialog.getLatestDialog()
                    dialog?.findViewById<TextView>(R.id.swSheetMessage)
                        ?.text?.toString()?.contains("Text language") == true
                })
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val message = dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                assertTrue("the sheet must say what to do: $message", message.contains("Hindi"))
                val primary = dialog.findViewById<TextView>(R.id.swSheetPrimary)
                assertEquals("Change text language", primary.text.toString())

                primary.performClick()
                val started = shadowOf(app).nextStartedActivity
                assertEquals(
                    "the button must open the text-language setting",
                    SettingsActivity::class.java.name, started?.component?.className
                )
            }
        }
    }
}
