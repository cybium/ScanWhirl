package com.scanwhirl.app.ux130.l3

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.util.Sha256
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L3-01 — the result screen's Replace / Keep / Restore decisions against a real
 * repository. The original's bytes are compared before and after every change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_01_ResultDecisionUiTest {

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

    private fun fileHashes(id: String): List<String> {
        val doc = requireNotNull(repo.getDocument(id))
        return doc.pageFiles.sortedBy { it.name }.map { Sha256.of(it) }
    }

    private fun intentFor(source: String, output: String) = ToolResultActivity.intent(
        app, "compress", docIds = listOf(output),
        sourceId = source, outputId = output,
        operationId = "compress-$output", operation = "compress",
        sourceFingerprint = requireNotNull(repo.toolSourceFingerprint(source))
    )

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
    }

    @Test
    fun `Replace then Keep both then Replace keeps original bytes and the output copy`() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "original")
        val output = DocFixtures.saved(repo, app, pages = 1, name = "compressed")
        val before = fileHashes(source)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        val intent = intentFor(source, output)

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertNotNull("Replace is offered for a proven result",
                    it.findViewById<android.view.View>(R.id.ux130_l3_replace_original))
                it.findViewById<android.view.View>(R.id.ux130_l3_replace_original).performClick()
            }
            eventually { repo.getDocument(source) == null }
            assertNull(repo.getDocument(source))
            assertNotNull("output survives Replace", repo.getDocument(output))

            scenario.onActivity {
                it.findViewById<android.view.View>(R.id.ux130_l3_keep_both).performClick()
            }
            eventually { repo.getDocument(source) != null }
            assertEquals(before, fileHashes(source))

            scenario.onActivity {
                it.findViewById<android.view.View>(R.id.ux130_l3_replace_original).performClick()
            }
            eventually { repo.getDocument(source) == null }
            assertNotNull(repo.getDocument(output))
        }
        // Restore the fixture for a clean teardown and prove the last Trash copy is exact.
        val key = requireNotNull(repo.listTrash().firstOrNull { it.originalId == source }?.key)
        val restored = repo.restoreToolSource(key, fp)
        assertEquals(before, fileHashes(restored.id))
    }

    @Test
    fun `the Restore original route survives leaving and reopening the result`() {
        val source = DocFixtures.saved(repo, app, pages = 1, name = "original")
        val output = DocFixtures.saved(repo, app, pages = 1, name = "compressed")
        val before = fileHashes(source)
        val intent = intentFor(source, output)

        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity {
                it.findViewById<android.view.View>(R.id.ux130_l3_replace_original).performClick()
            }
            eventually { repo.getDocument(source) == null }
        }
        // A fresh Activity with the same intent must read the journal from disk.
        ActivityScenario.launch<ToolResultActivity>(intent).use { scenario ->
            scenario.onActivity {
                val restore = it.findViewById<android.view.View>(R.id.ux130_l3_restore_original)
                assertNotNull("persistent restore route", restore)
                assertEquals(android.view.View.VISIBLE, restore.visibility)
                restore.performClick()
            }
            eventually { repo.getDocument(source) != null }
            assertEquals(before, fileHashes(source))
        }
    }

    @Test
    fun `an unproven result offers no Replace decision`() {
        val output = DocFixtures.saved(repo, app, pages = 1, name = "export")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "split", docIds = listOf(output))
        ).use { scenario ->
            scenario.onActivity {
                assertNull(it.findViewById<android.view.View>(R.id.ux130_l3_replace_original))
                assertNull(it.findViewById<android.view.View>(R.id.ux130_l3_keep_both))
            }
        }
        assertNotNull("nothing moved", repo.getDocument(output))
    }
}
