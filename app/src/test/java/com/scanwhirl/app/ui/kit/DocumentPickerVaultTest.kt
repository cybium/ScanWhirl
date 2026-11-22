package com.scanwhirl.app.ui.kit
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UI-W6 privacy half of the shared picker: a Vault document never shows its page, page count,
 * size, date, category or OCR text — only its name, a lock and the spoken reason. The empty and
 * no-match states are explicit, with the host's real routes and a working Clear.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class DocumentPickerVaultTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a Vault document shows a lock and none of its metadata or OCR snippet`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Vault electricity bill")
        DocFixtures.withOcr(repo, id)
        repo.setVaulted(id, true)
        val vaulted = requireNotNull(repo.getDocument(id))
        assertTrue("fixture is in the Vault", vaulted.isVaulted)

        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val picker = DocumentPicker.show(
                    activity = activity,
                    title = "Choose",
                    docs = listOf(vaulted),
                    onPick = {}
                )
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                val rows = picker.rows
                assertEquals(1, rows.size)
                val row = rows.first()

                val thumb = requireNotNull(row.findViewById<ImageView>(R.id.docPickerThumb))
                assertFalse("Vault must never decode its page", thumb.drawable is BitmapDrawable)
                val lock = requireNotNull(row.findViewById<ImageView>(R.id.docPickerLock))
                assertEquals(View.VISIBLE, lock.visibility)
                assertEquals(
                    "the lock says why, out loud",
                    activity.getString(R.string.w6_picker_vault_reason),
                    lock.contentDescription
                )
                assertNull("no page/size/date leak", row.findViewById<View>(R.id.docPickerMeta))
                assertNull("no category leak", row.findViewById<View>(R.id.docPickerCategory))

                val texts = mutableListOf<String>()
                fun walk(v: View) {
                    if (v is TextView) texts += v.text?.toString().orEmpty()
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(row)
                assertTrue(
                    "the Vault row must not print any OCR snippet: $texts",
                    texts.none { it.contains("text p") }
                )
                assertEquals(
                    "the row names the document so it can be chosen",
                    "Vault electricity bill",
                    row.findViewById<TextView>(R.id.docPickerName).text.toString()
                )
                // Nothing was picked by merely opening the sheet.
                assertNull(picker.selectedDocumentId)
                dialog.dismiss()
                idle()
            }
        }
    }

    @Test
    fun `the empty state calls the host's real Scan and Import routes`() {
        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var scanned = 0
                var imported = 0
                val picker = DocumentPicker.show(
                    activity = activity,
                    title = "Choose",
                    docs = emptyList(),
                    onEmptyScan = { scanned++ },
                    onEmptyImport = { imported++ },
                    onPick = {}
                )
                idle()
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(View.VISIBLE, requireNotNull(dialog.findViewById<View>(R.id.docPickerEmpty)).visibility)
                dialog.findViewById<View>(R.id.docPickerEmptyScan).performClick()
                idle()
                assertEquals("Scan ran the host's route", 1, scanned)
                assertEquals("Import was untouched", 0, imported)
            }
        }
    }

    @Test
    fun `a search with no match offers Clear and hides Scan and Import`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        val doc = requireNotNull(repo.listDocuments().firstOrNull())
        ActivityScenario.launch<ShellActivity>(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val picker = DocumentPicker.show(
                    activity = activity,
                    title = "Choose",
                    docs = listOf(doc),
                    onEmptyScan = {},
                    onEmptyImport = {},
                    onPick = {}
                )
                idle()
                picker.searchView.setText("nothing matches this")
                idle()
                assertEquals("no row matches", 0, picker.rows.size)
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                assertEquals(
                    "the no-match state is shown",
                    activity.getString(R.string.w6_picker_no_match_title),
                    dialog.findViewById<TextView>(R.id.docPickerEmptyTitle).text.toString()
                )
                assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.docPickerClear).visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.docPickerEmptyScan).visibility)
                assertEquals(View.GONE, dialog.findViewById<View>(R.id.docPickerEmptyImport).visibility)
                dialog.findViewById<View>(R.id.docPickerClear).performClick()
                idle()
                assertEquals("clearing restores the row", 1, picker.rows.size)
            }
        }
    }
}
