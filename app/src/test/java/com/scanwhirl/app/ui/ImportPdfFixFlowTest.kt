package com.scanwhirl.app.ui

import android.app.Application
import android.content.pm.ProviderInfo
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.ScanRepository.Companion.TrashStage
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.FakeMediaStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.IOException

/**
 * Owner bugs F5/W2: an imported PDF opens in the viewer as before, but the first Back from that
 * first look asks "Keep imported document?" and offers a reversible move to Trash.
 * Every other viewer entry keeps its plain Back.
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
        TrashActions.takePending()
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
        TrashActions.takePending()
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
    fun `the first Back after an import can move only that import to Trash and restore it`() {
        val imported = DocFixtures.saved(repo, app, pages = 2, name = "Imported PDF")
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Other doc")
        val originalPdf = requireNotNull(repo.getDocument(imported)).pdfFile.readBytes()
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
                dialog.findViewById<View>(R.id.swSheetSecondary).performClick()
            }
            assertTrue("the import action closes the viewer", waitUntil {
                var finishing = false
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            })
        }

        assertNull("the import is no longer in the library", repo.getDocument(imported))
        assertNotNull("every other document is untouched", repo.getDocument(other))
        assertEquals(1, repo.listDocuments().size)
        assertEquals("the import must be present in Trash, not permanently deleted",
            listOf(imported), repo.listTrash().map { it.originalId })
        val entry = repo.listTrash().single()
        assertEquals(TrashActions.Moved(listOf(entry.key), listOf("Imported PDF"), 0),
            TrashActions.takePending())
        assertTrue("the derived preview is invalidated: $caches", caches.none { it.exists() })
        val restored = repo.restoreFromTrash(entry.key)
        assertEquals(imported, restored.id)
        assertEquals(2, restored.pageCount)
        assertArrayEquals(originalPdf, restored.pdfFile.readBytes())
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test
    fun `a failed Trash move never falls back to permanent deletion`() {
        val imported = DocFixtures.saved(repo, app, pages = 1, name = "Fresh import")
        val originalPdf = requireNotNull(repo.getDocument(imported)).pdfFile.readBytes()
        ScanRepository.trashFault = { stage ->
            if (stage == TrashStage.BEFORE_MOVE) throw IOException("Fictional move failure")
        }
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, imported, freshImport = true)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                requireNotNull(ShadowDialog.getLatestDialog())
                    .findViewById<View>(R.id.swSheetSecondary).performClick()
            }
            assertTrue("the failed move displays the existing failure receipt, not a success", waitUntil {
                var failedHonestly = false
                scenario.onActivity { activity ->
                    val snackbar = activity.findViewById<TextView>(
                        com.google.android.material.R.id.snackbar_text
                    )
                    failedHonestly = !activity.isFinishing &&
                        snackbar?.text?.toString() == activity.getString(R.string.trash_move_failed)
                }
                failedHonestly
            })
        }
        assertArrayEquals(originalPdf, requireNotNull(repo.getDocument(imported)).pdfFile.readBytes())
        assertTrue(repo.listTrash().isEmpty())
        assertNull(TrashActions.takePending())
    }

    @Test
    fun `the import Trash action stays above the Create FAB area in English and Hindi`() {
        val imported = DocFixtures.saved(repo, app, pages = 1, name = "Fresh import")
        val problems = mutableListOf<String>()
        try {
            for (language in listOf("en", "hi")) {
                for (width in listOf(320, 411)) {
                    for (font in listOf(1f, 1.3f, 2f)) {
                        val case = "$language ${width}dp ${font}x"
                        RuntimeEnvironment.setQualifiers("$language-w${width}dp-h640dp-xhdpi")
                        RuntimeEnvironment.setFontScale(font)
                        val density = app.resources.displayMetrics.density
                        val w = (width * density).toInt()
                        val h = (640 * density).toInt()
                        lateinit var fab: Rect
                        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                            scenario.onActivity { shell ->
                                layoutWindow(shell.window.decorView, w, h)
                                fab = screenBounds(shell.findViewById(R.id.swNavScan))
                                assertEquals("$case Create FAB width", (74 * density).toInt(), fab.width())
                                assertTrue("$case Create FAB must be in its bottom zone: $fab",
                                    fab.top >= h - (140 * density).toInt() && fab.bottom <= h)
                            }
                        }
                        ActivityScenario.launch<DocumentViewerActivity>(
                            DocumentViewerActivity.intent(app, imported, freshImport = true)
                        ).use { scenario ->
                            scenario.onActivity { viewer ->
                                viewer.onBackPressedDispatcher.onBackPressed()
                                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                                layoutWindow(requireNotNull(dialog.window).decorView, w, h)
                                val action = dialog.findViewById<TextView>(R.id.swSheetSecondary)
                                val bounds = screenBounds(action)
                                val minimum = (48 * density).toInt()
                                if (bounds.bottom > fab.top) {
                                    problems += "$case: Trash must end above Create: action=$bounds FAB=$fab"
                                }
                                if (bounds.top < 0 || bounds.left < 0 || bounds.right > w ||
                                    bounds.width() < minimum || bounds.height() < minimum ||
                                    !action.isShown
                                ) {
                                    problems += "$case: Trash target is not visible at 48dp: $bounds"
                                }
                                val expected = if (language == "en") "Move to Trash" else
                                    "हटाई गई फ़ाइलों में भेजें"
                                val node = action.createAccessibilityNodeInfo()
                                if (node.text?.toString() != expected || !node.isClickable ||
                                    node.className != Button::class.java.name
                                ) {
                                    problems += "$case: expected accessible action '$expected', got '${node.text}'"
                                }
                                val message = dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
                                val recovery = if (language == "en") "restore this copy from Trash for 30 days" else
                                    "30 दिन तक हटाई गई फ़ाइलों से वापस"
                                if (!message.contains(recovery)) {
                                    problems += "$case: the message doesn't explain recovery from Trash: '$message'"
                                }
                                for (id in listOf(R.id.swSheetTitle, R.id.swSheetMessage,
                                    R.id.swSheetSecondary, R.id.swSheetPrimary)) {
                                    val text = dialog.findViewById<TextView>(id)
                                    val layout = text.layout
                                    val visibleHeight = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                                    val rect = screenBounds(text)
                                    if (layout == null || layout.height > visibleHeight ||
                                        rect.top < 0 || rect.bottom > h ||
                                        (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                                    ) {
                                        problems += "$case: clipped label '${text.text}'"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun layoutWindow(decor: View, width: Int, height: Int) {
        repeat(3) {
            idle()
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
        }
    }

    private fun screenBounds(view: View): Rect {
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        return Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
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
     * T44: the untouched-import question must name what the action does, not imply discarding edits.
     */
    @Test
    fun `the untouched import question names Trash, not a generic discard`() {
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
                    "the answer explicitly says it moves the import to Trash",
                    app.getString(R.string.trash_move),
                    dialog.findViewById<TextView>(R.id.swSheetSecondary)?.text?.toString()
                )
                assertEquals(
                    "and it never uses the generic Discard wording",
                    false,
                    app.getString(R.string.trash_move) == app.getString(R.string.discard)
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
