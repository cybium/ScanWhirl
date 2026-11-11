package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.ui.pagegrid.PageGridView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanReviewRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dirs = mutableListOf<File>()
    private fun stage(count: Int) = PendingScans.create(app).also { dir ->
        dirs.add(dir)
        repeat(count) { File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 120 + it)) }
    }
    private fun chip(activity: ScanReviewActivity, category: String): Chip {
        val rail = activity.findViewById<ChipGroup>(R.id.categoryRail)
        return (0 until rail.childCount).map { rail.getChildAt(it) as Chip }.first { it.tag == category }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for review state")
    }
    @After fun cleanup() { Durable.resetForTests(); dirs.forEach { it.deleteRecursively() } }

    @Test fun `one and five page reviews restore title and manual choice after recreation and cold launch`() {
        for (count in listOf(1, 5)) {
            val dir = stage(count)
            val title = "Invoice छात्र 😀 $count"
            val intent = ScanReviewActivity.intent(app, dir.path, null, "Document 4")
            ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
                scenario.onActivity {
                    chip(it, CategoryRules.STUDY).performClick()
                    it.findViewById<EditText>(R.id.titleInput).setText(title)
                }
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(title, it.findViewById<EditText>(R.id.titleInput).text.toString())
                    assertTrue(chip(it, CategoryRules.STUDY).isChecked)
                }
            }
            await { PendingScans.readReviewState(dir)?.title == title }
            // New Activity, no instance-state bundle and a different launcher default.
            ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Document 99")).use { scenario ->
                scenario.onActivity {
                    assertEquals(title, it.findViewById<EditText>(R.id.titleInput).text.toString())
                    assertTrue(chip(it, CategoryRules.STUDY).isChecked)
                    it.findViewById<EditText>(R.id.titleInput).setText("Passport")
                    assertTrue(chip(it, CategoryRules.STUDY).isChecked)
                }
            }
            await { PendingScans.readReviewState(dir)?.title == "Passport" }
            assertEquals(count, PendingScans.pageCount(dir))
        }
    }

    @Test fun `automatic category keeps suggesting after restart and empty title retains original default`() {
        val dir = stage(1)
        val intent = ScanReviewActivity.intent(app, dir.path, null, "Invoice 7")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.titleInput).setText("") }
        }
        await { PendingScans.readReviewState(dir)?.title == "" }
        ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Document 99")).use { scenario ->
            scenario.onActivity {
                assertEquals("", it.findViewById<EditText>(R.id.titleInput).text.toString())
                assertTrue(chip(it, CategoryRules.RECEIPTS).isChecked)
                it.findViewById<EditText>(R.id.titleInput).setText("Physics notes")
                assertTrue(chip(it, CategoryRules.STUDY).isChecked)
            }
        }
        await { PendingScans.readReviewState(dir)?.title == "Physics notes" }
        assertFalse(PendingScans.readReviewState(dir)!!.categoryPickedByHand)
    }

    @Test fun `open review debounces title writes off main and flushes latest state before Save`() {
        val dir = stage(5)
        val repo = ScanRepository(app)
        val before = repo.listDocuments().size
        val title = "Invoice नाम 😀"
        val writes = AtomicInteger()
        Durable.beforeFileSync = {
            if (it.name == PendingScans.REVIEW_STATE_FILE + ".tmp") {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                writes.incrementAndGet()
            }
        }
        ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Doc")).use { scenario ->
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            await { PendingScans.readReviewState(dir)?.title == "Doc" }
            val initialWrites = writes.get()
            scenario.onActivity {
                repeat(10) { n -> it.findViewById<EditText>(R.id.titleInput).setText("typing $n") }
                it.findViewById<EditText>(R.id.titleInput).setText(title)
                chip(it, CategoryRules.IDS).performClick()
                assertEquals("typing must not write per character", initialWrites, writes.get())
                // Save immediately, before the 350ms debounce fires.
                it.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            await { repo.listDocuments().size == before + 1 && !dir.exists() }
            val doc = repo.listDocuments().first { it.name == title }
            assertEquals(CategoryRules.IDS, doc.category)
            assertEquals(5, doc.pageCount)
            assertEquals(initialWrites + 1, writes.get())
        }
    }

    @Test fun `metadata failure blocks Save without publishing or losing staged pages`() {
        val dir = stage(1)
        val page = PendingScans.pages(dir).single()
        val hash = Sha256.of(page)
        val repo = ScanRepository(app)
        val before = repo.listDocuments().size
        ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Doc")).use { scenario ->
            Durable.beforeFileSync = { if (it.name == PendingScans.REVIEW_STATE_FILE + ".tmp") throw IOException("injected metadata failure") }
            scenario.onActivity {
                it.findViewById<EditText>(R.id.titleInput).setText("Keep this title")
                ShadowToast.reset()
                it.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            await { ShadowToast.getTextOfLatestToast() != null }
            // Clear, localized feedback, not a raw ExecutionException/IOException string.
            assertEquals(app.getString(R.string.review_state_failed), ShadowToast.getTextOfLatestToast())
            assertEquals(before, repo.listDocuments().size)
            assertEquals(hash, Sha256.of(page))
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals("Keep this title", it.findViewById<EditText>(R.id.titleInput).text.toString())
            }
            Durable.resetForTests()
        }
        await { PendingScans.readReviewState(dir)?.title == "Keep this title" }
    }

    @Test fun `staging discarded elsewhere is not resurrected and reports no false metadata failure`() {
        val dir = stage(1)
        ActivityScenario.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Doc")).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.titleInput).setText("Typed then discarded") }
            ShadowToast.reset()
            // e.g. the full-screen viewer deleted the last page and with it the staging folder.
            assertTrue(dir.deleteRecursively())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // onPause flush
            val deadline = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertFalse(dir.exists())
            assertNotEquals(app.getString(R.string.review_state_failed), ShadowToast.getTextOfLatestToast())
        }
        assertFalse(dir.exists())
    }

    @Test fun `debounce persists while review stays open and rotation reorder filters survive cold resume`() {
        val dir = stage(5)
        File(dir, "filters.txt").writeText("page_000.jpg=${ScanFilter.BLACKWHITE.id}")
        val files = PendingScans.pages(dir)
        val original = files.map { Sha256.of(it) }
        var expected: List<String> = emptyList()
        val intent = ScanReviewActivity.intent(app, dir.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity {
                it.findViewById<EditText>(R.id.titleInput).setText("Keep rotation नाम")
                chip(it, CategoryRules.FORMS).performClick()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            await { PendingScans.readReviewState(dir)?.title == "Keep rotation नाम" }
            assertTrue(PendingScans.readReviewState(dir)!!.categoryPickedByHand)
            scenario.onActivity {
                it.toggleSelect(0)
                it.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_rotate_right, 0)
            }
            await { ImageTools.imageSize(files[0].readBytes())?.second == 120 }
            val rotated = Sha256.of(files[0])
            assertNotEquals(original[0], rotated)
            scenario.onActivity { it.findViewById<PageGridView>(R.id.pageGrid).onDropped!!.invoke(0, 4) }
            await {
                // filters.txt is rewritten on main only after both IO rename passes finish;
                // checking it first avoids hashing a page mid-rename (page -> tmp -> page).
                File(dir, "filters.txt").readText().contains("page_004.jpg=${ScanFilter.BLACKWHITE.id}") &&
                    files[4].exists() && Sha256.of(files[4]) == rotated
            }
            expected = original.drop(1) + rotated
            assertEquals(expected, PendingScans.pages(dir).map { Sha256.of(it) })
        }
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertEquals("Keep rotation नाम", it.findViewById<EditText>(R.id.titleInput).text.toString())
                assertTrue(chip(it, CategoryRules.FORMS).isChecked)
                assertEquals(expected, PendingScans.pages(dir).map { file -> Sha256.of(file) })
            }
        }
    }
}
