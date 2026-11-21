package com.scanwhirl.app.ui

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/** F4: hunt H5-5 (rotation inside the page delete Undo window) and H5-3 (cancellation shown as failure). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class F4SmallFixesTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun forceLayout(rv: androidx.recyclerview.widget.RecyclerView) {
        if (rv.childCount == 0) {
            rv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            )
            rv.layout(0, 0, 1080, 1920)
        }
    }

    @Test
    fun `H5-5 rotating inside the delete Undo window keeps the unsaved page`() {
        val dir = File(app.cacheDir, "stage_${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(dir, "page_001.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 300)) }
        File(dir, "page_002.jpg").writeBytes(ImportFixtures.image(width = 200, height = 300))
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.stagingIntent(app, dir.path, 0)).use { scenario ->
            scenario.onActivity { activity ->
                forceLayout(activity.findViewById(R.id.pager))
                activity.findViewById<android.view.View>(R.id.btnDelete)!!.performClick()
            }
            idle()
            // A rotation / dark-mode / font change recreates the screen inside the Undo window.
            scenario.recreate()
            // Advance Robolectric's virtual clock instead of sleeping on the host: the restore
            // and the snackbar teardown are main-looper work, so a controlled idle drains them.
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertTrue("a configuration change must not make the pending delete permanent", first.isFile)
            assertFalse("the hidden .del marker must be renamed back", File(first.path + ".del").exists())
        }
    }

    @Test
    fun `H5-3 tool flows never report a cancellation as a failure`() {
        val root = File("src/main/java/com/scanwhirl/app/tools")
        for (name in listOf("CompressFlow.kt", "MergeFlow.kt", "ResizeFlow.kt", "ScanLauncher.kt")) {
            val lines = File(root, name).readLines()
            lines.forEachIndexed { i, line ->
                if (line.trim() == "} catch (e: Exception) {") {
                    val window = lines.subList(maxOf(0, i - 3), i).joinToString("\n")
                    // Only coroutine job failures (not the synchronous scanner launch, whose
                    // scanner_failed handler runs outside any coroutine).
                    val body = lines.subList(i, minOf(lines.size, i + 6)).joinToString("\n")
                    val jobFailure = listOf("compress_failed", "resize_failed", "stage_failed", "merge_failed")
                        .any { body.contains(it) }
                    assertTrue(
                        "$name:${i + 1} catches Exception without first rethrowing CancellationException",
                        !jobFailure || window.contains("CancellationException")
                    )
                }
            }
        }
        val merge = File(root, "MergeFlow.kt").readText()
        assertTrue("Merge failure text is localized", !merge.contains("\"Merge failed:"))
        assertTrue(app.getString(R.string.merge_failed, "x").isNotBlank())
    }
}
