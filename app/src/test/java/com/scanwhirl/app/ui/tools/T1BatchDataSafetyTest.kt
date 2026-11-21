package com.scanwhirl.app.ui.tools

import android.app.Application
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T1: the Batch chooser must actually show its search field once documents load, keep the
 * selection across rotation, and count documents a cancelled run never reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1BatchDataSafetyTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        ProGate.setTestOverride(app, true)
        repeat(10) { DocFixtures.saved(repo, app, pages = 1, name = "Doc $it") }
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    @Test
    fun `the search field appears after documents load`() {
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, emptyList())).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertNotNull("search must be shown for more than 8 documents",
                    activity.findViewById<EditText>(R.id.batchSearch))
            }
        }
    }

    @Test
    fun `selection survives rotation`() {
        val ids = repo.listDocuments().take(3).map { it.id }
        ActivityScenario.launch<BatchActivity>(BatchActivity.intent(app, ids)).use { scenario ->
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                assertEquals("three selected documents survive rotation",
                    activity.getString(R.string.tools_batch_count, 3, 10),
                    activity.findViewById<TextView>(R.id.batchCount).text.toString())
            }
        }
    }

    @Test
    fun `the summary counts documents a cancelled run never reached`() {
        val requested = listOf("a", "b", "c", "d")
        val results = listOf(
            ScanRepository.BatchItem("a", "A", true),
            ScanRepository.BatchItem("b", "B", false, reason = "failed")
        )
        assertEquals(2, BatchActivity.notProcessedCount(requested, results))
    }
}
