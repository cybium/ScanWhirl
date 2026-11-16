package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * C05: a folder rename rewrites every document's meta.txt with an fsync per file. It must run
 * off the main thread (the button tap may not block the UI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryMetaOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
        Durable.resetForTests()
    }

    @After
    fun tearDown() {
        Durable.resetForTests()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        val scroll = host.getChildAt(0) as ScrollView
        return scroll.getChildAt(0) as LibraryScreen
    }

    private fun folderChip(activity: ShellActivity, name: String): TextView? {
        val row = activity.findViewById<ViewGroup>(R.id.swFolderRow)
        return (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.text.toString().startsWith("$name (") }
    }

    private fun <T : View> android.app.Dialog.need(id: Int): T =
        requireNotNull(findViewById(id)) { "view $id is missing" }

    @Test
    fun `renaming a folder writes its documents off the main thread`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "A")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "B")
        repo.setFolder(listOf(a, b), "Tax")

        val mainName = Thread.currentThread().name
        val syncedOn = java.util.Collections.synchronizedList(mutableListOf<String>())
        Durable.beforeFileSync = { file ->
            if (Thread.currentThread().name == mainName) syncedOn += file.name
        }

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val chip = await {
                var found: TextView? = null
                scenario.onActivity { found = folderChip(it, "Tax") }
                found != null
            }
            assertTrue("the folder chip appears", chip)

            scenario.onActivity { activity ->
                folderChip(activity, "Tax")!!.performLongClick()
                idle()
                val options = ShadowDialog.getLatestDialog()
                options.need<View>(R.id.swSheetPrimary).performClick()
                idle()
                val rename = ShadowDialog.getLatestDialog()
                rename.need<EditText>(R.id.swSheetInput).setText("Tax 2026")
                rename.need<View>(R.id.swSheetPrimary).performClick()
            }

            val renamed = await {
                repo.getDocument(a)?.folder == "Tax 2026" && repo.getDocument(b)?.folder == "Tax 2026"
            }
            assertTrue("the folder rename is applied", renamed)
            assertEquals(
                "meta.txt fsyncs on the main thread: $syncedOn",
                emptyList<String>(),
                syncedOn
            )
        }
    }
}
