package com.scanwhirl.app.ui.protect

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

/**
 * UI-W6: every "choose a document" surface must show the same recognisable picker: a search
 * box, a real first-page thumbnail (never for a protected/Vault file: a lock and a reason
 * instead, with no page/size/date/category leak), the name (wrapping), "N pages · size · date"
 * and a category chip, and it must route by document id so two identical names still open the
 * exact chosen file. Cancel or rotation selects nothing.
 *
 * On the base integration 8d2fabfd Protect/Unlock showed a plain name-only list, so this suite
 * is red there and green once the shared picker is wired.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class W6PickerPilotTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
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

    private fun latest(): Dialog? = ShadowDialog.getLatestDialog()

    private fun picker(): Dialog = requireNotNull(latest()) { "no picker dialog" }

    private fun pickerRows(dialog: Dialog): List<LinearLayout> {
        val list = dialog.findViewById<LinearLayout>(R.id.docPickerList) ?: return emptyList()
        return (0 until list.childCount).mapNotNull { list.getChildAt(it) as? LinearLayout }
    }

    private fun rowFor(dialog: Dialog, docId: String): LinearLayout =
        pickerRows(dialog).firstOrNull { it.tag == docId }
            ?: throw AssertionError("no picker row for $docId (tags=${pickerRows(dialog).map { it.tag }})")

    private fun launch(mode: String): ActivityScenario<LockToolsActivity> =
        ActivityScenario.launch(LockToolsActivity.intent(app, mode))

    // ---- 1. duplicate names route by document id -----------------------------

    @Test
    fun `two identical names still select the exact file by id`() {
        // Same visible name, different page counts: the sheet must show the pages of the row
        // that was clicked, not of the first file with that name.
        val threePages = DocFixtures.saved(repo, app, pages = 3, name = "SW_TEST_3pages")
        val onePage = DocFixtures.saved(repo, app, pages = 1, name = "SW_TEST_3pages")
        launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the picker lists both documents",
                    waitUntil { pickerRows(picker()).size == 2 }
                )
                val dialog = picker()
                assertEquals(
                    "both rows show the same name",
                    listOf("SW_TEST_3pages", "SW_TEST_3pages"),
                    pickerRows(dialog).map { it.findViewById<TextView>(R.id.docPickerName).text.toString() }
                )
                rowFor(dialog, onePage).performClick()
                idle()
                val sheet = requireNotNull(latest()) { "the Protect sheet did not open" }
                val pages = requireNotNull(sheet.findViewById<TextView>(R.id.protectDocPages)) {
                    "the Protect sheet lost its document identity chip"
                }
                assertEquals(
                    "the tapped file (1 page) is the one reviewed, not its same-named twin (3 pages)",
                    "1",
                    pages.text.toString()
                )
                assertEquals(
                    "the reviewed name is the tapped document",
                    "SW_TEST_3pages",
                    sheet.findViewById<TextView>(R.id.protectDocName).text.toString()
                )
            }
        }
        assertNotNull("both same-named sources still exist", repo.getDocument(threePages))
        assertNotNull("both same-named sources still exist", repo.getDocument(onePage))
    }

    // ---- 2. locked source: lock glyph, no thumbnail, no metadata leak --------

    @Test
    fun `a protected source shows a lock and no thumbnail, page, size or category leak`() {
        val lockedId = DocFixtures.locked(repo, app, password = "1234", pages = 2)
        launch(LockToolsActivity.MODE_UNLOCK).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the locked source is listed", waitUntil { pickerRows(picker()).any { it.tag == lockedId } })
                val row = rowFor(picker(), lockedId)

                val thumb = requireNotNull(row.findViewById<ImageView>(R.id.docPickerThumb)) {
                    "the picker row has no thumbnail slot"
                }
                assertFalse(
                    "a protected file must not decode its first page",
                    thumb.drawable is BitmapDrawable
                )
                val lock = requireNotNull(row.findViewById<ImageView>(R.id.docPickerLock)) {
                    "a protected row needs a visible lock"
                }
                assertEquals(View.VISIBLE, lock.visibility)
                assertEquals(
                    "the lock carries a spoken reason",
                    activity.getString(R.string.w6_picker_locked_reason),
                    lock.contentDescription
                )

                val meta = row.findViewById<View>(R.id.docPickerMeta)
                assertTrue(
                    "no page count, size or date may leak from a protected file",
                    meta == null || meta.visibility != View.VISIBLE
                )
                val category = row.findViewById<View>(R.id.docPickerCategory)
                assertTrue(
                    "no category chip may leak from a protected file",
                    category == null || category.visibility != View.VISIBLE
                )

                // The row still routes by id and opens the Unlock sheet.
                row.performClick()
                idle()
                val sheet = requireNotNull(latest())
                assertNotNull(
                    "the Unlock sheet opens for the protected row",
                    sheet.findViewById<View>(R.id.unlockPassword)
                )
            }
        }
    }

    // ---- 3. search filters ------------------------------------------------

    @Test
    fun `search narrows the list to the matching name and clearing restores it`() {
        DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
        DocFixtures.saved(repo, app, pages = 1, name = "Offer letter")
        launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("both documents are listed", waitUntil { pickerRows(picker()).size == 2 })
                val search = requireNotNull(picker().findViewById<EditText>(R.id.docPickerSearch)) {
                    "the picker has no search box"
                }
                search.setText("offer")
                idle()
                assertEquals(
                    "only the matching document remains",
                    listOf("Offer letter"),
                    pickerRows(picker()).map { it.findViewById<TextView>(R.id.docPickerName).text.toString() }
                )
                search.setText("")
                idle()
                assertEquals("clearing the search restores both", 2, pickerRows(picker()).size)
            }
        }
    }

    // ---- 4. cancel leaves nothing selected --------------------------------

    @Test
    fun `cancelling the picker selects nothing and opens no sheet`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the picker is up", waitUntil { pickerRows(picker()).isNotEmpty() })
                assertTrue(
                    "a fresh picker has no selected row",
                    pickerRows(picker()).none { it.isSelected }
                )
                picker().onBackPressed()
                idle()
                assertNull(
                    "Back must not open the Protect sheet",
                    latest()?.findViewById<View>(R.id.protectPassword)
                )
            }
        }
    }

    @Test
    fun `rotation while picking leaves nothing selected`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
        launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the picker is up", waitUntil { pickerRows(picker()).any { it.tag == id } })
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                idle()
                val dialog = picker()
                assertTrue(
                    "the reopened picker has rows",
                    waitUntil { pickerRows(dialog).isNotEmpty() }
                )
                assertFalse(
                    "a rotation must not keep a stale selection",
                    rowFor(dialog, id).isSelected
                )
                assertNull(
                    "a rotation must not open the Protect sheet by itself",
                    latest()?.findViewById<View>(R.id.protectPassword)
                )
            }
        }
    }

    // ---- 5. empty state with Scan and Import ------------------------------

    @Test
    fun `an empty library offers Scan and Import instead of a dead end`() {
        // Unlock is the surface whose Import route is real (it opens the system PDF picker), so
        // both empty actions are genuinely wired here.
        launch(LockToolsActivity.MODE_UNLOCK).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = picker()
                val empty = requireNotNull(dialog.findViewById<View>(R.id.docPickerEmpty)) {
                    "an empty library must show an empty state"
                }
                assertEquals(View.VISIBLE, empty.visibility)
                val scan = requireNotNull(dialog.findViewById<TextView>(R.id.docPickerEmptyScan)) {
                    "the empty state has no Scan action"
                }
                val import = requireNotNull(dialog.findViewById<TextView>(R.id.docPickerEmptyImport)) {
                    "the empty state has no Import action"
                }
                assertEquals(activity.getString(R.string.w6_picker_empty_scan), scan.text.toString())
                assertEquals(activity.getString(R.string.w6_picker_empty_import), import.text.toString())
                scan.performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(
                    "Scan leads to the screen that can scan",
                    ShellActivity::class.java.name,
                    started?.component?.className
                )
            }
        }
    }

    // ---- 6. accessibility at 200% ---------------------------------------

    private fun assertPickerAccessible(activity: Activity, dialog: Dialog, label: String) {
        val root = requireNotNull(dialog.findViewById<View>(R.id.docPickerSheetRoot)) { "$label: no picker" }
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
            )
            root.layout(0, 0, w, root.measuredHeight)
        }

        val minTouch = (48 * dm.density).roundToInt()
        val problems = mutableListOf<String>()

        fun labelled(v: View): Boolean {
            if (!v.contentDescription.isNullOrBlank()) return true
            if (v is EditText && !v.hint.isNullOrBlank()) return true
            if (v is TextView && !v.text.isNullOrBlank()) return true
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i)
                    if (child.visibility == View.VISIBLE && labelled(child)) return true
                }
            }
            return false
        }

        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO) {
                if (v.isClickable) {
                    if (v.width < minTouch || v.height < minTouch) {
                        problems += "$label: ${v.javaClass.simpleName} id=${v.id} is " +
                            "${v.width}x${v.height}px (needs ${minTouch}x${minTouch})"
                    }
                    if (!labelled(v)) problems += "$label: clickable id=${v.id} has no label"
                }
                if (v is TextView) {
                    val layout = v.layout
                    if (layout != null) {
                        val avail = v.width - v.totalPaddingLeft - v.totalPaddingRight
                        for (line in 0 until layout.lineCount) {
                            if (layout.getLineWidth(line) > avail + 0.5f) {
                                problems += "$label: \"${v.text}\" line $line is wider than $avail px"
                            }
                            if (layout.getEllipsisCount(line) > 0) {
                                problems += "$label: \"${v.text}\" line $line is ellipsized"
                            }
                        }
                        val needed = layout.height + v.totalPaddingTop + v.totalPaddingBottom
                        if (needed > v.height + 0.5f) {
                            problems += "$label: \"${v.text}\" needs ${needed}px but is ${v.height}px tall"
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        assertTrue("$label accessibility problems:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `the picker is accessible at 130 and 200 percent in English`() {
        DocFixtures.saved(repo, app, pages = 3, name = "Rent receipt")
        for (scale in floatArrayOf(1.3f, 2.0f)) {
            RuntimeEnvironment.setFontScale(scale)
            launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue("the picker is up", waitUntil { pickerRows(picker()).isNotEmpty() })
                    assertEquals(
                        "the font scale reached the picker",
                        scale.toDouble(),
                        activity.resources.configuration.fontScale.toDouble(),
                        0.001
                    )
                    assertPickerAccessible(activity, picker(), "EN @ ${scale}x")
                }
            }
        }
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @Test
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `the Hindi picker is accessible at 200 percent`() {
        DocFixtures.saved(repo, app, pages = 3, name = "किराया रसीद")
        RuntimeEnvironment.setFontScale(2.0f)
        launch(LockToolsActivity.MODE_PROTECT).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the picker is up", waitUntil { pickerRows(picker()).isNotEmpty() })
                assertPickerAccessible(activity, picker(), "HI @ 2.0x")
            }
        }
    }
}
