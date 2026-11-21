package com.scanwhirl.app.ux130.l3

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
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
import java.io.File

/**
 * L3-02 P1 — a stale output id must never authorize deleting somebody else's
 * document. The identity is captured in the result intent, before the id is reused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_02_OutputIdentityGuardTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
    }

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `an id reused by another document is not removed by Undo`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "original output")
        // Producers capture identity off main before passing it into the intent.
        val identity = ToolResultActivity.captureOutputIdentityBlocking(app, listOf(output), emptyList())
        val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(output),
            outputIdentity = identity)

        val other = DocFixtures.saved(repo, app, pages = 1, name = "new owner")
        File(repo.docsDir, output).deleteRecursively()
        File(repo.docsDir, other).copyRecursively(File(repo.docsDir, output))
        assertNotNull(repo.getDocument(output))

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
            val deadline = System.currentTimeMillis() + 5000L
            var shown = false
            while (!shown && System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val error = activity.findViewById<TextView>(R.id.toolResultError)
                    shown = error.isVisible &&
                        error.text.toString() == app.getString(R.string.ux130_l3_copy_changed)
                }
                if (!shown) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(20L)
                }
            }
            assertTrue("the honest refusal is visible", shown)
        }
        val stillThere = repo.getDocument(output)
        assertNotNull("the document that reused the id survives", stillThere)
        assertEquals("new owner", stillThere!!.name)
    }

    @Test
    fun `an unchanged output is still removed by one tap`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "fresh output")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(output))
        ).use { scenario ->
            scenario.onActivity { it.findViewById<View>(R.id.toolResultUndo).performClick() }
            val deadline = System.currentTimeMillis() + 5000L
            val diag = StringBuilder()
            while (repo.getDocument(output) != null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                scenario.onActivity { activity ->
                    val err = activity.findViewById<TextView>(R.id.toolResultError)
                    diag.append("error='${err.text}' ")
                }
                Thread.sleep(20L)
            }
            assertEquals("guard diagnostic: $diag", null, repo.getDocument(output))
        }
    }
}
