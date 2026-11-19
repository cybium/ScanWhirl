package com.scanwhirl.app.ui.shell

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.tools.IdCardActivity
import com.scanwhirl.app.ui.tools.PassportActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * UI-B1-LIBRARY-CREATE (UI_CRITIQUE_GPT_2026-11-19 #2): the centre Scan action opens one
 * Create sheet instead of jumping straight into the scanner, so ID card, Passport, QR code,
 * photos and PDF imports stop looking like separate Home flows.
 *
 * The sheet reuses the launchers the old Home row used (checked per entry), Back/Close launches
 * nothing and gives focus back to Scan, QR stays Pro-gated in Free, and at 200 % font the entries
 * become full-width rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LibraryCreateSheetTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        RuntimeEnvironment.setFontScale(1.0f)
        DocFixtures.saved(repo, app, pages = 2, name = "Create sheet doc")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.setLocaleTag(app, "")
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    /** Opens the Create sheet the way a person does and returns its dialog. */
    private fun openSheet(activity: ShellActivity): Dialog {
        activity.findViewById<View>(R.id.swNavScan).performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("the centre Scan action opens a sheet instead of the scanner", dialog)
        assertTrue("the Create sheet is showing", dialog!!.isShowing)
        return dialog
    }

    private fun entry(dialog: Dialog, id: Int): View {
        val view = dialog.findViewById<View>(id)
        assertNotNull("the Create sheet has the entry ${idName(dialog, id)}", view)
        return view!!
    }

    private fun idName(dialog: Dialog, id: Int): String =
        runCatching { dialog.context.resources.getResourceEntryName(id) }.getOrDefault(id.toString())

    /** The first TextView under an entry (a tile's label or a row's label). */
    private fun labelOf(entry: View): String {
        if (entry is TextView) return entry.text.toString()
        if (entry is ViewGroup) {
            for (i in 0 until entry.childCount) {
                val label = labelOfOrNull(entry.getChildAt(i))
                if (label != null) return label
            }
        }
        return ""
    }

    private fun labelOfOrNull(view: View): String? {
        if (view is TextView && view.id != R.id.swCreateQrPro) return view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                labelOfOrNull(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `the sheet lists the six starting routes`() {
        launch { activity ->
            val dialog = openSheet(activity)
            assertEquals(
                "the sheet title is Create",
                activity.getString(R.string.tools_group_create),
                dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            val primary = dialog.findViewById<TextView>(R.id.swCreateScan)
            assertTrue("the filled primary is shown", primary.isVisible)
            assertEquals(
                "the primary starts a document scan",
                activity.getString(R.string.create_scan),
                primary.text.toString()
            )
            for (id in intArrayOf(
                R.id.swCreateIdCard,
                R.id.swCreatePassport,
                R.id.swCreateQr,
                R.id.swCreatePhotos,
                R.id.swCreateImport
            )) {
                entry(dialog, id)
            }
            assertEquals(
                "ID card",
                activity.getString(R.string.tool_id_card),
                labelOf(entry(dialog, R.id.swCreateIdCard))
            )
            assertEquals(
                "Passport",
                activity.getString(R.string.tool_passport),
                labelOf(entry(dialog, R.id.swCreatePassport))
            )
            assertEquals(
                "QR code",
                activity.getString(R.string.tool_qr_code),
                labelOf(entry(dialog, R.id.swCreateQr))
            )
            assertEquals(
                "Import photos",
                activity.getString(R.string.create_import_photos),
                labelOf(entry(dialog, R.id.swCreatePhotos))
            )
            assertEquals(
                "Import PDF",
                activity.getString(R.string.home_create_import),
                labelOf(entry(dialog, R.id.swCreateImport))
            )
            dialog.dismiss()
        }
    }

    @Test
    fun `scan entry runs the existing camera launcher`() {
        launch { activity ->
            val dialog = openSheet(activity)
            val toastBefore = ShadowToast.shownToastCount()
            dialog.findViewById<View>(R.id.swCreateScan).performClick()
            idle()
            assertFalse("the sheet closes on a choice", dialog.isShowing)
            assertNull(
                "the sheet itself starts no activity; the scanner does",
                shadowOf(activity).nextStartedActivity
            )
            // In Robolectric ML Kit is absent, so ScanLauncher.launchScanner() reports the same
            // scanner failure a phone without Play services shows: proof the existing launcher ran.
            assertTrue(
                "the existing scanner launcher ran",
                ShadowToast.shownToastCount() > toastBefore ||
                    !ShadowToast.getTextOfLatestToast().isNullOrBlank()
            )
        }
    }

    @Test
    fun `ID card entry opens the existing ID card tool`() {
        launch { activity ->
            val dialog = openSheet(activity)
            entry(dialog, R.id.swCreateIdCard).performClick()
            idle()
            assertFalse("the sheet closes on a choice", dialog.isShowing)
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("ID card opens its editor", started)
            assertEquals(IdCardActivity::class.java.name, started.component?.className)
        }
    }

    @Test
    fun `Passport entry opens the existing Passport tool`() {
        launch { activity ->
            val dialog = openSheet(activity)
            entry(dialog, R.id.swCreatePassport).performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("Passport opens its tool", started)
            assertEquals(PassportActivity::class.java.name, started.component?.className)
        }
    }

    @Test
    fun `Import photos entry opens the existing Add photos sheet`() {
        launch { activity ->
            val dialog = openSheet(activity)
            entry(dialog, R.id.swCreatePhotos).performClick()
            idle()
            assertFalse("the Create sheet closes first", dialog.isShowing)
            val next = ShadowDialog.getLatestDialog()
            assertNotNull("the Add photos choice opens", next)
            assertTrue(next!!.isShowing)
            assertEquals(
                activity.getString(R.string.photos_add_title),
                next.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            next.dismiss()
        }
    }

    @Test
    fun `Import PDF entry opens the existing import chooser`() {
        launch { activity ->
            val dialog = openSheet(activity)
            entry(dialog, R.id.swCreateImport).performClick()
            idle()
            val next = ShadowDialog.getLatestDialog()
            assertNotNull("the Import chooser opens", next)
            assertTrue(next!!.isShowing)
            assertNotNull("the PDF row is there", next.findViewById<View>(R.id.importPdf))
            assertNotNull("the photos row is there", next.findViewById<View>(R.id.importPhotos))
            next.dismiss()
        }
    }

    @Test
    fun `QR stays Pro-gated for a Free user`() {
        ProGate.setTestOverride(app, false)
        launch { activity ->
            val dialog = openSheet(activity)
            // The gold marker carries the word, never colour alone (UI_PLAN 0.11).
            val badge = dialog.findViewById<TextView>(R.id.swCreateQrPro)
            assertTrue("QR is marked Pro in Free", badge.isVisible)
            assertEquals("Pro", badge.text.toString())
            entry(dialog, R.id.swCreateQr).performClick()
            idle()
            assertNull(
                "a Free QR tap must not open the QR tool",
                shadowOf(activity).nextStartedActivity
            )
            assertTrue(
                "a Free QR tap opens the Pro screen",
                activity.findViewById<View>(R.id.swProScreen)?.isVisible == true
            )
            activity.findViewById<View>(R.id.swProClose).performClick()
        }
    }

    @Test
    fun `QR marker is absent once Pro is unlocked`() {
        ProGate.setTestOverride(app, true)
        launch { activity ->
            val dialog = openSheet(activity)
            val badge = dialog.findViewById<TextView>(R.id.swCreateQrPro)
            assertTrue("no Pro marker in a Pro build", badge == null || !badge.isVisible)
            dialog.dismiss()
        }
    }

    @Test
    fun `back from the sheet launches nothing and returns focus to Scan`() {
        launch { activity ->
            val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            val dm = activity.resources.displayMetrics
            val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            repeat(2) {
                root.measure(w, h)
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val disc = activity.findViewById<View>(R.id.swNavScan)
            val dialog = openSheet(activity)
            val toastBefore = ShadowToast.shownToastCount()

            dialog.onBackPressed()
            idle()

            assertFalse("Back closes the sheet", dialog.isShowing)
            assertNull("Back launches no activity", shadowOf(activity).nextStartedActivity)
            assertEquals(
                "Back starts no scanner (no new toast)",
                toastBefore,
                ShadowToast.shownToastCount()
            )
            assertTrue(
                "focus returns to the Scan button (focus=${activity.currentFocus}, " +
                    "tree=${root.findFocus()})",
                disc.hasFocus() || disc.isAccessibilityFocused || root.findFocus() === disc
            )
        }
    }

    @Test
    fun `the Scan button announces that it opens Create`() {
        launch { activity ->
            val disc = activity.findViewById<View>(R.id.swNavScan)
            assertEquals(
                activity.getString(R.string.create_disc_desc),
                disc.contentDescription?.toString()
            )
        }
    }

    @Test
    fun `entries become full width rows at 200 percent font`() {
        RuntimeEnvironment.setFontScale(2.0f)
        launch { activity ->
            val dialog = openSheet(activity)
            val root = dialog.window!!.decorView
            val dm = activity.resources.displayMetrics
            val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            repeat(2) {
                root.measure(w, h)
                root.layout(0, 0, w, h)
            }
            val rows = intArrayOf(
                R.id.swCreateIdCard, R.id.swCreatePassport, R.id.swCreateQr,
                R.id.swCreatePhotos, R.id.swCreateImport
            ).map { entry(dialog, it) }
            val content = dialog.findViewById<View>(R.id.swSheetContent)
            val contentWidth = content.width
            var previousBottom = Int.MIN_VALUE
            for (row in rows) {
                assertTrue("a row is laid out", row.height > 0 && row.width > 0)
                assertTrue(
                    "at 200 % each entry owns the sheet width (${row.width} of $contentWidth), " +
                        "not a narrow tile",
                    row.width >= contentWidth * 0.9f
                )
                val top = topIn(row, root)
                assertTrue(
                    "at 200 % entries stack in one column (top $top after $previousBottom)",
                    top >= previousBottom
                )
                previousBottom = top + row.height
            }
            dialog.dismiss()
        }
    }

    private fun topIn(view: View, root: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== root) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }
}
