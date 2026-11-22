package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * L1lib defect #2: the Tags sheet calls repo.listTags(), which reads every meta.txt.
 * It must run off the main thread and show a loading state until the tags arrive.
 * Fictional fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryTagsOffMainTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    @After
    fun tearDown() {
        ScanRepository.listTagsProbe = null
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(10)
        }
        idle()
        assertTrue(what, runCatching(condition).getOrDefault(false))
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? DocCard }
    }

    @Test
    fun `tags load off the main thread and the sheet shows a loading state`() {
        ProGate.setTestOverride(app, true)
        DocFixtures.saved(repo, app, pages = 1, name = "Tagged")

        val ioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "l1lib-io") }
        val io = ioThread.asCoroutineDispatcher()
        val thread = AtomicReference<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ScanRepository.listTagsProbe = { name ->
            thread.set(name)
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        LibraryScreen.ioDispatcher = io

        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    waitFor("the card renders") { cards(activity).isNotEmpty() }
                    cards(activity).first().performLongClick()
                    idle()
                    activity.findViewById<View>(R.id.swSelTags).performClick()

                    assertTrue("listTags was asked for", entered.await(5, TimeUnit.SECONDS))
                    val dialog = ShadowDialog.getLatestDialog()
                    assertNotNull("the Tags sheet opened", dialog)
                    val message = dialog!!.findViewById<TextView>(R.id.swSheetMessage)
                    assertNotNull("the sheet carries a loading line", message)
                    assertTrue(
                        "a loading state is visible while tags load: '${message.text}'",
                        message.text.contains("Loading")
                    )
                    assertNull("no option rows until tags are loaded", dialog.findViewById<View>(R.id.swSheetItems))

                    release.countDown()
                    waitFor("tag options appear after loading") {
                        dialog.findViewById<ViewGroup>(R.id.swSheetItems) != null
                    }
                    assertNotEquals(
                        "listTags did not run on the main thread",
                        "main",
                        thread.get()
                    )
                }
            }
        } finally {
            release.countDown()
            ScanRepository.listTagsProbe = null
            ioThread.shutdownNow()
        }
    }
}
