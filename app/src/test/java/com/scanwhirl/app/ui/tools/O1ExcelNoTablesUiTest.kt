package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrEngine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Review P2: "Tables only" with one readable table-less page and one unreadable page must not
 * claim only "no tables". It must name the page the app could not read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1ExcelNoTablesUiTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

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
        return condition()
    }

    @Test
    fun `tables only with a partial unread document names the unread pages`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Partly readable")
        repo.ocrStore.putPage(
            id, "page_000.jpg", "letter",
            OcrPage(1000, 1400, listOf(OcrLine("Dear sir", 60, 80, 420, 120)))
        )
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }

        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "to_excel")).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the setup screen must load", waitUntil {
                    activity.findViewById<View>(R.id.toolSetupRun)?.isShown == true
                })
                activity.findViewById<View>(R.id.toolSetupRun).performClick()
            }
            assertTrue("the no-tables sheet opens", waitUntil {
                val title = ShadowDialog.getLatestDialog()
                    ?.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
                title == app.getString(R.string.tools_excel_no_tables_title)
            })
            scenario.onActivity {
                val message = ShadowDialog.getLatestDialog()
                    ?.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
                assertTrue(
                    "the sheet must name the one unread page of two: '$message'",
                    message.contains("1") && message.contains("2") && message.contains("read", true)
                )
            }
        }
    }
}
