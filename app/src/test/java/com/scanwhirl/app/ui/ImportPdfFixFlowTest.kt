package com.scanwhirl.app.ui

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.FakeMediaStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Owner bugs F5/W2: an imported PDF opens in the viewer as before, but the first Back from that
 * first look asks "Keep imported document?" and Discard deletes exactly that document and its
 * cache preview. Every other viewer entry keeps its plain Back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportPdfFixFlowTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        Faults.resetAll()
        // T44: the viewer's save-to-Documents goes through MediaStore, exactly like the phone.
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
        // androidx FileProvider caches its roots per authority; a fresh cacheDir per test makes a
        // stale entry fail the share (W1 shares from the Actions sheet here).
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun tearDown() {
        Faults.resetAll()
        media.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    /** The unlocked preview the viewer builds for an imported PDF (ScanRepository.dropDerivedCaches). */
    private fun previewFiles(id: String): List<File> = listOf(
        File(app.cacheDir, "preview_$id.pdf"),
        File(File(app.cacheDir, "documents"), "preview_$id.pdf")
    )

    private fun previews(cache: List<File>): List<File> = cache.mapNotNull { file ->
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        file.takeIf { it.isFile }
    }

    @Test
    fun `the first Back after an import asks and Discard deletes that one document`() {
        val imported = DocFixtures.saved(repo, app, pages = 2, name = "Imported PDF")
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Other doc")
        val caches = previews(previewFiles(imported))

        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no Keep question" }
                assertEquals(
                    app.getString(R.string.fixflow_import_keep_title),
                    dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                assertEquals(
                    "Keep is the safe primary answer",
                    app.getString(R.string.fixflow_keep),
                    dialog.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
                )
                assertEquals(
                    "T44: the destructive answer names the import, not a generic discard",
                    app.getString(R.string.fixflow_import_discard),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary).text.toString()
                )
                dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
            }
            assertTrue("Discard deletes the imported document", waitUntil { repo.getDocument(imported) == null })
        }

        assertNull("the imported document is gone", repo.getDocument(imported))
        assertNotNull("every other document is untouched", repo.getDocument(other))
        assertEquals(1, repo.listDocuments().size)
        // The preview cache is deleted asynchronously after the document row: wait for it instead of
        // racing it (failed once on a slow cloud runner, integration run 44265863059).
        assertTrue("its cache preview is gone too: $caches", waitUntil { caches.none { it.exists() } })
    }

    @Test
    fun `Keep leaves the imported document in the library`() {
        val imported = DocFixtures.saved(repo, app, pages = 1, name = "Imported PDF")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                requireNotNull(ShadowDialog.getLatestDialog())
                    .findViewById<View>(R.id.swSheetPrimary)
                    .performClick()
                idle()
            }
            assertTrue("Keep closes the viewer", waitUntil {
                var finishing = false
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            })
        }
        assertNotNull("Keep keeps the imported document", repo.getDocument(imported))
    }

    /**
     * T44 (phone build 5): the import viewer offered "Discard" even after the user had saved a copy
     * of the document into Documents/ScanWhirl, and Discard deleted the library document the copy
     * was recorded against. An import the user has acted on is theirs: Back just closes.
     */
    @Test
    fun `an import the user saved to Documents is kept on Back, with no delete question`() {
        val imported = DocFixtures.saved(repo, app, pages = 2, name = "Saved import")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }
            assertTrue(
                "the saved copy is recorded for this import",
                waitUntil { repo.exportRecordsOf(imported).isNotEmpty() }
            )
            var finishing = false
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                finishing = activity.isFinishing
            }
            assertTrue("Back closes the viewer", finishing)
            assertNull(
                "no Keep/Discard question once the user saved a copy",
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            )
        }
        assertNotNull("the import stays in the library", repo.getDocument(imported))
        assertEquals("its saved copy stays too", 1, media.scanwhirlPdfs().size)
    }

    /**
     * W1: an import the user shared from the document screen's Actions sheet is theirs. Back must
     * close the screen without a Keep/Discard question and without deleting it.
     */
    @Test
    fun `an import shared from the Actions sheet is kept on Back, with no delete question`() {
        val imported = DocFixtures.saved(repo, app, pages = 2, name = "Fee receipt Oct - FAKE")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnMore).performClick()
                idle()
                val sheet = requireNotNull(ShadowDialog.getLatestDialog()) {
                    "the Actions sheet opens"
                }
                requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:share")
                ) { "the sheet has a Share row" }.performClick()
                assertTrue(
                    "Share reaches the chooser",
                    waitUntil { shadowOf(activity).peekNextStartedActivity() != null }
                )
            }
            var finishing = false
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                finishing = activity.isFinishing
            }
            assertTrue("Back closes the viewer", finishing)
            assertNull(
                "no Keep/Discard question once the import was shared",
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            )
        }
        assertNotNull("the shared import stays in the library", repo.getDocument(imported))
    }

    /**
     * T44: the untouched-import question must name what the destructive answer does. Its old label
     * was the generic "Discard" ("don't keep changes" in Hindi), which reads like dropping edits.
     */
    @Test
    fun `the untouched import question names the delete, not a generic discard`() {
        val imported = DocFixtures.saved(repo, app, pages = 1, name = "Fresh import")
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no keep question" }
                assertEquals(
                    app.getString(R.string.fixflow_import_keep_title),
                    dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
                )
                assertEquals(
                    "the destructive answer says it deletes the import",
                    app.getString(R.string.fixflow_import_discard),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary)?.text?.toString()
                )
                assertEquals(
                    "and it never uses the generic Discard wording",
                    false,
                    app.getString(R.string.fixflow_import_discard) == app.getString(R.string.discard)
                )
            }
        }
    }

    @Test
    fun `a normal viewer entry keeps its plain Back`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Library doc")
        var finishing = false
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                idle()
                finishing = activity.isFinishing
            }
        }
        assertTrue("Back leaves an ordinary document view", finishing)
        assertFalse("no Keep question for an ordinary entry", ShadowDialog.getLatestDialog()?.isShowing == true)
        assertNotNull("the document stays", repo.getDocument(id))
    }
}
