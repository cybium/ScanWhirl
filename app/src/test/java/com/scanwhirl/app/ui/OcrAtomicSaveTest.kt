package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Sha256
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrAtomicSaveTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private fun idleUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test fun `Save asks when recognition lands after comparison on an edited unread page`() {
        checkRace("कुल राशि\t1250\r\n😀")
    }

    @Test fun `Save asks even when an unread page becomes recognized empty text`() {
        checkRace("")
    }

    private fun checkRace(recognized: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional atomic OCR")
        val key = "page_000.jpg"
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use { c ->
            val activity = c.get()
            val edit = activity.findViewById<EditText>(R.id.editText)
            activity.findViewById<View>(R.id.btnEditText).performClick()
            edit.setText("my unsaved edit")
            val published = AtomicBoolean()
            activity.beforeAtomicOcrWriteForTests = {
                val source = File(File(repo.docsDir, id), key)
                assertEquals(1, OcrStore(repo.docsDir).putRecognizedPages(id,
                    listOf(OcrStore.Recognized(key, Sha256.of(source), recognized, null))).stored)
                published.set(true)
            }
            activity.findViewById<View>(R.id.btnSaveEdits).performClick()
            assertTrue("save reached the deterministic interleaving", idleUntil { published.get() })
            assertTrue("save attempt completed", idleUntil {
                val field = OcrEditorActivity::class.java.getDeclaredField("saving").apply { isAccessible = true }
                !field.getBoolean(activity)
            })
            assertEquals("recognized text must never be overwritten", recognized, repo.ocrDoc(id)[key])
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("conflict asks about the newly stored text", sheet)
            assertTrue(sheet.isShowing)
            assertEquals("Use the new text", sheet.findViewById<TextView>(R.id.swSheetPrimary).text.toString())
            assertEquals("my unsaved edit", edit.text.toString())
            assertFalse(activity.isFinishing)
            activity.beforeAtomicOcrWriteForTests = null
        }
    }
}
