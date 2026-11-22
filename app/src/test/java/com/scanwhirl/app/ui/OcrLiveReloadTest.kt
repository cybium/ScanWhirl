package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.OcrReindex
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.atomic.AtomicInteger

/**
 * D9-1 (device Build 9): a re-read finished while the Text screen was open. The store held the
 * correct Devanagari, but the screen kept the old mojibake and "Save edits" wrote that stale
 * text back over the good OCR. The screen now follows the store: pages the person has not
 * edited reload silently, pages they edited are never replaced without asking, and Save never
 * writes a page whose stored text changed underneath the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrLiveReloadTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    private val hindiPageOne = "कुल राशि 1250"
    private val hindiPageTwo = "सार्वजनिक सूचना"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        OcrEngine.rawReader = { _, script ->
            when (script) {
                OcrScript.DEVANAGARI -> OcrRawPage(
                    600, 800,
                    listOf(
                        OcrRawLine(hindiPageOne, OcrBox(20, 40, 560, 70), OcrScript.DEVANAGARI),
                        OcrRawLine(hindiPageTwo, OcrBox(20, 120, 560, 150), OcrScript.DEVANAGARI)
                    )
                )
                else -> OcrRawPage(
                    600, 800,
                    listOf(OcrRawLine("dqDrh iqDrh", OcrBox(20, 40, 560, 70), OcrScript.LATIN))
                )
            }
        }
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun seed(vararg texts: String): String {
        val id = DocFixtures.saved(repo, app, pages = texts.size, name = "Hindi notice")
        texts.forEachIndexed { i, text ->
            repo.updateOcrPage(id, "page_%03d.jpg".format(i), text)
        }
        return id
    }

    private fun intent(id: String): Intent =
        Intent(app, OcrEditorActivity::class.java).putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)

    private fun idleUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun rereadHindi(id: String) {
        runBlocking {
            OcrLanguageSettings.set(app, OcrLanguage.HINDI)
            OcrReindex(repo).reRecognise(id, OcrLanguage.HINDI, replaceCorrections = true)
        }
    }

    @Test(timeout = 60_000)
    fun `a Hindi reread replaces the mojibake on the open screen`() {
        val id = seed("dqDrh iqDrh", "dqDrh")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            val edit = activity.findViewById<EditText>(R.id.editText)
            assertTrue("the old text is on screen first", edit.text.toString().contains("dqDrh"))

            rereadHindi(id)

            assertTrue(
                "the open screen shows the freshly read Devanagari",
                idleUntil { edit.text.toString().contains(hindiPageOne) }
            )
            assertFalse("the stale mojibake is gone", edit.text.toString().contains("dqDrh"))
            assertEquals(
                "the page indicator still counts both pages",
                "Page 1 of 2",
                activity.findViewById<TextView>(R.id.ocrPageIndicator).text.toString()
            )
        }
    }

    @Test(timeout = 60_000)
    fun `Save edits after a reread keeps the new stored text byte-identical`() {
        val id = seed("dqDrh iqDrh")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            val edit = activity.findViewById<EditText>(R.id.editText)

            rereadHindi(id)
            assertTrue(idleUntil { edit.text.toString().contains(hindiPageOne) })
            val stored = repo.ocrDoc(id)

            activity.findViewById<View>(R.id.btnEditText).performClick()
            activity.findViewById<View>(R.id.btnSaveEdits).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "nothing the person did not change is written back over the re-read text",
                stored,
                repo.ocrDoc(id)
            )
            assertTrue(stored.values.joinToString("\n").contains(hindiPageOne))
        }
    }

    @Test(timeout = 60_000)
    fun `an edited page is never replaced by a reread without asking`() {
        val id = seed("old line", "second old line")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            val edit = activity.findViewById<EditText>(R.id.editText)
            activity.findViewById<View>(R.id.btnEditText).performClick()
            edit.setText("my handwritten note")

            rereadHindi(id)
            shadowOf(Looper.getMainLooper()).idle()

            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("the person is asked before their edit is replaced", sheet)
            assertEquals(
                "Use the new text",
                sheet.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
            assertEquals(
                "Keep my edit",
                sheet.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
            )
            assertEquals(
                "the edit stays on screen until the person chooses",
                "my handwritten note",
                edit.text.toString()
            )

            sheet.findViewById<View>(R.id.swSheetSecondary).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Keep my edit keeps the typed text", "my handwritten note", edit.text.toString())
            assertTrue(
                "the re-read text is still stored for the page that was not edited",
                repo.ocrDoc(id)["page_000.jpg"].orEmpty().contains(hindiPageOne)
            )

            activity.findViewById<View>(R.id.btnSaveEdits).performClick()
            assertTrue("the person's choice is what gets stored", idleUntil {
                repo.ocrDoc(id)["page_000.jpg"] == "my handwritten note"
            })
        }
    }

    @Test(timeout = 60_000)
    fun `Save edits refuses to write over text that changed underneath`() {
        val id = seed("first page")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val activity = c.get()
            val edit = activity.findViewById<EditText>(R.id.editText)
            activity.findViewById<View>(R.id.btnEditText).performClick()
            edit.setText("my edit")

            // A re-read landed in the store without this screen seeing it (e.g. while stopped).
            repo.updateOcrPages(id, mapOf("page_000.jpg" to hindiPageOne))

            activity.findViewById<View>(R.id.btnSaveEdits).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("Save asks instead of overwriting", sheet)
            assertEquals(
                "nothing was written yet",
                hindiPageOne,
                repo.ocrDoc(id)["page_000.jpg"]
            )
            assertEquals("the person's text is still in the editor", "my edit", edit.text.toString())

            sheet.findViewById<View>(R.id.swSheetPrimary).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "Use the new text shows the stored Devanagari",
                hindiPageOne,
                edit.text.toString()
            )
            assertEquals(hindiPageOne, repo.ocrDoc(id)["page_000.jpg"])
        }
    }

    @Test(timeout = 60_000)
    fun `recreation after a Hindi reread displays fresh OCR without an unsaved warning`() {
        val id = seed("HRT HYO old Latin OCR")
        val hindi = "\u092d\u093e\u0930\u0924 \u0938\u0930\u0915\u093e\u0930"
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            assertFalse(
                "Android must not auto-restore old EditText text over fresh repository OCR",
                c.get().findViewById<EditText>(R.id.editText).isSaveEnabled
            )
            assertEquals(
                "HRT HYO old Latin OCR",
                c.get().findViewById<EditText>(R.id.editText).text.toString()
            )
            // Recognition completes while the previous activity is stopped: the
            // saved baseline is old, but the repository now contains real Hindi.
            c.pause().stop()
            repo.updateOcrPages(id, mapOf("page_000.jpg" to hindi))
            c.recreate()
            val refreshed = c.get()
            assertEquals(
                "the current stored OCR must win over an untouched stale baseline",
                hindi,
                refreshed.findViewById<EditText>(R.id.editText).text.toString()
            )
            // No manual edits: pressing Back must finish, not show Save/Discard.
            refreshed.onBackPressedDispatcher.onBackPressed()
            assertTrue("fresh OCR is not an unsaved manual edit", refreshed.isFinishing)
            assertEquals(hindi, repo.ocrDoc(id)["page_000.jpg"])
        }
    }

    @Test(timeout = 60_000)
    fun `recreation keeps a real unsaved correction instead of replacing it`() {
        val id = seed("original saved line")
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val first = c.get()
            first.findViewById<View>(R.id.btnEditText).performClick()
            first.findViewById<EditText>(R.id.editText).setText("manual unsaved correction")
            c.recreate()
            val after = c.get()
            assertEquals(
                "only an explicitly edited page carries its old baseline",
                "manual unsaved correction",
                after.findViewById<EditText>(R.id.editText).text.toString()
            )
            after.onBackPressedDispatcher.onBackPressed()
            assertFalse("manual edits still require confirmation", after.isFinishing)
            assertEquals("original saved line", repo.ocrDoc(id)["page_000.jpg"])
        }
    }

    @Test(timeout = 60_000)
    fun `pages that used up their automatic retries say so`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Damaged page")
        val calls = AtomicInteger()
        ScanWhirlApp.from(app).indexer = OcrIndexer(
            repo,
            CoroutineScope(Dispatchers.Unconfined),
            recognize = {
                calls.incrementAndGet()
                throw IllegalStateException("page could not be decoded")
            },
            autoRun = false
        )
        runBlocking { repeat(4) { ScanWhirlApp.from(app).indexer.index(id) } }
        assertEquals("automatic retries are bounded", 3, calls.get())

        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id)).setup().use { c ->
            val status = c.get().findViewById<TextView>(R.id.ocrStatusText).text.toString()
            assertTrue(
                "the screen says the pages could not be read after several tries: $status",
                status.contains("even after several tries")
            )
        }
    }
}
