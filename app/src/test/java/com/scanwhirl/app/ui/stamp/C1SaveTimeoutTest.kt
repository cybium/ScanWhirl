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

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun snackbar(screen: StampEditorActivity): String? =
        screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    @Test
    fun `back reopens after the save timeout and the late receipt is still reported`() {
        StampEditorActivity.saveTimeoutMs = 0
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Slow save")
        val gate = CompletableDeferred<Unit>()
        val copy = ScanDocument(
            id = "copy1", name = "Slow save - Signed", createdAt = 1, pageCount = 1,
            pdfFile = File(app.cacheDir, "copy1.pdf"), pageFiles = emptyList()
        )
        StampSaveRunner.submit(id) { gate.await(); copy }
        try {
            val screen = activity(id)
            screen.editor.add(
                PageStamp.Mark.Text("x", PageStamp.Box(0.1f, 0.1f, 0.3f, 0.12f))
            )
            screen.refresh()
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

            // The write finishes after the person has gone: the receipt must still arrive.
            gate.complete(Unit)
            assertTrue(
                "the late receipt is reported",
                waitFor("the receipt toast") {
                    ShadowToast.getTextOfLatestToast()?.contains("Slow save - Signed") == true
                }
            )
        } finally {
            gate.complete(Unit)
            StampSaveRunner.consume(id)
        }
    }
}
