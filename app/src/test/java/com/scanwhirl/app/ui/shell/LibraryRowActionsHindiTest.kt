package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-B5 Hindi parity: the Library row's ⋮ opens the same Actions sheet, and every word
 * in it is the shared sheet's Hindi (values-hi), not an English fallback or a second
 * vocabulary. The sheet still acts on the row's own document in Hindi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class LibraryRowActionsHindiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        TrashActions.takePending()
    }

    @After
    fun tearDown() {
        TrashActions.takePending()
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    private fun latestDialog(what: String): android.app.Dialog {
        var found: android.app.Dialog? = null
        poll {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog.isShowing &&
                dialog.findViewById<View>(R.id.swSheetTitle) != null
            ) {
                found = dialog
                true
            } else {
                false
            }
        }
        return requireNotNull(found) { "$what never appeared" }
    }

    private fun openRowActions(activity: ShellActivity, id: String): android.app.Dialog {
        assertTrue("the row is listed", poll { cards(activity).any { it.doc?.id == id } })
        cards(activity).first { it.doc?.id == id }.moreButton.performClick()
        idle()
        return latestDialog("the row's Actions sheet")
    }

    private fun isHindi(text: String): Boolean =
        text.any { it.code in 0x0900..0x097F }

    @Test
    fun `the row sheet from the library speaks Hindi and names its document`() {
        val other = DocFixtures.saved(repo, app, pages = 2, name = "पुराना नाम")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "किराया 2026")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                assertEquals(
                    "the sheet speaks Hindi (copy diet: दस्तावेज़ के काम)",
                    "दस्तावेज़ के काम",
                    sheet.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                )
                val header = requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<TextView>("docActions:name")
                ) { "the header must name the document" }
                assertEquals("the header names this row", "किराया 2026", header.text.toString())
                assertTrue("the header is Hindi", isHindi(header.text.toString()))

                val trash = requireNotNull(sheet.findViewById<TextView>(R.id.labelMoreDelete))
                assertEquals(app.getString(R.string.trash_move), trash.text.toString())
                assertEquals("हटाई गई फ़ाइलों में भेजें", trash.text.toString())
                assertTrue("the move row is Hindi", isHindi(trash.text.toString()))

                val rename = requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:rename")
                )
                val renameWords = rename.contentDescription?.toString().orEmpty()
                assertEquals(app.getString(R.string.rename), renameWords)
                assertTrue("the rename row is Hindi: $renameWords", isHindi(renameWords))

                assertNotNull(repo.getDocument(other))
                assertNotNull(repo.getDocument(shown))
            }
        }
    }

    @Test
    fun `Move to Trash from the Hindi row sheet still moves and Undo restores`() {
        val kept = DocFixtures.saved(repo, app, pages = 2, name = "रखें")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "हटाना है")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                requireNotNull(sheet.findViewById<View>(R.id.rowMoreDelete)).performClick()
                idle()
                val confirm = latestDialog("the Trash confirmation")
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()

                assertTrue("the document leaves the library", poll { repo.getDocument(shown) == null })
                assertNotNull("the other document stays", repo.getDocument(kept))
                val trashed = requireNotNull(repo.listTrash().singleOrNull()) {
                    "Hindi path must move to Trash, never purge"
                }
                assertEquals(shown, trashed.originalId)
                assertEquals(3, trashed.document.pageCount)

                var action: TextView? = null
                poll {
                    action = activity.window!!.decorView
                        .findViewById<TextView>(com.google.android.material.R.id.snackbar_action)
                    action != null
                }
                assertNotNull("the Hindi Moved to Trash notice offers Undo", action)
                action!!.performClick()
                assertTrue("Undo restores the document", poll { repo.getDocument(shown) != null })
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
            }
        }
    }
}
