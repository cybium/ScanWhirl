package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ScanFilter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TEST-ONLY reproduction of independent review B6 (P1): a queued Rotate can use an obsolete
 * page position after a pending deletion.
 *
 * This drives the REAL PageViewerActivity seams, not a duplicate algorithm:
 *  - [ScanRepository.draftFilterProbe] parks the slow filter A while it holds [pageOps];
 *  - the actual `btnRotateRight` / `btnDelete` click handlers run the production race;
 *  - the queued rotate calls commitPendingDeleteNowLocked() (deleting B) and then hands the
 *    STALE tap-time position (1) to repo.draftRotate, which now points at C.
 *
 * Correct behaviour resolves page identity inside the serialized edit and detects the stale edit,
 * leaving C byte-for-byte unchanged. The two final assertions are the product assertions and are
 * expected to FAIL on the unfixed base tree. Everything before them is a setup/precondition gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageViewerQueuedIdentityRegressionTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)
    private var controller: ActivityController<PageViewerActivity>? = null

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Sets a companion probe on ScanRepository; absent on the base tree = setup failure. */
    private fun setProbe(name: String, probe: (() -> Unit)?) {
        val field = try {
            ScanRepository::class.java.getField(name)
        } catch (e: NoSuchFieldException) {
            throw AssertionError("SETUP: draft seam '$name' is missing", e)
        }
        field.set(null, probe)
    }

    /** Real production seam: identity-based applyFilter used by the filter sheet. */
    private fun callApplyFilter(
        screen: PageViewerActivity,
        docId: String,
        identity: String,
        filter: ScanFilter
    ) {
        val params = arrayOf(String::class.java, String::class.java, ScanFilter::class.java)
        val method: Method = PageViewerActivity::class.java.declaredMethods.firstOrNull { m ->
            (m.name == "applyFilter" || m.name.startsWith("applyFilter$")) &&
                m.parameterTypes.contentEquals(params)
        } ?: throw AssertionError("SETUP: identity-based applyFilter is missing")
        method.isAccessible = true
        method.invoke(screen, docId, identity, filter)
    }

    private fun fieldValue(screen: PageViewerActivity, name: String): Any? {
        val f = PageViewerActivity::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(screen)
    }

    private fun currentPositionOf(screen: PageViewerActivity): Int {
        val m: Method = PageViewerActivity::class.java.getDeclaredMethod("currentPosition")
        m.isAccessible = true
        return m.invoke(screen) as Int
    }

    private fun pageOpsLocked(screen: PageViewerActivity): Boolean =
        (fieldValue(screen, "pageOps") as Mutex).isLocked

    private fun hasPendingDelete(screen: PageViewerActivity): Boolean =
        fieldValue(screen, "pendingDelete") != null

    private fun forceLayout(rv: RecyclerView) {
        val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        rv.measure(w, h)
        rv.layout(0, 0, 1080, 1920)
    }

    /** Establish that the pager's snap view is really on [target] before any tap. */
    private fun ensureOnPage(screen: PageViewerActivity, target: Int) {
        val pager: RecyclerView = screen.findViewById(R.id.pager)
        assertNotNull("pager exists", pager)
        forceLayout(pager)
        idle()
        if (currentPositionOf(screen) != target) {
            (pager.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 0)
            forceLayout(pager)
            idle()
        }
        assertEquals("viewer must be showing page index $target", target, currentPositionOf(screen))
    }

    /** Bounded pump: wait until the queued operation released pageOps and consumed pendingDelete. */
    private fun waitForSettled(screen: PageViewerActivity, label: String, timeoutMs: Long = 20_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (!pageOpsLocked(screen) && !hasPendingDelete(screen)) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $label")
    }

    @Test(timeout = 45_000)
    fun `queued rotate after a pending delete must not rotate the neighbour`() {
        // Fictional draft pages A,B,C (distinct widths => distinct bytes, see DocFixtures).
        val id = DocFixtures.saved(repo, app, pages = 3)
        val doc = repo.getDocument(id)!!
        assertEquals(3, doc.pageFiles.size)
        val aSource = doc.pageFiles[0].name
        val bSource = doc.pageFiles[1].name
        val cSource = doc.pageFiles[2].name
        val initialPages = runBlocking { repo.draftPages(id) }
        val cShaBefore = sha(initialPages[2].readBytes())
        assertFalse("C must be byte-distinct from B", cShaBefore == sha(initialPages[1].readBytes()))

        // Barrier: hold the slow filter inside repo.draftFilter while it owns pageOps.
        val filterEntered = CountDownLatch(1)
        val filterRelease = CountDownLatch(1)
        setProbe("draftFilterProbe") {
            filterEntered.countDown()
            try {
                filterRelease.await(25, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }

        // Launch already snapped on B (index 1).
        val c = Robolectric.buildActivity(
            PageViewerActivity::class.java,
            PageViewerActivity.intent(app, id, 1)
        ).setup()
        controller = c
        try {
            idle()
            val screen = c.get()
            ensureOnPage(screen, 1)
            assertTrue("pageOps must be free before the filter starts", !pageOpsLocked(screen))

            @Suppress("UNCHECKED_CAST")
            val idsBefore = fieldValue(screen, "pageIds") as List<String>
            assertEquals(3, idsBefore.size)
            val identityA = idsBefore[0]
            val identityB = idsBefore[1]
            val identityC = idsBefore[2]

            // 1. Start filter A (identity of page 0); it parks holding pageOps.
            callApplyFilter(screen, id, identityA, ScanFilter.ENHANCE)
            idle()
            assertTrue("the filter reached the repo seam", filterEntered.await(15, TimeUnit.SECONDS))
            assertTrue("the parked filter holds pageOps", pageOpsLocked(screen))

            // 2. On B (pos 1), queue a Rotate behind the parked filter. rotateCurrent captures pos=1 here.
            c.get().findViewById<View>(R.id.btnRotateRight).performClick()
            idle()

            // 3. Delete B while pageOps is still held: pendingDelete + reload happen OUTSIDE the lock.
            c.get().findViewById<View>(R.id.btnDelete).performClick()
            idle()
            assertTrue("B is pending delete", hasPendingDelete(screen))
            @Suppress("UNCHECKED_CAST")
            val uiIds = fieldValue(screen, "pageIds") as List<String>
            assertEquals("B is hidden from the pager immediately", listOf(identityA, identityC), uiIds)
            assertFalse("the hidden identity is B", uiIds.contains(identityB))

            // 4. Release A. The queued rotate commits B's delete, then uses the stale pos=1.
            filterRelease.countDown()
            waitForSettled(screen, "the queued rotate to settle")
            idle()

            // ---- Product assertions (RED on the unfixed base tree) ----
            val finalDraft = runBlocking { repo.draftOf(id) }!!
            assertEquals("only A and C survive", 2, finalDraft.entries.size)
            assertEquals("A stays first", aSource, finalDraft.entries[0].source)
            assertEquals("C keeps its slot as the second survivor", cSource, finalDraft.entries[1].source)
            assertFalse("B was deleted", finalDraft.entries.any { it.source == bSource })

            val finalPages = runBlocking { repo.draftPages(id) }
            assertEquals("C byte-for-byte unchanged", cShaBefore, sha(finalPages[1].readBytes()))
            assertNull("C has no stray rotation/filter replacement", finalDraft.entries[1].replacement)
        } finally {
            filterRelease.countDown()
            setProbe("draftFilterProbe", null)
            controller = null
            c.pause().stop().destroy()
        }
    }
}
