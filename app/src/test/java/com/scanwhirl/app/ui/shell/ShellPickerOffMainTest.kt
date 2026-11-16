package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * C08: the Searchable PDF / Enhance / Text document picker stat'd the whole library inside the
 * click listener. The listing now runs off the main thread and the sheet follows it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellPickerOffMainTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setTestOverride(app, null)
    }

    @After
    fun tearDown() {
        ScanRepository.readProbe = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    @Test
    fun `the tool document picker lists off the main thread`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Pick me")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            val mainName = Thread.currentThread().name
            val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
            scenario.onActivity { activity ->
                idle()
                ScanRepository.readProbe = { threads += it }
                activity.open(requireNotNull(ToolRegistry.byId("searchable_pdf")))
            }

            val shown = await {
                val dialog = ShadowDialog.getLatestDialog()
                dialog != null && dialog.findViewById<android.view.View>(R.id.swDocPickerList) != null
            }
            assertTrue("the picker sheet opens when the list arrives", shown)
            assertNotNull(ShadowDialog.getLatestDialog())
            assertTrue("the library was listed", threads.isNotEmpty())
            assertFalse(
                "listDocuments ran on the main thread: $threads",
                threads.contains(mainName)
            )
        }
    }
}
