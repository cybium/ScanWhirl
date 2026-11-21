package com.scanwhirl.app.ui.stamp

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E1 defect 4: Back during "Saving" used to finish the screen immediately. The copy kept being
 * written on the process scope, but its receipt (result + toast) went to a dead Activity, and a
 * reopened editor could Save the same marks again. The screen must stay until the receipt is
 * delivered; Back while saving is blocked with a notice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampSavingBackTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(context, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(label: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $label")
    }

    @Test
    fun `back is blocked while saving and the receipt is delivered before closing`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val source = repo.getDocument(id)!!
        // Occupy the runner with a save we control: the editor's Save joins this promise, so
        // "Saving" is deterministic instead of racing a real disk write.
        val screen = activity(id)
        screen.editor.add(PageStamp.Mark.Text("RECEIPT", PageStamp.Box(0.1f, 0.1f, 0.5f, 0.2f)))
        val edits = screen.editor.edits()
        val release = CompletableDeferred<Unit>()
        StampSaveRunner.submit(id, SaveMeta(edits, 0)) {
            release.await()
            source
        }
        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()

        assertEquals(
            "the Save button shows it joined the running write",
            context.getString(R.string.c1_still_saving),
            screen.findViewById<TextView>(R.id.saveCopy).text.toString()
        )
        assertFalse("not finishing before Back", screen.isFinishing)

        screen.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse("Back must not close the screen mid-save", screen.isFinishing)

        release.complete(Unit)
        waitFor("the receipt to be delivered") {
            shadowOf(screen).resultCode == Activity.RESULT_OK &&
                shadowOf(screen).resultIntent != null
        }
        val result = shadowOf(screen).resultIntent!!
        assertNotNull("the new copy id is returned", result.getStringExtra(StampEditorActivity.EXTRA_NEW_DOC_ID))
        assertTrue("the screen closes only after the receipt", screen.isFinishing)

        StampSaveRunner.consume(id)
    }
}
