package com.scanwhirl.app.ux130.v2l

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V2L PERF 1-3 + 6 — measurable, bounded main-thread work.
 * RED on base: size() stat'd the file, whenDay() allocated per bind, the thumb key stat'd,
 * and the shell listed the library on both onCreate and onResume.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class V2PerfTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
        PageThumbLoader.keyStatProbe = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0) as ViewGroup
        return host.getChildAt(0) as LibraryScreen
    }

    @Test(timeout = 30_000)
    fun `binding two hundred cards makes zero main-thread thumbnail key stats`() {
        val seed = DocFixtures.saved(repo, app, pages = 1, name = "Perf seed")
        val base = repo.getDocument(seed)!!
        val docs: List<ScanDocument> = (0 until 200).map { i ->
            base.copy(id = "perf-$i", name = "Perf $i", cachedSize = 1_024L * (i + 1))
        }
        var stats = 0
        PageThumbLoader.keyStatProbe = { stats++ }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            waitFor("library loaded") { library(activity).loaded }
            val lib = library(activity)
            val bind = LibraryScreen::class.java.getDeclaredMethod(
                "bindCard", DocCard::class.java, ScanDocument::class.java
            )
            bind.isAccessible = true
            for (doc in docs) bind.invoke(lib, DocCard(activity), doc)
            assertEquals("no lastModified()/length() stat during 200 binds", 0, stats)
        } finally {
            PageThumbLoader.keyStatProbe = null
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `size and day formatting are hoisted off the bind hot path`() {
        val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/LibraryScreen.kt").readText()
        val size = src.substringAfter("private fun size(doc: ScanDocument)").substringBefore("private fun whenDay")
        assertTrue("cached size only", size.contains("doc.cachedSize"))
        assertTrue("no exists() in size()", !size.contains("exists()"))
        assertTrue("no length() in size()", !size.contains("length()"))
        val day = src.substringAfter("private var dayFormatter").substringBefore("internal companion object")
        assertTrue("one formatter field", day.contains("private var dayFormatter"))
        assertTrue("formatter invalidated by locale", day.contains("dayFormatterLocale != locale"))
        assertTrue("reused calendars", day.contains("dayDocCalendar") && day.contains("dayTodayCalendar"))

        val card = java.io.File("src/main/java/com/scanwhirl/app/ui/kit/DocCard.kt").readText()
        val bind = card.substringAfter("val canShowPage").substringBefore("showThumbPlaceholder")
        assertTrue("no exists() stat in card bind", !bind.contains("exists()"))
        assertTrue("revision passed to the thumb loader", card.contains("revision = document.revision.toString()"))
    }

    @Test(timeout = 30_000)
    fun `a cold start lists the library exactly once`() {
        val scanApp = ScanWhirlApp.from(app)
        scanApp.startStorageRecovery()
        waitFor("recovery finished") { scanApp.storageRecoveryFinished.value }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            waitFor("library loaded") { library(activity).loaded }
            idle()
            assertEquals("one cold-start listing", 1, library(activity).reloadRequests)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `only the library tab is instantiated at cold start`() {
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
            val onCreate = src.substringAfter("override fun onCreate").substringBefore("private fun loadWallpaper")
            assertTrue("ToolsHubScreen not constructed in onCreate", !onCreate.contains("ToolsHubScreen("))
            assertTrue("VaultScreen not constructed in onCreate", !onCreate.contains("VaultScreen("))
            assertTrue("MeScreen not constructed in onCreate", !onCreate.contains("MeScreen("))
            assertTrue("lazy factories exist", src.contains("private fun ensureToolsHub()"))
            activity.findViewById<View>(R.id.swNavTools).performClick()
            idle()
            assertTrue("Tools still opens", activity.findViewById<View>(R.id.swTabContent) != null)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
