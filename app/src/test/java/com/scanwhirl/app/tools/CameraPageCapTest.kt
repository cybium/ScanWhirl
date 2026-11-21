package com.scanwhirl.app.tools
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

/**
 * T15: the camera scan's page cap is ScanWhirl's own setting (ML Kit documents no maximum),
 * so 30 -> 60 needs the app to say when a session stopped at the cap. A session of exactly
 * [ScanLauncher.CAMERA_PAGE_LIMIT] pages shows one calm prompt after the verified save, on
 * the screen the pages land on, and "Add more" reuses the existing add-pages flow on the
 * same document. Fewer pages save and open exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CameraPageCapTest {


    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private var n = 0

    private fun pages(count: Int): List<Uri> = (0 until count).map {
        // Small synthetic pages: the cap is about page count, not pixel size.
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.JPEG, width = 24, height = 16)
        val uri = Uri.parse("content://scanner/cap_${n++}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        uri
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for $what")
    }

    /** The showing sheet, if any: the screen the pages land on. */
    private fun showingSheet(): BottomSheetDialog? =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } as? BottomSheetDialog

    private fun sheetMessage(sheet: BottomSheetDialog): String =
        sheet.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()

    // ------------------------------------------------------------------ the cap itself

    @Test(timeout = 60_000)
    fun `the camera session cap is 60 and the camera options ask for it`() {
        assertEquals("one camera session stops at 60 pages", 60, ScanLauncher.CAMERA_PAGE_LIMIT)
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a ->
                val launcher = ScanLauncher(a, repo, a) {}
                assertEquals(
                    "the camera path uses the named cap",
                    ScanLauncher.CAMERA_PAGE_LIMIT,
                    launcher.cameraConfig().pageLimit
                )
                // The real builder has no getter, but it must accept the cap we ask for.
                assertNotNull("the camera options build", launcher.cameraOptions())

                assertTrue(launcher.cameraSessionCapped(ScanLauncher.CAMERA_PAGE_LIMIT))
                assertFalse("one page short is not a capped session", launcher.cameraSessionCapped(59))
                assertFalse("over the cap cannot come back from the scanner", launcher.cameraSessionCapped(61))
                assertFalse(launcher.cameraSessionCapped(0))
            }
        }
    }

    // ------------------------------------------------------------------ the prompt

    @Test(timeout = 60_000)
    fun `a session of exactly the cap pages shows the prompt where the pages land`() {
        Prefs.setAutoCopyToDocuments(app, false)
        val scanned = pages(ScanLauncher.CAMERA_PAGE_LIMIT)
        val expected = app.getString(R.string.cap_scan_message, ScanLauncher.CAMERA_PAGE_LIMIT)
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> a.onScannerPages(scanned) }
            await("the saved document and its cap prompt") {
                repo.listDocuments().isNotEmpty() && showingSheet()?.let { sheetMessage(it) == expected } == true
            }
            val doc = repo.listDocuments().single()
            assertEquals(
                "all pages are saved before the prompt",
                ScanLauncher.CAMERA_PAGE_LIMIT,
                doc.pageCount
            )
            val sheet = showingSheet()
            assertNotNull("the prompt is on screen", sheet)
            assertEquals(expected, sheetMessage(sheet!!))
            assertTrue("the number comes from the cap, not a copy", expected.contains("60"))
        }
    }

    @Test(timeout = 60_000)
    fun `fewer pages than the cap save and open without a prompt`() {
        Prefs.setAutoCopyToDocuments(app, false)
        val scanned = pages(2)
        val started = mutableListOf<Intent>()
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a -> a.onScannerPages(scanned) }
            await("the new document to open") {
                scenario.onActivity { a ->
                    while (true) started += shadowOf(a).nextStartedActivity ?: break
                }
                started.any { it.component?.className == DocumentViewerActivity::class.java.name }
            }
            assertNull("no cap prompt below the cap", showingSheet())
        }
        val doc = repo.listDocuments().single()
        val viewer = started.filter { it.component?.className == DocumentViewerActivity::class.java.name }
        assertEquals("the new document opens exactly once", 1, viewer.size)
        assertEquals(
            "a short session opens the document as before, with no insert step",
            DocumentViewerActivity.intent(app, doc.id).toUri(Intent.URI_INTENT_SCHEME),
            viewer.single().toUri(Intent.URI_INTENT_SCHEME)
        )
    }

    @Test(timeout = 60_000)
    fun `Add more reuses the existing add pages entry on the same document`() {
        val docId = DocFixtures.saved(repo, app, pages = 2, name = "Cap add more")
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            var started: Intent? = null
            scenario.onActivity { a ->
                ScanLauncher(a, repo, a) {}.offerAddMoreAfterCap(docId)
                idle()
                val sheet = showingSheet()
                assertNotNull("the prompt is showing", sheet)
                val primary = sheet!!.findViewById<TextView>(R.id.swSheetPrimary)!!
                assertEquals(app.getString(R.string.cap_scan_add_more), primary.text.toString())
                primary.performClick()
                idle()
                started = shadowOf(a).nextStartedActivity
            }
            assertNotNull("Add more starts a screen", started)
            assertEquals(
                "Add more opens the viewer's existing insert-pages entry for this document",
                DocumentViewerActivity.insertIntent(app, docId).toUri(Intent.URI_INTENT_SCHEME),
                started!!.toUri(Intent.URI_INTENT_SCHEME)
            )
        }
        assertEquals("the document keeps every page", 2, repo.listDocuments().single { it.id == docId }.pageCount)
    }

    @Test(timeout = 60_000)
    fun `Done closes the prompt and keeps the saved pages`() {
        val docId = DocFixtures.saved(repo, app, pages = 3, name = "Cap done")
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            var started: Intent? = null
            scenario.onActivity { a ->
                ScanLauncher(a, repo, a) {}.offerAddMoreAfterCap(docId)
                idle()
                val sheet = showingSheet()
                assertNotNull("the prompt is showing", sheet)
                val secondary = sheet!!.findViewById<TextView>(R.id.swSheetSecondary)!!
                assertEquals(app.getString(R.string.cap_scan_done), secondary.text.toString())
                while (shadowOf(a).nextStartedActivity != null) Unit
                secondary.performClick()
                idle()
                assertFalse("Done dismisses the prompt", sheet.isShowing)
                started = shadowOf(a).nextStartedActivity
            }
            assertNull("Done starts nothing else", started)
        }
        val kept = repo.listDocuments().single { it.id == docId }
        assertEquals("Done keeps every page", 3, kept.pageCount)
        assertEquals(3, kept.pageFiles.count { it.exists() })
    }

    @Test(timeout = 60_000)
    fun `the prompt's two buttons are 48 dp targets with text`() {
        val docId = DocFixtures.saved(repo, app, pages = 1, name = "Cap targets")
        ActivityScenario.launch<ShellActivity>(Intent(app, ShellActivity::class.java)).use { scenario ->
            scenario.onActivity { a ->
                ScanLauncher(a, repo, a) {}.offerAddMoreAfterCap(docId)
                idle()
                val sheet = showingSheet()
                assertNotNull("the prompt is showing", sheet)
                val density = a.resources.displayMetrics.density
                val min48 = (48 * density).toInt()
                for (id in listOf(R.id.swSheetPrimary, R.id.swSheetSecondary)) {
                    val button = sheet!!.findViewById<TextView>(id)!!
                    // SAFE-UNLOCK-CONSENT: the primary is now wrap_content with a 54dp
                    // minimum, so a long label grows at 200% font instead of being cut. The
                    // declared target is the larger of the fixed height and the minimal one.
                    val declared = maxOf(button.layoutParams.height, button.minHeight)
                    assertTrue("button $id is a 48 dp target", declared >= min48)
                    assertTrue("button $id is clickable", button.isClickable)
                    val label = button.text.toString()
                    assertTrue("button $id has a label", label.isNotBlank())
                }
                sheet!!.dismiss()
            }
        }
    }

    // ------------------------------------------------------------------ both languages

    @Test(timeout = 60_000)
    fun `the prompt exists in English and Hindi with the same number placeholder`() {
        val en = parseStrings(File("src/main/res/values/strings.xml"))
        val hi = parseStrings(File("src/main/res/values-hi/strings.xml"))
        for (key in listOf("cap_scan_message", "cap_scan_add_more", "cap_scan_done")) {
            assertNotNull("$key is missing in English", en[key])
            assertNotNull("$key is missing in Hindi", hi[key])
            assertTrue("$key must not be empty in English", !en.getValue(key).isBlank())
            assertTrue("$key must not be empty in Hindi", !hi.getValue(key).isBlank())
        }

        val enMessage = en.getValue("cap_scan_message")
        val hiMessage = hi.getValue("cap_scan_message")
        assertTrue("English takes the number as a placeholder", enMessage.contains("%1\$d"))
        assertEquals(
            "both languages take the same number",
            placeholders(enMessage),
            placeholders(hiMessage)
        )
        // The number is a placeholder, not a copy of 60.
        assertFalse("English must not hardcode the cap", enMessage.contains("60"))
        assertFalse("Hindi must not hardcode the cap", hiMessage.contains("60"))
        assertTrue(String.format(Locale.ROOT, enMessage, 60).contains("60"))
        assertTrue(String.format(Locale.ROOT, hiMessage, 60).contains("60"))
        assertTrue(
            "Hindi stays Hindi",
            hiMessage.any { it.code in 0x0900..0x097F }
        )
        assertFalse("पेज is a rejected glossary term", hiMessage.contains("पेज"))
        assertTrue("Hindi uses the glossary's word for pages", hiMessage.contains("पन्ने"))
    }

    private fun parseStrings(file: File): Map<String, String> {
        val xml = file.readText(Charsets.UTF_8)
        return Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun placeholders(text: String): List<String> =
        Regex("%(?:\\d+\\$)?([sdf])").findAll(text).map { it.groupValues[1] }.sorted().toList()
}
