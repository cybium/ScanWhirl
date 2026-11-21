package com.scanwhirl.app.ux130.l3

import android.app.Application
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
        // Built while the original still owns the id: identity is captured here.
        val intent = ToolResultActivity.intent(app, "compress", docIds = listOf(output))

        val other = DocFixtures.saved(repo, app, pages = 1, name = "new owner")
        File(repo.docsDir, output).deleteRecursively()
        File(repo.docsDir, other).copyRecursively(File(repo.docsDir, output))
        assertNotNull(repo.getDocument(output))

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.toolResultUndo).performClick()
                val error = activity.findViewById<TextView>(R.id.toolResultError)
                assertEquals(
                    "the honest refusal is visible",
                    app.getString(R.string.ux130_l3_copy_changed),
                    error.text.toString()
                )
                assertTrue("the refusal is shown", error.isVisible)
            }
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
        }
        val deadline = System.currentTimeMillis() + 5000L
        while (repo.getDocument(output) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals(null, repo.getDocument(output))
    }
}
