package com.scanwhirl.app.ux130.l3

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * L3-02 P1 performance guard: file hashing for the Undo identity must never run
 * on the main thread — neither while building the intent nor inside Undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_02_IdentityThreadProbeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var threads: MutableList<Thread>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
        threads = mutableListOf()
        ToolResultActivity.identityThreadProbe = { threads += it }
    }

    @After
    fun tearDown() {
        ToolResultActivity.identityThreadProbe = null
        Faults.resetAll()
    }

    @Test
    fun `building the result intent performs no file hashing on main`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "no hash")
        val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        assertNotNull(intent)
        assertTrue("intent() ran a hash: $threads", threads.isEmpty())
    }

    @Test
    fun `the Undo identity re-check never hashes on the main thread`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "io hash")
        // Producer-time D2 identity: real producers capture it on IO. Do the same here so the
        // thread probe sees an IO thread, not Robolectric's main test thread, and the matching
        // output is still removed with one tap.
        val identity = requireNotNull(runBlocking(Dispatchers.IO) {
            ToolResultActivity.captureOutputIdentityBlocking(app, listOf(id), emptyList())
        })
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id), outputIdentity = identity)
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
            val deadline = System.currentTimeMillis() + 5000L
            while (repo.getDocument(id) != null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20L)
            }
        }
        val main = Looper.getMainLooper().thread
        assertTrue("hashing ran on the main thread: $threads", threads.none { it === main })
        assertEquals(null, repo.getDocument(id))
    }
}
