package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
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
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Lane C1, item 4: Back is held only for a bounded time during a Save, and leaving after that
 * timeout must not lose the receipt when the write eventually finishes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class C1SaveTimeoutTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        StampEditorActivity.saveTimeoutMs = 60_000L
        ShadowToast.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun controller(id: String) = Robolectric.buildActivity(
        StampEditorActivity::class.java,
        Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
    ).setup()

    private fun activity(id: String): StampEditorActivity = controller(id).get()

    private val mark = PageStamp.Mark.Text("x", PageStamp.Box(0.1f, 0.1f, 0.3f, 0.12f))

    private fun snackbar(screen: StampEditorActivity): String? =
        screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    @Test
    fun `a timed out exit reports the late receipt through the fallback with its own label`() {
        StampEditorActivity.saveTimeoutMs = 0
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Slow save")
        val copy = ScanDocument(
            id = "copy1", name = "Slow save - Redacted", createdAt = 1, pageCount = 1,
            pdfFile = File(app.cacheDir, "copy1.pdf"), pageFiles = emptyList()
        )
        val firstController = controller(id)
        val screen = firstController.get()
        screen.editor.add(mark)
        screen.refresh()
        val edits = screen.editor.edits()
        val gate = CompletableDeferred<Unit>()
        // Set the receiver FIRST (onCreate), then submit: the runner captures the app context at
        // submit time, so the app-context fallback can really run once the screen is destroyed.
        StampSaveRunner.submit(id, SaveMeta(edits, R.string.c1_redacted_label)) {
            gate.await()
            copy
        }
        try {
            screen.findViewById<View>(R.id.saveCopy).performClick()
            idle()

            ShadowToast.reset()
            screen.onBackPressedDispatcher.onBackPressed()
            idle()
            assertEquals(
                "an honest message replaces 'please wait' once the bounded wait expires",
                app.getString(R.string.c1_saving_timed_out),
                snackbar(screen)
            )
            assertTrue("Back now leaves the screen", screen.isFinishing)

            // Destroying the screen clears its receipt target; the runner must use the fallback.
            firstController.pause().stop().destroy()
            idle()
            gate.complete(Unit)
            assertTrue(
                "the fallback reports the redacted receipt, not a generic saved-copy line",
                waitFor("the fallback receipt") {
                    ShadowToast.getTextOfLatestToast()?.contains(
                        app.getString(R.string.c1_redacted_saved, "Slow save - Redacted")
                    ) == true
                }
            )
        } finally {
            gate.complete(Unit)
            StampSaveRunner.consume(id)
        }
    }

    @Test
    fun `a timed out exit then reopen and Save makes exactly one copy`() {
        StampEditorActivity.saveTimeoutMs = 0
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Slow reopen")
        val firstController = controller(id)
        val first = firstController.get()
        first.editor.add(mark)
        first.refresh()
        val edits = first.editor.edits()

        val before = repo.listDocuments(includeVaulted = true).size
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        StampSaveRunner.submit(id, SaveMeta(edits, 0)) {
            writes++
            gate.await()
            withContext(Dispatchers.IO) { ScanRepository(app).stampDocument(id, edits, null) }
        }
        first.findViewById<View>(R.id.saveCopy).performClick()
        idle()

        // The bounded wait expires and the first screen really goes away.
        first.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the first screen finished after the timeout", first.isFinishing)
        firstController.pause().stop().destroy()
        idle()

        // A fresh editor with the same marks presses Save while the first write is still running.
        val secondController = controller(id)
        val second = secondController.get()
        second.editor.add(mark)
        second.refresh()
        second.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        assertEquals(
            "the reopened editor joins the running write instead of starting another",
            app.getString(R.string.c1_still_saving),
            second.findViewById<TextView>(R.id.saveCopy).text.toString()
        )

        gate.complete(Unit)
        assertTrue(
            "the single write completes",
            waitFor("the copy") { repo.listDocuments(includeVaulted = true).size >= before + 1 }
        )
        Thread.sleep(200)
        idle()
        assertEquals("the gated write ran exactly once", 1, writes)
        assertEquals(
            "reopening and pressing Save must not create a second copy",
            before + 1,
            repo.listDocuments(includeVaulted = true).size
        )
        assertEquals("exactly one receipt toast", 1, ShadowToast.shownToastCount())
        secondController.pause().stop().destroy()
    }
}
