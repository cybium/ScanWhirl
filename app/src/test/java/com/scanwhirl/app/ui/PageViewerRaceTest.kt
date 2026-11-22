package com.scanwhirl.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ScanFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PVR production race: applyFilter writes `filterChoice[pos]` after `repo.draftFilter` returns,
 * while commitPendingDelete(Now) remaps those choices from another coroutine with no ordering
 * guard. A delete in that window leaves the deleted page's filter on its neighbour.
 *
 * The test drives the two operations concurrently with a controllable seam in the repo draft
 * call, in both orders. Reflection keeps this file compiling on the pre-fix SHA (RED there):
 * the production seams and the identity-based filter entry are exactly what the fix adds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageViewerRaceTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)
    private var controller: ActivityController<PageViewerActivity>? = null

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(label: String, timeoutMs: Long = 15_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $label")
    }

    /** Sets a companion probe on ScanRepository; absent on the base tree = RED. */
    private fun setProbe(name: String, probe: (() -> Unit)?) {
        val field = try {
            ScanRepository::class.java.getField(name)
        } catch (e: NoSuchFieldException) {
            throw AssertionError("RED on base: draft seam '$name' is missing", e)
        }
        field.set(null, probe)
    }

    /** Identity-based applyFilter; absent on the base tree = RED. (Kotlin mangles internal names.) */
    private fun callApplyFilter(screen: PageViewerActivity, docId: String, identity: String, filter: ScanFilter) {
        val params = arrayOf(String::class.java, String::class.java, ScanFilter::class.java)
        val method: Method = PageViewerActivity::class.java.declaredMethods.firstOrNull { m ->
            (m.name == "applyFilter" || m.name.startsWith("applyFilter$")) &&
                m.parameterTypes.contentEquals(params)
        } ?: throw AssertionError("RED on base: identity-based applyFilter is missing")
        method.isAccessible = true
        method.invoke(screen, docId, identity, filter)
    }

    /** Stable identity of a visible page; absent on the base tree = RED. */
    private fun identityOfPageZero(screen: PageViewerActivity): String {
        val field = try {
            PageViewerActivity::class.java.getDeclaredField("pageIds")
        } catch (e: NoSuchFieldException) {
            throw AssertionError("RED on base: page identity list is missing", e)
        }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val ids = field.get(screen) as? List<String>
        assertTrue("page identities were loaded", !ids.isNullOrEmpty())
        return ids!![0]
    }

    private fun launch(id: String): ActivityController<PageViewerActivity> {
        val c = Robolectric.buildActivity(
            PageViewerActivity::class.java, PageViewerActivity.intent(app, id, 0)
        ).setup()
        controller = c
        idle()
        return c
    }

    private fun releaseAll(vararg latches: CountDownLatch) {
        setProbe("draftFilterProbe", null)
        setProbe("draftDeleteProbe", null)
        latches.forEach { it.countDown() }
    }

    private fun assertCleanNeighbour(id: String, identityZero: String, screen: PageViewerActivity) {
        val draft = runBlocking { repo.draftOf(id) }!!
        assertEquals("the deleted page is gone from the draft", 2, draft.entries.size)
        assertTrue(
            "the two surviving pages are untouched base pages (no stray filter baked in)",
            draft.entries.all { it.replacement == null }
        )
        assertEquals(
            "the neighbour must keep ORIGINAL, not the deleted page's ENHANCE",
            ScanFilter.ORIGINAL,
            screen.selectedFilter(0)
        )
        val choices = runCatching {
            val f = PageViewerActivity::class.java.getDeclaredField("filterChoice")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (f.get(screen) as Map<String, ScanFilter>)
        }.getOrNull()
        if (choices != null) assertFalse("the deleted page's filter choice is gone", choices.containsKey(identityZero))
    }

    @Test(timeout = 30_000)
    fun `filter starts first, delete lands in the window - neighbour keeps ORIGINAL`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val filterEntered = CountDownLatch(1)
        val filterRelease = CountDownLatch(1)
        setProbe("draftFilterProbe") {
            filterEntered.countDown()
            try { filterRelease.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        }

        val c = launch(id)
        try {
            val identityZero = identityOfPageZero(c.get())

            // 1. Start the filter on page 0; it parks inside repo.draftFilter.
            callApplyFilter(c.get(), id, identityZero, ScanFilter.ENHANCE)
            idle() // lifecycleScope dispatches through the main looper under Robolectric
            assertTrue("the filter reached the repo seam", filterEntered.await(15, TimeUnit.SECONDS))

            // 2. While it is parked, delete that same page and commit it.
            c.get().findViewById<View>(R.id.btnDelete).performClick()
            idle()
            c.get().commitPendingDelete()
            Thread.sleep(150) // let the contending page-op coroutine queue up
            idle()

            // 3. Let the filter finish; both operations must settle in a consistent order.
            releaseAll(filterRelease)
            waitFor("both page operations to settle") { runBlocking { repo.draftOf(id) }?.entries?.size == 2 }
            idle()

            assertCleanNeighbour(id, identityZero, c.get())
        } finally {
            releaseAll(filterRelease)
            c.pause().stop().destroy()
            controller = null
        }
    }

    @Test(timeout = 30_000)
    fun `delete starts first, filter result for the deleted page arrives late - it is dropped`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val deleteEntered = CountDownLatch(1)
        val deleteRelease = CountDownLatch(1)
        setProbe("draftDeleteProbe") {
            deleteEntered.countDown()
            try { deleteRelease.await(15, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        }

        val c = launch(id)
        try {
            val identityZero = identityOfPageZero(c.get())

            // 1. Delete page 0; the commit parks inside repo.draftDelete.
            c.get().findViewById<View>(R.id.btnDelete).performClick()
            idle()
            c.get().commitPendingDelete()
            assertTrue("the delete reached the repo seam", deleteEntered.await(15, TimeUnit.SECONDS))

            // 2. While it is parked, a filter result for the SAME (soon deleted) page arrives.
            callApplyFilter(c.get(), id, identityZero, ScanFilter.ENHANCE)
            idle() // lifecycleScope dispatches through the main looper under Robolectric
            Thread.sleep(150)
            idle()

            // 3. Release the delete; the late filter must be dropped, not applied to the neighbour.
            releaseAll(deleteRelease)
            waitFor("the delete to settle") { runBlocking { repo.draftOf(id) }?.entries?.size == 2 }
            idle()

            assertCleanNeighbour(id, identityZero, c.get())
        } finally {
            releaseAll(deleteRelease)
            c.pause().stop().destroy()
            controller = null
        }
    }
}
