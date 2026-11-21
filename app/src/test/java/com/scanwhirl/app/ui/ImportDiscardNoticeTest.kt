package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ImportDiscardStore
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ImportDiscardNoticeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun waitUntil(condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test fun `durable discard resumes in Library above full navigation and survives rotation`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = PdfInvariants.dirDigest(File(repo.docsDir, id))
        val store = ImportDiscardStore(app)
        // No in-memory post(): a fresh Shell discovers the durable transaction itself.
        store.discard(id, TrashActions.undoDurationMs(20_000))
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            fun awaitNotice() = assertTrue("Discarded - Undo resumes from its disk token", waitUntil {
                var shown = false
                scenario.onActivity {
                    shown = it.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                        ?.text?.toString() == it.getString(R.string.u1_import_discarded)
                }
                shown
            })
            awaitNotice()
            scenario.recreate()
            awaitNotice()
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                repeat(3) {
                    idle()
                    decor.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
                    decor.layout(0, 0, 720, 1280)
                }
                val action = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_action)
                assertEquals(activity.getString(R.string.undo), action.text.toString())
                val nav = activity.findViewById<View>(R.id.swNavScan).parent as View
                val actionBounds = bounds(action)
                val navBounds = bounds(nav)
                assertTrue("Undo clears the entire nav host: $actionBounds / $navBounds",
                    actionBounds.bottom <= navBounds.top)
                assertTrue(action.performClick())
            }
            assertTrue("Undo restores the document", waitUntil { repo.getDocument(id) != null })
            assertEquals(before, PdfInvariants.dirDigest(File(repo.docsDir, id)))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
            assertNotNull("an old expiry job cannot purge an undone transaction", repo.getDocument(id))
            assertTrue(repo.listTrash().isEmpty())
        }
    }

    @Test fun `accessibility timeout extends the finite window without shortening its eight second floor`() {
        assertEquals(8_000, TrashActions.undoDurationMs(null as Int?))
        assertEquals(8_000, TrashActions.undoDurationMs(2_000))
        assertEquals(30_000, TrashActions.undoDurationMs(30_000))
    }

    private fun bounds(view: View): Rect {
        val xy = IntArray(2)
        view.getLocationOnScreen(xy)
        return Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    }
}
