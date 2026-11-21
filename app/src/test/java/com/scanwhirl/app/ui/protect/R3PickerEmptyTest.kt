package com.scanwhirl.app.ui.protect

import android.app.Application
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.tools.HindiTextLauncher
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class R3PickerEmptyTest(private val language: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun languages(): List<Array<String>> = listOf(arrayOf("en"), arrayOf("hi"))
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanWhirlApp.from(app).repository

    @Before
    fun configure() {
        RuntimeEnvironment.setQualifiers("$language-w320dp-h640dp-xhdpi")
    }

    @After
    fun reset() {
        RuntimeEnvironment.setQualifiers("")
    }

    @Test
    fun unlockWithOnlyUnlockedDocuments() {
        DocFixtures.saved(repo, app, pages = 1, name = "Fictional unlocked receipt")
        chooser(LockToolsActivity.MODE_UNLOCK) { dialog ->
            assertEquals(
                if (language == "hi") "किसी भी दस्तावेज़ पर पासवर्ड नहीं है"
                else "None of your documents are password-protected",
                title(dialog)
            )
            // KT1: a non-empty library with no eligible documents is no longer a dead end - the
            // caller's real Scan/Import routes stay available, with an honest explanation.
            assertTrue(visible(dialog, R.id.docPickerEmptyScan))
            assertTrue(visible(dialog, R.id.docPickerEmptyImport))
            assertTrue(visible(dialog, R.id.docPickerExtra))
            assertFalse(visible(dialog, R.id.docPickerClear))
            assertTrue("the no-eligible state explains itself",
                dialog.findViewById<TextView>(R.id.docPickerEmptyBody)?.text.toString().isNotBlank())
        }
    }

    @Test
    fun protectWithOnlyLockedDocuments() {
        DocFixtures.locked(repo, app, pages = 1)
        chooser(LockToolsActivity.MODE_PROTECT) { dialog ->
            assertEquals(
                if (language == "hi") "आपके सभी दस्तावेज़ पासवर्ड से सुरक्षित हैं"
                else "All your documents are already password-protected",
                title(dialog)
            )
            // KT1: the honest route forward stays available when nothing is eligible.
            assertTrue(visible(dialog, R.id.docPickerEmptyScan))
            assertFalse(visible(dialog, R.id.docPickerClear))
            assertTrue("the no-eligible state explains itself",
                dialog.findViewById<TextView>(R.id.docPickerEmptyBody)?.text.toString().isNotBlank())
        }
    }

    @Test
    fun trulyEmptyLibraryKeepsTheOriginalCopyAndOnePdfAction() {
        chooser(LockToolsActivity.MODE_UNLOCK) { dialog ->
            assertEquals(app.getString(R.string.w6_picker_empty_title), title(dialog))
            assertEquals(
                app.getString(R.string.w6_picker_empty_body),
                dialog.findViewById<TextView>(R.id.docPickerEmptyBody)?.text.toString()
            )
            assertTrue(visible(dialog, R.id.docPickerEmptyScan))
            assertEquals(
                "a single PDF action",
                1,
                listOf(R.id.docPickerExtra, R.id.docPickerEmptyImport).count { visible(dialog, it) }
            )
        }
    }

    @Test
    fun searchMissWithEligibleDocumentsKeepsClearSearch() {
        DocFixtures.locked(repo, app, pages = 1)
        chooser(LockToolsActivity.MODE_UNLOCK) { dialog ->
            assertFalse(visible(dialog, R.id.docPickerEmpty))
            dialog.findViewById<EditText>(R.id.docPickerSearch)!!.setText("no fictional match")
            assertEquals(app.getString(R.string.w6_picker_no_match_title), title(dialog))
            assertTrue(visible(dialog, R.id.docPickerClear))
            dialog.findViewById<View>(R.id.docPickerClear)!!.performClick()
            assertFalse(visible(dialog, R.id.docPickerEmpty))
        }
    }

    @Test
    fun hindiTextWithOnlyLockedDocuments() {
        DocFixtures.locked(repo, app, pages = 1)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                HindiTextLauncher.open(activity, repo)
                withLatestPicker { dialog ->
                    assertEquals(activity.getString(R.string.r3fit_no_hindi_text_source), title(dialog))
                    // KT1: FlowUtil's real Scan route remains available when nothing is eligible.
                    assertTrue(visible(dialog, R.id.docPickerEmptyScan))
                }
            }
        }
    }

    @Test
    fun insertPagesDoesNotMistakeItsOnlyDestinationForAnEmptyLibrary() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional destination")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.startInsertPages()
                val sources = requireNotNull(ShadowDialog.getLatestDialog())
                val options = requireNotNull(sources.findViewById<ViewGroup>(R.id.swSheetItems))
                options.getChildAt(2).performClick()
                withLatestPicker { dialog ->
                    assertEquals(activity.getString(R.string.r3fit_no_insert_source), title(dialog))
                    // KT1: FlowUtil's real Scan route remains available when nothing is eligible.
                    assertTrue(visible(dialog, R.id.docPickerEmptyScan))
                }
            }
        }
    }

    @Test
    fun pageToolsTellTheTruthWhenAllDocumentsAreLocked() {
        DocFixtures.locked(repo, app, pages = 1)
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                DocumentViewerActivity.openInsertPicker(activity)
                assertEquals(
                    activity.getString(R.string.r3fit_no_page_tool_target),
                    ShadowToast.getTextOfLatestToast()
                )
                DocumentViewerActivity.openOrganizePicker(activity)
                assertEquals(
                    activity.getString(R.string.r3fit_no_page_tool_target),
                    ShadowToast.getTextOfLatestToast()
                )
            }
        }
    }

    private fun chooser(mode: String, assertions: (Dialog) -> Unit) {
        ActivityScenario.launch<LockToolsActivity>(LockToolsActivity.intent(app, mode)).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(language, activity.resources.configuration.locales[0].language)
                withLatestPicker(assertions)
            }
        }
    }

    private fun withLatestPicker(assertions: (Dialog) -> Unit) {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        try {
            assertions(dialog)
        } finally {
            dialog.dismiss()
        }
    }

    private fun title(dialog: Dialog) =
        dialog.findViewById<TextView>(R.id.docPickerEmptyTitle)?.text.toString()

    private fun visible(dialog: Dialog, id: Int) =
        dialog.findViewById<View>(id)?.visibility == View.VISIBLE
}
