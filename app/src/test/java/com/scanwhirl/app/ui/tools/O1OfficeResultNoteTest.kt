package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PptxWriter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * O1 defect 5: a PPTX result must only claim the recognised text is in speaker notes when the
 * deck actually contains notes. A page-picture-only deck must say there is no text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1OfficeResultNoteTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    private fun noteOf(activity: ToolResultActivity): String =
        activity.findViewById<TextView>(R.id.toolResultRasterNote)?.text?.toString().orEmpty()

    private fun launch(deck: File, check: (ToolResultActivity) -> Unit) {
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "to_powerpoint", files = listOf(deck),
                mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
        ).use { scenario -> scenario.onActivity { activity -> check(activity) } }
    }

    @Test
    fun `a pptx with no recognised text does not claim there are speaker notes`() {
        val jpeg = ImportFixtures.image(width = 120, height = 160)
        val deck = File(app.cacheDir, "o1_no_notes_${System.nanoTime()}.pptx")
        PptxWriter.write(deck, 1, true, { jpeg }) { null }

        launch(deck) { activity ->
            assertTrue(
                "the result must say the slides have no text: '${noteOf(activity)}'",
                waitUntil { noteOf(activity).contains("no text", ignoreCase = true) }
            )
        }
    }

    @Test
    fun `a pptx with recognised text still says the notes hold it`() {
        val jpeg = ImportFixtures.image(width = 120, height = 160)
        val deck = File(app.cacheDir, "o1_notes_${System.nanoTime()}.pptx")
        PptxWriter.write(deck, 1, true, { jpeg }) { "recognized line" }

        launch(deck) { activity ->
            assertTrue(
                "the result must still name the speaker notes: '${noteOf(activity)}'",
                waitUntil { noteOf(activity).contains("speaker notes", ignoreCase = true) }
            )
        }
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
