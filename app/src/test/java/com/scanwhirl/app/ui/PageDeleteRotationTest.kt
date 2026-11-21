package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** U2: configuration recreation is not a decision to lose the page-delete Undo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageDeleteRotationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun awaitPages(scenario: ActivityScenario<DocumentViewerActivity>, count: Int) {
        assertTrue("viewer shows $count pages", waitUntil {
            var actual = -1
            scenario.onActivity {
                val field = DocumentViewerActivity::class.java.getDeclaredField("visiblePages")
                field.isAccessible = true
                actual = (field.get(it) as List<*>).size
            }
            actual == count
        })
    }

    private fun deleteFirst(scenario: ActivityScenario<DocumentViewerActivity>) {
        awaitPages(scenario, 3)
        scenario.onActivity {
            it.toggleSelected(0)
            it.deleteSelectedPages()
        }
        awaitPages(scenario, 2)
    }

    private fun undoIsShown(scenario: ActivityScenario<DocumentViewerActivity>): Boolean {
        var shown = false
        scenario.onActivity {
            shown = it.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                ?.let { text -> text.isShown && text.text.toString() == it.getString(R.string.page_deleted) } == true
        }
        return shown
    }

    @Test
    fun `rotation keeps deleted page hidden and Undo restores it byte identically`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            deleteFirst(scenario)
            scenario.recreate()
            awaitPages(scenario, 2)
            assertTrue("Page deleted - Undo is shown after rotation", waitUntil { undoIsShown(scenario) })
            scenario.onActivity {
                assertTrue(it.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick())
            }
            awaitPages(scenario, 3)
            assertEquals("Undo does not create a draft edit", 3, runBlocking { repo.draftPages(id) }.size)
            assertEquals("saved PDF, pages and metadata remain exact", before,
                PdfInvariants.dirDigest(File(repo.docsDir, id)))
        }
    }

    @Test
    fun `rotation does not restart the Undo window and expiry deletes only from draft`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            deleteFirst(scenario)
            // At least 8 seconds are available; spend most before recreation.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            scenario.recreate()
            awaitPages(scenario, 2)
            assertTrue("remaining Undo is visible", waitUntil { undoIsShown(scenario) })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue("the original deadline still commits the draft delete",
                waitUntil { runBlocking { repo.draftPages(id) }.size == 2 })
            assertEquals("expiry never changes the saved document", before,
                PdfInvariants.dirDigest(File(repo.docsDir, id)))
        }
    }

    @Test
    fun `repeated rotation keeps one Undo and it cannot delete the restored page later`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            deleteFirst(scenario)
            repeat(2) {
                scenario.recreate()
                awaitPages(scenario, 2)
                assertTrue(waitUntil { undoIsShown(scenario) })
            }
            scenario.onActivity {
                it.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            awaitPages(scenario, 3)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            assertEquals("old activity timeout must not delete an undone page", 3,
                runBlocking { repo.draftPages(id) }.size)
            assertFalse("Undo leaves no delete edit", runBlocking { repo.draftOf(id) }?.changes?.let { it > 0 } == true)
        }
    }

    @Test
    fun `rotation after Undo does not resurrect a pending delete`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            deleteFirst(scenario)
            assertTrue(waitUntil { undoIsShown(scenario) })
            scenario.onActivity {
                it.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            awaitPages(scenario, 3)
            scenario.recreate()
            awaitPages(scenario, 3)
            assertFalse("an already-undone delete has no new Undo window", undoIsShown(scenario))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
            assertEquals(3, runBlocking { repo.draftPages(id) }.size)
        }
    }

    @Test
    fun `leaving the viewer still commits an unconfirmed page delete to the draft only`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            deleteFirst(scenario)
            scenario.onActivity { it.finish() }
        }
        assertTrue("leaving remains the end of this page-delete Undo window",
            waitUntil { runBlocking { repo.draftPages(id) }.size == 2 })
        assertEquals(before, PdfInvariants.dirDigest(File(repo.docsDir, id)))
    }

    @Test
    fun `an import with a pending page edit is kept rather than offered Discard on Back`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id, freshImport = true)
        ).use { scenario ->
            deleteFirst(scenario)
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                assertTrue("this edited import skips the import Discard question", it.isFinishing)
            }
        }
        assertNotNull(repo.getDocument(id))
        assertTrue(com.scanwhirl.app.store.ImportDiscardStore(app).pending().isEmpty())
    }
}
