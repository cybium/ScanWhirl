package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
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
 * C03: opening the page viewer must not block the main thread on `repo.draftPages` (disk plus
 * DRAFT_LOCK). The read probe records the thread every draft read starts on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageViewerOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
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
    fun `the draft read never runs on the main thread`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Pages")
        val mainName = Thread.currentThread().name
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        ScanRepository.readProbe = { threads += it }

        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 1)).use { scenario ->
            val shown = await {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<RecyclerView>(R.id.pager).adapter?.itemCount == 3
                }
                ready
            }
            assertTrue("the pager receives the pages", shown)
            assertTrue("the draft read happened", threads.isNotEmpty())
            assertFalse(
                "draftPages ran on the main thread: $threads",
                threads.contains(mainName)
            )
            scenario.onActivity { a ->
                assertTrue(
                    "the requested page is on screen",
                    a.findViewById<View>(R.id.pager).isShown
                )
            }
        }
    }

    @Test
    fun `a staging folder still opens synchronously without a draft read`() {
        val dir = java.io.File(app.cacheDir, "staging_${System.nanoTime()}").apply { mkdirs() }
        for (i in 0..1) {
            java.io.File(dir, "page_00$i.jpg").writeBytes(
                com.scanwhirl.app.util.ImportFixtures.image(width = 120, height = 180)
            )
        }
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        ScanRepository.readProbe = { threads += it }
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.stagingIntent(app, dir.path, 0)).use { scenario ->
            val shown = await {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<RecyclerView>(R.id.pager).adapter?.itemCount == 2
                }
                ready
            }
            assertTrue(shown)
            assertTrue("unsaved scans never touch the library reader", threads.isEmpty())
        }
    }
}
