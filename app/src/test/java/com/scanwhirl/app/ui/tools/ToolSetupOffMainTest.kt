package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C07: a tool setup screen must draw before it stats the library (300 documents = a full
 * per-page stat), and the listing must run off the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolSetupOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
    }

    @After
    fun tearDown() {
        ScanRepository.readProbe = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    @Test
    fun `the setup screen lists the library off the main thread`() {
        DocFixtures.saved(repo, app, pages = 2, name = "One")
        DocFixtures.saved(repo, app, pages = 2, name = "Two")
        val mainName = Thread.currentThread().name
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        ScanRepository.readProbe = { threads += it }

        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "split")).use { scenario ->
            val ready = await {
                var shown = false
                scenario.onActivity { a ->
                    shown = a.findViewById<View>(R.id.toolSetupRun) != null &&
                        a.findViewById<View>(R.id.toolSetupRun).isShown
                }
                shown
            }
            assertTrue("the setup chrome is drawn", ready)
            assertTrue("the library is listed", threads.isNotEmpty())
            assertFalse(
                "listDocuments ran on the main thread: $threads",
                threads.contains(mainName)
            )
        }
    }
}
