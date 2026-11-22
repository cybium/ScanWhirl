package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: 03_tools_hub — the result screen names its output under the thumbnail and shows the page
 * count; the Before/After size line belongs to Compress/Resize only, not every tool.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ResultNameTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the result names the output and its page count`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "isz14 - Merged")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val name = activity.findViewById<TextView>(R.id.toolResultName)
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary)
                assertTrue("saved line names the output: ${name.text}", name.text.toString().contains("isz14 - Merged"))
                assertTrue("one measured fact: ${summary.text}", summary.text.toString().contains("2 pages"))
            }
        }
    }

    @Test
    fun `compress keeps the actual size line`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Contract - Compressed")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val summary = activity.findViewById<TextView>(R.id.toolResultSummary)
                assertTrue("compress shows a size: ${summary.text}",
                    summary.text.toString().contains("KB") || summary.text.toString().contains("MB") ||
                        summary.text.toString().contains(" B"))
            }
        }
    }

    @Test
    fun `a file result names the file`() {
        val file = java.io.File(app.cacheDir, "documents/Invoice - Pages 1-2.xlsx").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(
                app, "to_excel", files = listOf(file),
                mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val name = activity.findViewById<TextView>(R.id.toolResultName)
                assertTrue("file name: ${name.text}", name.text.toString().contains(file.name))
            }
        }
    }
}
