package com.scanwhirl.app.ui.tools

import android.app.Application
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Copy contracts plus rendered result geometry. No storage/action semantics are replaced. */
abstract class U4CopyIconsBase {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val hi get() = app.resources.configuration.locales[0].language == "hi"
    private fun source(path: String) = File("src/main/java/com/scanwhirl/app/ui/$path").readText()
    private fun string(key: String): String {
        val id = app.resources.getIdentifier(key, "string", app.packageName)
        assertNotEquals("EN/HI resource $key must exist", 0, id)
        return app.getString(id)
    }

    @Test fun settingsNoteDescribesFutureReadingOnly() {
        assertEquals(if (hi) "अगली बार पढ़ने पर यह भाषा इस्तेमाल होगी।" else "Used the next time text is read.",
            string("u4_ocr_language_note"))
        assertTrue(source("SettingsActivity.kt").contains(".setMessage(R.string.u4_ocr_language_note)"))
        assertTrue("document re-read warning stays", source("OcrEditorActivity.kt").contains("R.string.ocr_lang_note"))
        assertTrue("document warning still explains edits", app.getString(R.string.ocr_lang_note).length > 60)
    }

    @Test fun resultHeaderIsReadyNotDone() {
        assertEquals(if (hi) "हो गया" else "Done", app.getString(R.string.tools_result_done))
    }

    @Test fun restoreNotesRequestExistingSecrets() {
        assertEquals(if (hi) "इस प्रति के लिए इस्तेमाल किया गया पासवर्ड डालें।"
            else "Enter the password used for this backup.", string("u4_backup_restore_password_note"))
        assertEquals(if (hi) "इस प्रति के लिए इस्तेमाल किया गया तिजोरी वापस खोलने का कोड डालें।"
            else "Enter the Vault recovery code used for this backup.", string("u4_backup_restore_code_note"))
        val restore = source("backup/BackupActivity.kt").substringAfter("private fun askRestoreSecret")
            .substringBefore("private fun runRestore")
        assertTrue(restore.contains("R.string.u4_backup_restore_password_note"))
        assertTrue(restore.contains("R.string.u4_backup_restore_code_note"))
        assertFalse(restore.contains("R.string.backup_password_note"))
        assertFalse(restore.contains("R.string.backup_code_note"))
    }

    @Test fun meBackupIsShortButBackupWarningsRemain() {
        assertEquals(if (hi) "कूटबद्ध प्रतियाँ।" else "Encrypted backups.",
            app.getString(R.string.me_backup_summary))
        val backup = source("backup/BackupActivity.kt")
        assertTrue(backup.contains("R.string.backup_explain"))
        assertTrue(backup.contains("R.string.backup_vault"))
    }

    @Test fun actionsLoseOnlyRedundantCopy() {
        val actions = source("DocumentActionsSheet.kt")
        assertFalse(actions.contains("groupHeader(activity, R.string.doc_actions_header"))
        assertEquals(if (hi) "इसे 30 दिनों तक वापस ला सकते हैं।" else "You can restore it for 30 days.",
            app.getString(R.string.shell_trash_note))
        assertTrue("bound document stays", actions.contains("rows.addView(header(activity, doc))"))
        assertTrue("trash scope stays", actions.contains("R.string.shell_trash_note"))
    }

    /**
     * LEAD DECISION (L1-01): OLD read the heading from the old standalone sort row and
     * asserted `swSort` text "Newest ▾". NEW reads the same heading inside the single
     * Folder/Filter/Sort control band, keeps the sort-dependent heading, and still requires
     * all five sort choices. Intent unchanged: the list heading follows the chosen sort.
     */
    @Test fun libraryHeadingFollowsSort() {
        assertEquals(if (hi) "दस्तावेज़" else "Documents", string("u4_library_documents"))
        val library = source("shell/LibraryScreen.kt")
        val render = library.substringAfter("private fun renderSortLabel()").substringBefore("/**")
        assertTrue(render.contains("R.string.u4_library_documents"))
        assertTrue(render.contains("R.string.home_recent"))
        assertTrue(render.contains("LIB_NAME_ASC") && render.contains("LIB_NAME_DESC") && render.contains("LIB_SIZE"))
        val prefs = com.scanwhirl.app.util.Prefs
        for (sort in listOf(prefs.LIB_NEWEST, prefs.LIB_OLDEST, prefs.LIB_NAME_ASC, prefs.LIB_NAME_DESC, prefs.LIB_SIZE)) {
            prefs.setLibrarySort(app, sort)
            ActivityScenario.launch(com.scanwhirl.app.ui.shell.ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull(
                        "sort lives in the one control band",
                        activity.findViewById<View>(R.id.uxL1ControlBand)
                    )
                    val sortView = activity.findViewById<TextView>(R.id.swSort)
                    // Council §7: OLD read the heading as the sort row's first sibling.
                    // NEW reads the sort-dependent plain-text heading row.
                    val heading = activity.findViewById<TextView>(R.id.cbDocHeading)
                    val expected = if (sort == prefs.LIB_NEWEST || sort == prefs.LIB_OLDEST)
                        activity.getString(R.string.home_recent) else string("u4_library_documents")
                    assertEquals("heading for $sort", expected, heading.text.toString())
                }
            }
        }
        prefs.setLibrarySort(app, prefs.LIB_NEWEST)
    }

    @Test fun resultHasOneNameHonestReceiptAndSizeHeadline() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario -> scenario.onActivity { activity ->
            val name = activity.findViewById<TextView>(R.id.toolResultName)
            val summary = activity.findViewById<TextView>(R.id.toolResultSummary)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            assertEquals("location must wrap rather than truncate", Int.MAX_VALUE, name.maxLines)
            assertFalse("hero must not repeat output name", summary.text.contains("Fictional receipt"))
            assertTrue("one saved receipt line: ${name.text}", name.text.toString().contains("Fictional receipt"))
            assertNotNull("headline precedes the real preview",
                activity.window.decorView.findViewWithTag<View>("result-headline"))
            assertTrue("measured result precedes receipt", summary.top < name.top)
            val also = activity.findViewById<View>(R.id.toolResultAlsoSave)
            assertNotNull("the save offer exists in the More panel", also)
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            assertEquals(View.VISIBLE, also.visibility)
        } }
        val file = File(app.cacheDir, "fictional-output.docx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "to_word", files = listOf(file))
        ).use { scenario -> scenario.onActivity { activity ->
            assertTrue("single file is named in its type card",
                activity.findViewById<View>(R.id.toolResultFileName).visibility == View.VISIBLE)
            assertEquals(file.name, activity.findViewById<TextView>(R.id.toolResultFileName).text.toString())
            assertTrue("export file is named in the one receipt line",
                activity.findViewById<TextView>(R.id.toolResultName).text.toString().contains(file.name))
        } }
    }

    @Test fun resultButtonsReflowAt200PercentWithoutSpokenIcons() {
        val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional receipt")
        ActivityScenario.launch<ToolResultActivity>(
            ToolResultActivity.intent(app, "compress", docIds = listOf(id))
        ).use { scenario -> scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val density = activity.resources.displayMetrics.density
            activity.findViewById<View>(R.id.toolResultMore).performClick()
            root.measure(View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val buttons = listOf(R.id.toolResultOpen, R.id.toolResultShare, R.id.toolResultAlsoSave, R.id.toolResultUndo)
                .map { activity.findViewById<TextView>(it) }
            for (button in buttons) {
                assertNotNull("leading decorative glyph: ${button.text}", button.compoundDrawablesRelative[0])
                assertNull("no redundant spoken glyph", button.contentDescription)
                assertEquals("button accessibility role", "android.widget.Button", button.createAccessibilityNodeInfo().className)
                assertTrue("48dp minimum", button.height >= 48 * density)
                val layout = requireNotNull(button.layout)
                assertEquals("full text is laid out", button.text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) assertEquals("no ellipsis", 0, layout.getEllipsisCount(line))
                assertTrue("no vertical clipping", layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
            }
            assertNotEquals("Open alone is primary", buttons[0].currentTextColor, buttons[1].currentTextColor)
        } }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class U4CopyIconsEnTest : U4CopyIconsBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class U4CopyIconsHiTest : U4CopyIconsBase()
