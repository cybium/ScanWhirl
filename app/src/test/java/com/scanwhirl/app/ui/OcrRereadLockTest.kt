package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F-01 (final release judge, ee733f77): after "Read again" was confirmed, the recognition runs in
 * the background while the Text screen stays open. Edit text, Tidy, Undo/Redo and Save edits stayed
 * usable, but the screen recreates itself when the re-read finishes and deliberately does not carry
 * unsaved text across that recreation, so anything typed in that window was silently dropped.
 * While a confirmed re-read runs, the screen must not accept new edits at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrRereadLockTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var release: CountDownLatch
    private lateinit var started: CountDownLatch

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        release = CountDownLatch(1)
        started = CountDownLatch(1)
        OcrEngine.rawReader = { _, script ->
            if (script == OcrScript.DEVANAGARI) {
                started.countDown()
                // Hold the re-read in flight until the test has tried to edit.
                release.await(10, TimeUnit.SECONDS)
                OcrRawPage(
                    600, 800,
                    listOf(OcrRawLine("भारत सरकार", OcrBox(20, 40, 560, 70), OcrScript.DEVANAGARI))
                )
            } else {
                OcrRawPage(
                    600, 800,
                    listOf(OcrRawLine("dqDrh iqDrh", OcrBox(20, 40, 560, 70), OcrScript.LATIN))
                )
            }
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        OcrEngine.rawReader = null
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun seed(): String {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Hindi notice")
        repo.updateOcrPage(id, "page_000.jpg", "dqDrh iqDrh")
        return id
    }

    private fun intent(id: String): Intent =
        Intent(app, OcrEditorActivity::class.java).putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)

    private fun startReread(activity: OcrEditorActivity, id: String) {
        val m = OcrEditorActivity::class.java.getDeclaredMethod(
            "reReadDocument", String::class.java, OcrLanguage::class.java
        )
        m.isAccessible = true
        m.invoke(activity, id, OcrLanguage.HINDI)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the re-read reached the recognizer", started.await(8, TimeUnit.SECONDS))
    }

    private fun editing(activity: OcrEditorActivity): Boolean {
        val f = OcrEditorActivity::class.java.getDeclaredField("editing")
        f.isAccessible = true
        return f.getBoolean(activity)
    }

    @Test
    fun `while a confirmed reread runs no control lets the person start editing`() {
        val id = seed()
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            startReread(activity, id)

            val editButton = activity.findViewById<View>(R.id.btnEditText)
            assertFalse("Edit text is disabled during the re-read", editButton.isEnabled)
            editButton.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Edit text does not enter edit mode during the re-read", editing(activity))
            assertNull(
                "the text field accepts no typing during the re-read",
                activity.findViewById<EditText>(R.id.editText).keyListener
            )

            val tidy = activity.findViewById<View>(R.id.btnTidyText)
            assertFalse("Tidy is disabled during the re-read", tidy.isEnabled)
            tidy.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Tidy does not enter edit mode during the re-read", editing(activity))

            assertFalse(
                "Undo is disabled during the re-read",
                activity.findViewById<View>(R.id.btnUndo).isEnabled
            )
            assertFalse(
                "Redo is disabled during the re-read",
                activity.findViewById<View>(R.id.btnRedo).isEnabled
            )
        }
    }

    @Test
    fun `Save edits during a reread writes nothing over the stored text`() {
        val id = seed()
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            startReread(activity, id)

            // Even if a stale handler reaches Save edits, it must not write while re-reading.
            val save = OcrEditorActivity::class.java.getDeclaredMethod(
                "saveEdits", Boolean::class.javaPrimitiveType
            )
            save.isAccessible = true
            save.invoke(activity, false)
            shadowOf(Looper.getMainLooper()).idle()
            // A confirmed re-read clears the page first (OcrReindex.clearForReread); nothing
            // from the old screen may be written into that cleared page while it runs.
            assertNull(
                "Save edits writes no old text into the page being re-read",
                repo.ocrDoc(id)?.get("page_000.jpg")
            )
            assertFalse(
                "Save edits is disabled during the re-read",
                activity.findViewById<View>(R.id.btnSaveEdits).isEnabled
            )
        }
    }
}
