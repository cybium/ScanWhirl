package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.RectF
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

/**
 * PM-2.3b: the full-page editor writes crop/filter/rotate/delete to the draft, never to the
 * document. Filters always start from the unfiltered version, and Original restores the base
 * reference so OCR carry-over works again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageEditorDraftTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun digest(id: String) = PdfInvariants.dirDigest(File(repo.docsDir, id))
    private fun draftDir(id: String) = File(app.filesDir, "drafts/$id")
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

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
    fun `crop in the page viewer writes to the draft not the document`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val d0 = digest(id)
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 0)).use { scenario ->
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnCrop)!!.performClick() }
            var open = false
            assertTrue("crop overlay", pollUntil {
                scenario.onActivity { open = it.findViewById<android.view.View>(R.id.cropOverlay)!!.visibility == android.view.View.VISIBLE }
                open
            })
            // The page bitmap is decoded off the main thread; Apply is a no-op until it exists.
            var ready = false
            assertTrue("crop image decodes", pollUntil {
                scenario.onActivity {
                    ready = it.findViewById<CropView>(R.id.cropView).getCropRect().width() > 0f
                }
                ready
            })
            scenario.onActivity { cropCorners(it) }
            assertTrue(
                "the draft must exist",
                pollUntil(30_000) { runBlocking { repo.draftOf(id) } != null }
            )
            assertEquals("the saved document changed before Save", d0, digest(id))
            val cropped = runBlocking { repo.draftPages(id) }.first()
            val size = requireNotNull(PageReplace.bounds(cropped))
            assertTrue("draft page must be cropped, was $size", size.first in 150..175 && size.second in 230..255)
        }
    }

    @Test
    fun `filter applies from the original without stacking and Original removes the replacement`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val base = repo.getDocument(id)!!.pageFiles.single()
        val baseSha = Sha256.of(base)

        repo.draftFilter(id, 0, ScanFilter.BLACKWHITE)
        val first = repo.draftOf(id)!!.entries.single()
        val orig = File(draftDir(id), requireNotNull(first.replacementOrig))
        assertEquals("the kept original is the untouched base", baseSha, Sha256.of(orig))
        val bw = Sha256.of(File(draftDir(id), requireNotNull(first.replacement)))
        assertNotEquals(baseSha, bw)

        repo.draftFilter(id, 0, ScanFilter.ENHANCE)
        val second = repo.draftOf(id)!!.entries.single()
        val enriched = Sha256.of(File(draftDir(id), requireNotNull(second.replacement)))
        assertEquals("the original is immutable across filters", first.replacementOrig, second.replacementOrig)
        assertNotEquals("filtering must not stack", bw, enriched)
        assertNotEquals(baseSha, enriched)

        repo.draftFilter(id, 0, ScanFilter.ORIGINAL)
        val restored = repo.draftOf(id)!!.entries.single()
        assertNull(restored.replacement)
        assertNull("Original becomes a pure base reference", restored.replacementOrig)
    }

    @Test
    fun `rotate and delete in the page viewer write to the draft`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 0)).use { scenario ->
            scenario.onActivity { activity ->
                forceLayout(activity.findViewById(R.id.pager))
                activity.findViewById<android.view.View>(R.id.btnRotateRight)!!.performClick()
            }
            assertTrue(pollUntil { runBlocking { repo.draftOf(id) }?.changes == 1 })
            assertEquals(d0, digest(id))

            scenario.onActivity { activity ->
                forceLayout(activity.findViewById(R.id.pager))
                activity.findViewById<android.view.View>(R.id.btnDelete)!!.performClick()
                activity.commitPendingDelete()
            }
            assertTrue(pollUntil { runBlocking { repo.draftPages(id) }.size == 2 })
            assertEquals("the saved document changed before Save", d0, digest(id))
        }
    }

    @Test
    fun `crop then discard leaves the base byte identical`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val d0 = digest(id)
        repo.draftCrop(id, 0, RectF(0f, 0f, 100f, 150f))
        repo.discardDraft(id)
        assertEquals(d0, digest(id))
        assertFalse(draftDir(id).exists())
    }

    @Test
    fun `saved copy has the cropped size and the original keeps its own`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1)
        repo.setPageFilter(id, 0, ScanFilter.BLACKWHITE) // creates the base .orig in place
        val basePage = repo.getDocument(id)!!.pageFiles.single()
        val baseSize = requireNotNull(PageReplace.bounds(basePage))

        repo.draftCrop(id, 0, RectF(0f, 0f, 100f, 150f))
        val copy = repo.saveDraftAsNew(id, null)
        val newPage = copy.pageFiles.single()
        val newOrig = File(newPage.parentFile, "page_000.orig")

        val newSize = requireNotNull(PageReplace.bounds(newPage))
        val origSize = requireNotNull(PageReplace.bounds(newOrig))
        assertTrue("saved page width was ${newSize.first}", kotlin.math.abs(newSize.first - 100) <= 1)
        assertTrue("saved original width was ${origSize.first}", kotlin.math.abs(origSize.first - 100) <= 1)
        assertEquals("the base page keeps its own size", baseSize, PageReplace.bounds(basePage))
    }

    @Test
    fun `back from the page viewer keeps the draft edit and the bar shows it`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val d0 = digest(id)
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.intent(app, id, 0)).use { scenario ->
            scenario.onActivity { activity ->
                forceLayout(activity.findViewById(R.id.pager))
                activity.findViewById<android.view.View>(R.id.btnRotateRight)!!.performClick()
            }
            assertTrue("the rotate lands in the draft", pollUntil { runBlocking { repo.draftOf(id) }?.changes == 1 })
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        assertEquals("Back must not drop the saved document's edit", 1, runBlocking { repo.draftOf(id) }!!.changes)
        assertEquals(d0, digest(id))

        // Returning to Organize shows the change count.
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var shown = false
            assertTrue("the draft bar must show the change", pollUntil {
                scenario.onActivity { shown = it.findViewById<android.view.View>(R.id.draftBar)?.visibility == android.view.View.VISIBLE }
                shown
            })
            scenario.onActivity {
                assertEquals(
                    app.getString(
                        R.string.draft_header,
                        it.resources.getQuantityString(R.plurals.draft_pages, 2, 2),
                        it.resources.getQuantityString(R.plurals.draft_changes, 1, 1)
                    ),
                    it.findViewById<android.widget.TextView>(R.id.draftHeader).text.toString()
                )
            }
        }
    }

    @Test
    fun `staging back keeps the staged rotate for the review`() {
        val dir = File(app.cacheDir, "stage_${UUID.randomUUID()}").apply { mkdirs() }
        val page = File(dir, "page_001.jpg")
        page.writeBytes(ImportFixtures.image(width = 200, height = 300))
        ActivityScenario.launch<PageViewerActivity>(PageViewerActivity.stagingIntent(app, dir.path, 0)).use { scenario ->
            scenario.onActivity { activity ->
                forceLayout(activity.findViewById(R.id.pager))
                activity.findViewById<android.view.View>(R.id.btnRotateRight)!!.performClick()
            }
            assertTrue("the staged page rotates", pollUntil { PageReplace.bounds(page) == 300 to 200 })
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        assertEquals("staging Back keeps the edit for Review", 300 to 200, PageReplace.bounds(page))
    }

    /** The UiSmokeTest crop gesture: drag the top-left corner in, then Apply. */
    private fun cropCorners(activity: PageViewerActivity) {
        val cropView = activity.findViewById<CropView>(R.id.cropView)
        cropView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        cropView.layout(0, 0, 1080, 1920)
        val scale = kotlin.math.min(1080f / 200f, 1920f / 300f)
        val drawnTop = (1920f - 300f * scale) / 2f
        cropView.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 0f, drawnTop, 0)
        )
        cropView.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 216f, 450f, 0)
        )
        cropView.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, 216f, 450f, 0)
        )
        activity.findViewById<android.view.View>(R.id.btnApplyCrop)!!.performClick()
    }
}
