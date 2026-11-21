package com.scanwhirl.app.ui.kit

import android.app.Application
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.ui.home.PageHitViews
import com.scanwhirl.app.ui.protect.LockToolsActivity
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.PageTextMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Lane KT1 (Hy4 hunt at 5a1b82ff): shared UI kit robustness. Every assertion here is written
 * against the public kit API only, so the file compiles on the base SHA and fails RED for the
 * nine defects before the kit is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class Kt1KitFixesTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun newController(): ActivityController<AppCompatActivity> {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_ScanWhirl)
        return controller.setup()
    }

    private fun withActivity(block: (AppCompatActivity) -> Unit) {
        val controller = newController()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun doc(name: String = "Fictional rent receipt") = ScanDocument(
        id = name,
        name = name,
        createdAt = 1_700_000_000_000L,
        pageCount = 2,
        pdfFile = File(app.cacheDir, "kt1.pdf"),
        pageFiles = emptyList(),
        category = CategoryRules.RECEIPTS
    )

    // ---- Defect 1: dialogs follow their owner's lifecycle -------------------------------

    @Test
    fun `SwSheet is dismissed when its owner activity is destroyed`() {
        val controller = newController()
        val sheet = SwSheet.builder(controller.get()).setTitle("Leak check").setPrimary("OK").build().show()
        idle()
        assertTrue("sheet is up before destruction", sheet.isShowing)
        controller.pause().stop().destroy()
        idle()
        assertFalse("a destroyed owner must not leave the window behind", sheet.isShowing)
    }

    @Test
    fun `destroying the owner does not fire caller dismiss callbacks`() {
        val controller = newController()
        var dismissals = 0
        val sheet = SwSheet.builder(controller.get()).setTitle("Rotation")
            .setPrimary("OK").build()
        sheet.setOnDismissListener { dismissals++ }
        sheet.show()
        idle()
        controller.pause().stop().destroy()
        idle()
        assertEquals("a dead screen must not run the sheet's dismiss logic", 0, dismissals)
    }

    @Test
    fun `a manual dismiss followed by finish runs onDismiss exactly once`() {
        val controller = newController()
        var dismissals = 0
        val sheet = SwSheet.builder(controller.get()).setTitle("Manual")
            .setPrimary("OK").build()
        sheet.setOnDismissListener { dismissals++ }
        sheet.show()
        idle()
        // Off-main dismissal is still pending when finish() lands: the shared boolean used to
        // let the lifecycle dismissal suppress this manual one, losing its callbacks entirely.
        val tap = Thread { sheet.dismiss() }
        tap.start()
        tap.join()
        controller.pause().stop().destroy()
        idle()
        assertEquals("the manual dismissal's callbacks must run exactly once", 1, dismissals)
    }

    @Test
    fun `a main-thread manual dismiss followed by finish still runs onDismiss once`() {
        val controller = newController()
        var dismissals = 0
        val sheet = SwSheet.builder(controller.get()).setTitle("Manual")
            .setPrimary("OK").build()
        sheet.setOnDismissListener { dismissals++ }
        sheet.show()
        idle()
        sheet.dismiss()
        controller.pause().stop().destroy()
        idle()
        assertEquals("one manual dismissal, one callback", 1, dismissals)
    }

    @Test
    fun `a credential field is wiped when the owner is destroyed without a dismiss callback`() {
        val controller = newController()
        val activity = controller.get()
        val sheet = SwSheet.builder(activity).setTitle("Unlock").setPrimary("Go").build()
        val field = android.widget.EditText(activity)
        sheet.bindCredentialSubmit(
            activity = activity,
            field = field,
            submitLabel = R.string.sheet_got_it,
            read = { field.text.toString() },
            work = { it },
            onResult = {}
        )
        // Not shown: there is no onDismiss callback at all, so only the owner's ON_DESTROY
        // observer can wipe the secret. That is the path the old code missed.
        field.setText("secret123")
        assertEquals("the secret is in the field before destruction", "secret123", field.text.toString())
        controller.pause().stop().destroy()
        idle()
        assertEquals("the secret must not survive its owner", "", field.text.toString())
    }

    @Test
    fun `a credential field is also wiped when a shown sheet is destroyed`() {
        val controller = newController()
        val activity = controller.get()
        val sheet = SwSheet.builder(activity).setTitle("Unlock").setPrimary("Go").build()
        val field = android.widget.EditText(activity)
        sheet.bindCredentialSubmit(
            activity = activity,
            field = field,
            submitLabel = R.string.sheet_got_it,
            read = { field.text.toString() },
            work = { it },
            onResult = {}
        )
        sheet.show()
        idle()
        field.setText("secret123")
        controller.pause().stop().destroy()
        idle()
        assertEquals("the secret must not survive its owner", "", field.text.toString())
    }

    @Test
    fun `SwProgress is dismissed when its owner activity is destroyed`() {
        val controller = newController()
        val progress = SwProgress.show(controller.get(), "Working")
        idle()
        assertTrue("progress is up before destruction", progress.isShowing)
        controller.pause().stop().destroy()
        idle()
        assertFalse("progress must not leak its window", progress.isShowing)
    }

    @Test
    fun `DocumentPicker is dismissed when its owner activity is destroyed`() {
        val controller = newController()
        val picker = DocumentPicker.show(controller.get(), "Choose", listOf(doc()), onPick = {})
        idle()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue("picker is up before destruction", dialog.isShowing)
        controller.pause().stop().destroy()
        idle()
        assertFalse("picker must not keep the destroyed activity", dialog.isShowing)
    }

    @Test
    fun `LockTools chooser comes back after recreation when nothing was picked`() {
        ActivityScenario.launch<LockToolsActivity>(
            LockToolsActivity.intent(app, LockToolsActivity.MODE_PROTECT)
        ).use { scenario ->
            idle()
            val before = ShadowDialog.getLatestDialog()
            assertNotNull("the chooser is open", before)
            scenario.recreate()
            idle()
            val after = ShadowDialog.getLatestDialog()
            assertNotSame("a fresh chooser is shown after rotation", before, after)
            assertNotNull("the re-shown chooser is the document picker", after?.findViewById<View>(R.id.docPickerSearch))
        }
    }

    // ---- Defect 2: one pick per picker --------------------------------------------------

    @Test
    fun `a double tap on a document row picks only once`() {
        withActivity { activity ->
            var picks = 0
            val picker = DocumentPicker.show(activity, "Choose", listOf(doc()), onPick = { picks++ })
            idle()
            val row = picker.rows.single()
            row.performClick()
            row.performClick()
            assertEquals("a double tap must not fire onPick twice", 1, picks)
        }
    }

    @Test
    fun `a double tap on the scan and extra actions fires each route only once`() {
        withActivity { activity ->
            var scanned = 0
            var extra = 0
            val picker = DocumentPicker.show(
                activity, "Choose", emptyList(),
                extraAction = DocumentPicker.ExtraAction("From a PDF file") { extra++ },
                onEmptyScan = { scanned++ },
                onPick = {}
            )
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val scan = dialog.findViewById<View>(R.id.docPickerEmptyScan)!!
            val fromPdf = dialog.findViewById<View>(R.id.docPickerExtra)!!
            scan.performClick(); scan.performClick()
            assertEquals("Scan fired once", 1, scanned)
            // A fresh picker for the extra action: the first click dismissed the old one.
            val picker2 = DocumentPicker.show(
                activity, "Choose", emptyList(),
                extraAction = DocumentPicker.ExtraAction("From a PDF file") { extra++ },
                onPick = {}
            )
            idle()
            val second = requireNotNull(ShadowDialog.getLatestDialog())
            val fromPdf2 = second.findViewById<View>(R.id.docPickerExtra)!!
            fromPdf2.performClick(); fromPdf2.performClick()
            assertEquals("the extra route fired once", 1, extra)
        }
    }

    // ---- Defect 3: sheet actions are one-shot -------------------------------------------

    @Test
    fun `primary and secondary actions ignore a double tap`() {
        withActivity { activity ->
            var primary = 0
            var secondary = 0
            val sheet = SwSheet.builder(activity).setTitle("Actions")
                .setPrimary("Go") { primary++ }
                .setSecondary("Cancel") { secondary++ }
                .build().show()
            idle()
            val p = sheet.primaryButton
            val s = sheet.secondaryButton
            p.performClick(); p.performClick()
            s.performClick(); s.performClick()
            assertEquals("primary fired once", 1, primary)
            assertEquals("secondary fired once", 1, secondary)
        }
    }

    @Test
    fun `a single-choice option ignores a double tap`() {
        withActivity { activity ->
            var picks = 0
            val sheet = SwSheet.builder(activity).setTitle("Pick")
                .setOptions(listOf("One", "Two"), checked = 0) { _, _ -> picks++ }
                .build().show()
            idle()
            val list = sheet.view.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
            val row = list.getChildAt(0)
            row.performClick(); row.performClick()
            assertEquals("one option pick", 1, picks)
        }
    }

    // ---- Defect 4: no eligible documents is not a dead end -------------------------------

    @Test
    fun `a nonempty library with no eligible documents shows the caller's route and honest copy`() {
        withActivity { activity ->
            var routed = 0
            DocumentPicker.show(
                activity, "Choose", emptyList(),
                noEligibleTitle = R.string.r3fit_no_protectable,
                onEmptyScan = { routed++ },
                onEmptyImport = { routed++ },
                onPick = {}
            )
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertEquals("the honest no-eligible title", activity.getString(R.string.r3fit_no_protectable),
                dialog.findViewById<android.widget.TextView>(R.id.docPickerEmptyTitle)?.text?.toString())
            val body = dialog.findViewById<android.widget.TextView>(R.id.docPickerEmptyBody)
            assertNotNull("the dead end explains itself", body)
            assertTrue("the body is not blank", body!!.text.toString().isNotBlank())
            assertEquals(View.VISIBLE, body.visibility)
            val scan = dialog.findViewById<View>(R.id.docPickerEmptyScan)!!
            val import = dialog.findViewById<View>(R.id.docPickerEmptyImport)!!
            assertEquals("the caller's Scan route is offered", View.VISIBLE, scan.visibility)
            assertEquals("the caller's Import route is offered", View.VISIBLE, import.visibility)
            scan.performClick()
            assertEquals("the route works", 1, routed)
        }
    }

    // ---- Defect 5: DocCard keeps its metadata and speaks selection state -----------------

    @Test
    fun `a search hit keeps name meta folder and badge in the spoken description`() {
        withActivity { activity ->
            val document = doc().copy(
                pageCount = 4,
                folder = "Rentals",
                cachedSize = 1_234_000L,
                revision = 9L
            )
            val hit = DocSearchHit(
                docId = document.id, revision = document.revision, pageIndex = 1,
                pageKey = "page_001.jpg", query = "ZEPHYRQUILL",
                excerpt = PageTextMatches.Excerpt("a ZEPHYRQUILL match", emptyList())
            )
            val card = DocCard(activity)
            card.bind(document, "4 pages · 1.2 MB · Yesterday", onShare = {}, onMore = {}, search = hit)
            val spoken = card.contentDescription?.toString().orEmpty()
            assertTrue("name survives: $spoken", spoken.contains("Fictional rent receipt"))
            assertTrue("pages survive: $spoken", spoken.contains("4 pages"))
            assertTrue("size survives: $spoken", spoken.contains("1.2 MB"))
            assertTrue("date survives: $spoken", spoken.contains("Yesterday"))
            assertTrue("folder survives: $spoken", spoken.contains("Rentals"))
            assertTrue("match is added: $spoken", spoken.contains("ZEPHYRQUILL"))
            assertTrue("page is named: $spoken", spoken.contains("page 2", ignoreCase = true))
        }
    }

    @Test
    fun `a protected row keeps its badge in the description without leaking a hit`() {
        withActivity { activity ->
            val document = doc().copy(isLocked = true, folder = "Rentals")
            val stale = DocSearchHit(
                docId = document.id, revision = 0L, pageIndex = 0,
                pageKey = "page_000.jpg", query = "SECRET",
                excerpt = PageTextMatches.Excerpt("SECRET must stay silent", emptyList())
            )
            val card = DocCard(activity)
            card.bind(document, "2 pages · 12 KB · Today", onShare = {}, onMore = {}, search = stale)
            val spoken = card.contentDescription?.toString().orEmpty()
            assertTrue("name survives: $spoken", spoken.contains("Fictional rent receipt"))
            assertTrue("protected badge survives: $spoken", spoken.contains(
                activity.getString(R.string.home_protected)))
            assertFalse("a protected row never speaks a stale hit: $spoken", spoken.contains("SECRET"))
        }
    }

    @Test
    fun `selection mode is a checkable TalkBack target`() {
        withActivity { activity ->
            val card = DocCard(activity)
            card.bind(doc(), "2 pages · 12 KB · Today", onShare = {}, onMore = {})
            card.setSelection(true, true)
            val info = AccessibilityNodeInfo.obtain()
            card.onInitializeAccessibilityNodeInfo(info)
            assertTrue("selection mode is checkable", info.isCheckable)
            assertTrue("a selected card is checked", info.isChecked)

            card.setSelection(true, false)
            val cleared = AccessibilityNodeInfo.obtain()
            card.onInitializeAccessibilityNodeInfo(cleared)
            assertFalse("an unselected card is not checked", cleared.isChecked)
        }
    }

    // ---- Defect 6: password fields do not learn or suggest -------------------------------

    @Test
    fun `a password field opts out of learning and suggestions in both states`() {
        withActivity { activity ->
            val field = PasswordField(activity)
            val noLearning = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            val noSuggestions = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            fun assertFlags(where: String) {
                assertTrue("$where: no personalized learning",
                    field.editText.imeOptions and noLearning == noLearning)
                assertTrue("$where: no suggestions",
                    field.editText.inputType and noSuggestions == noSuggestions)
            }
            assertFlags("hidden password")
            field.toggleVisibility()
            assertFlags("visible password")
            field.toggleVisibility()
            assertFlags("hidden again")
        }
    }

    // ---- Defect 7: chips have a 48dp target ---------------------------------------------

    @Test
    fun `a chip has a 48dp touch target with its visual face inset`() {
        withActivity { activity ->
            val chip = SwChip(activity).bind(null, "Scan")
            assertTrue("chip touch target is 48dp", chip.minimumHeight >= SwStyle.dp(activity, 48f))
            assertTrue("the face is inset so the visual height is unchanged",
                chip.background is InsetDrawable)
        }
    }

    // ---- Defect 8: a null action clears the previous one ---------------------------------

    @Test
    fun `rebinding an empty state without an action removes the stale action`() {
        withActivity { activity ->
            var calls = 0
            val state = SwEmptyState(activity)
            state.bind("First", "Body", "Do it") { calls++ }
            assertEquals(View.VISIBLE, state.action.visibility)
            state.bind("Second", "Body")
            assertEquals("the stale action is hidden", View.GONE, state.action.visibility)
            state.action.performClick()
            assertEquals("the stale listener is gone", 0, calls)
            state.bind("Third", "Body", "Again") { calls++ }
            assertEquals(View.VISIBLE, state.action.visibility)
            state.action.performClick()
            assertEquals("a new action works", 1, calls)
        }
    }

    // ---- Defect 9: the extra action stays pinned at 200% --------------------------------

    @Test
    fun `the PDF extra action stays on screen in a short window`() {
        val previous = RuntimeEnvironment.getQualifiers()
        RuntimeEnvironment.setFontScale(2f)
        try {
            withActivity { activity ->
                val docs = List(12) { doc("Fictional document $it") }
                DocumentPicker.show(
                    activity, "Choose", docs,
                    extraAction = DocumentPicker.ExtraAction("From a PDF file") {},
                    onPick = {}
                )
                idle()
                val root = requireNotNull(ShadowDialog.getLatestDialog())
                    .findViewById<View>(R.id.docPickerSheetRoot)
                val density = activity.resources.displayMetrics.density
                val width = (360 * density).toInt()
                val height = (320 * density).toInt()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val extra = root.findViewById<View>(R.id.docPickerExtra)
                assertNotNull("the extra action exists", extra)
                assertTrue(
                    "extra is pinned inside a 320dp window: bottom=${extra!!.bottom} root=${root.height}",
                    extra.bottom <= root.height - root.paddingBottom
                )
                val scroll = root.findViewById<android.widget.ScrollView>(R.id.docPickerScroll)
                assertTrue("the document list still scrolls above it", scroll.canScrollVertically(1))
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers(previous)
        }
    }
}
