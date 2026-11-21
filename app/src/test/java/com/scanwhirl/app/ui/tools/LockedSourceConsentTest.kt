package com.scanwhirl.app.ui.tools
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.PdfProtection
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
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

/**
 * SAFE-UNLOCK-CONSENT — choose a password-protected document as a tool source (Compress,
 * Merge, ZIP, …) and the old flow silently removed its password from the library copy for
 * good. The password only proves the file opens; removing the protection is a separate act
 * and now needs an explicit yes.
 *
 * The consent sheet must sit between the password sheet and `repo.unlockPdf`: while it is
 * open the document is still locked and its bytes are still encrypted, Cancel / Back leave
 * it that way and unselected, and only the primary button unlocks and selects it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LockedSourceConsentTest {


    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private lateinit var protectedId: String
    private lateinit var plainId: String

    @Before
    fun setUp() {
        RuntimeEnvironment.setFontScale(1.0f)
        // The protected document is saved first so the plain one is the newest, but the tests
        // still click the plain row explicitly: the selection never depends on list order.
        protectedId = DocFixtures.locked(repo, app, password = "1234", pages = 2)
        plainId = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
    }

    @After
    fun tearDown() {
        Faults.resetAll()
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

    // ---- the rig ----------------------------------------------------------

    private fun source(activity: Activity): LinearLayout =
        requireNotNull(activity.findViewById<LinearLayout>(R.id.toolSetupSource))

    private fun rowFor(activity: Activity, docId: String): LinearLayout {
        val host = source(activity)
        for (i in 0 until host.childCount) {
            val row = host.getChildAt(i) as? LinearLayout ?: continue
            if (row.tag == docId) return row
        }
        throw AssertionError("no source row for $docId")
    }

    private fun ticked(activity: Activity, docId: String): Boolean =
        rowFor(activity, docId).findViewById<View>(R.id.toolSetupDocCheck).visibility == View.VISIBLE

    /** Waits for the two fixture rows, then makes the plain document the current pick. */
    private fun readyWithPlainPick(activity: Activity) {
        assertTrue(
            "the library lists both fixtures",
            waitUntil {
                source(activity).childCount >= 2 &&
                    runCatching { rowFor(activity, protectedId) }.isSuccess &&
                    runCatching { rowFor(activity, plainId) }.isSuccess
            }
        )
        // Merge/ZIP toggle on tap, so only tap a row that is not the pick already.
        if (!ticked(activity, plainId)) {
            rowFor(activity, plainId).performClick()
            idle()
        }
        assertTrue("the plain document is the pick", ticked(activity, plainId))
        assertFalse("the protected document starts unpicked", ticked(activity, protectedId))
    }

    /** Taps the protected row and returns the password sheet it opens. */
    private fun openPasswordSheet(activity: Activity): Dialog {
        rowFor(activity, protectedId).performClick()
        idle()
        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "no unlock sheet" }
        requireNotNull(sheet.findViewById<View>(R.id.unlockPassword)) {
            "the protected row must open the password sheet first"
        }
        return sheet
    }

    private fun submitPassword(sheet: Dialog, password: String) {
        sheet.findViewById<EditText>(R.id.unlockPassword).setText(password)
        sheet.findViewById<View>(R.id.unlockOpen).performClick()
        idle()
        // S4B: the password check is a suspending PBKDF2 off the main thread. Wait until it is
        // applied: the error line shows, the sheet closes, or the consent sheet opens.
        waitUntil {
            !sheet.isShowing ||
                sheet.findViewById<View>(R.id.unlockError)?.visibility == View.VISIBLE ||
                ShadowDialog.getLatestDialog() !== sheet
        }
    }

    /** The consent sheet: the newest dialog must be the SwSheet with its primary button. */
    private fun consentSheet(): Dialog {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "no consent sheet" }
        requireNotNull(dialog.findViewById<View>(R.id.swSheetPrimary)) {
            "the password must be followed by the removal-consent sheet, not by an unlock"
        }
        return dialog
    }

    private fun Dialog.text(id: Int): String =
        requireNotNull(findViewById<TextView>(id)) { "missing view $id" }.text.toString()

    private fun documentLocked(): Boolean = repo.getDocument(protectedId)?.isLocked == true

    /** The library copy's bytes: a locked file must not be rewritten behind the sheet. */
    private fun protectedBytes(): ByteArray =
        requireNotNull(repo.getDocument(protectedId)).pdfFile.readBytes()

    private fun assertStillProtectedAndUnpicked(activity: Activity, bytesBefore: ByteArray, why: String) {
        assertTrue("$why: the document is still locked", documentLocked())
        assertEquals("$why: the copy in the library was not rewritten", bytesBefore.size, protectedBytes().size)
        assertTrue("$why: the bytes are unchanged", bytesBefore.contentEquals(protectedBytes()))
        assertEquals(
            "$why: the file is still an encrypted PDF",
            true,
            PdfProtection.isEncrypted(requireNotNull(repo.getDocument(protectedId)).pdfFile)
        )
        assertFalse("$why: the protected document stays unselected", ticked(activity, protectedId))
        assertTrue("$why: the earlier pick is untouched", ticked(activity, plainId))
    }

    // ---- 1. the sheet itself ---------------------------------------------

    @Test(timeout = 60_000)
    fun `a protected source asks before the password is removed`() {
        val bytesBefore = protectedBytes()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")

                val consent = consentSheet()
                assertEquals(
                    "the sheet says which file is protected",
                    app.getString(R.string.tools_setup_unlock_consent_title),
                    consent.text(R.id.swSheetTitle)
                )
                assertEquals(
                    "the body names the tool and the permanent change",
                    app.getString(
                        R.string.tools_setup_unlock_consent_body,
                        app.getString(R.string.tool_compress)
                    ),
                    consent.text(R.id.swSheetMessage)
                )
                assertEquals(
                    "the primary button states the change",
                    app.getString(R.string.tools_setup_unlock_consent_primary),
                    consent.text(R.id.swSheetPrimary)
                )
                assertEquals(
                    "Cancel is the quiet second choice",
                    app.getString(R.string.sheet_cancel),
                    consent.text(R.id.swSheetSecondary)
                )
                assertStillProtectedAndUnpicked(activity, bytesBefore, "while the consent sheet is open")
            }
        }
    }

    // ---- 2. Cancel --------------------------------------------------------

    @Test(timeout = 60_000)
    fun `cancel keeps the file encrypted and unselected`() {
        val bytesBefore = protectedBytes()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                val consent = consentSheet()
                consent.findViewById<View>(R.id.swSheetSecondary).performClick()
                idle()
                assertFalse("the sheet is gone", consent.isShowing)
                assertStillProtectedAndUnpicked(activity, bytesBefore, "after Cancel")
            }
        }
        assertTrue("Cancel never unlocks", documentLocked())
    }

    // ---- 3. Back ----------------------------------------------------------

    @Test(timeout = 60_000)
    fun `back keeps the file encrypted and unselected`() {
        val bytesBefore = protectedBytes()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                val consent = consentSheet()
                consent.onBackPressed()
                idle()
                assertFalse("Back dismisses the sheet", consent.isShowing)
                assertStillProtectedAndUnpicked(activity, bytesBefore, "after Back")
            }
        }
        assertTrue("Back never unlocks", documentLocked())
    }

    // ---- 4. the explicit yes ---------------------------------------------

    @Test(timeout = 60_000)
    fun `the primary button removes the password and selects the document`() {
        val attempts = AtomicInteger()
        val main = AtomicInteger()
        ScanRepository.lockDerivationProbe = {
            attempts.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) main.incrementAndGet()
        }
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                consentSheet().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue(
                "only the primary button unlocks the library copy",
                waitUntil { repo.getDocument(protectedId)?.isLocked == false }
            )
            assertEquals(
                "the unlocked file is a plain PDF",
                false,
                PdfProtection.isEncrypted(requireNotNull(repo.getDocument(protectedId)).pdfFile)
            )
            scenario.onActivity { activity ->
                assertTrue("the unlocked document becomes the pick", waitUntil { ticked(activity, protectedId) })
                assertFalse("Compress keeps one source", ticked(activity, plainId))
                assertEquals("no password derivation on the UI thread", 0, main.get())
                assertEquals("consent reuses the proof instead of deriving again", 1, attempts.get())
            }
        }
    }

    @Test(timeout = 60_000)
    fun `merge keeps its earlier pick and adds the document after consent`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "merge")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                consentSheet().findViewById<View>(R.id.swSheetPrimary).performClick()
            }
            assertTrue(
                "the consent unlocks the merge source",
                waitUntil { repo.getDocument(protectedId)?.isLocked == false }
            )
            scenario.onActivity { activity ->
                assertTrue("the unlocked document is added", waitUntil { ticked(activity, protectedId) })
                assertTrue("Merge keeps the earlier pick", ticked(activity, plainId))
            }
        }
    }

    // ---- 5. a wrong password never gets that far -------------------------

    @Test(timeout = 60_000)
    fun `a wrong password never reaches the consent step`() {
        val bytesBefore = protectedBytes()
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                val sheet = openPasswordSheet(activity)
                submitPassword(sheet, "9999")
                assertEquals(
                    "the tries line is shown on the password sheet",
                    View.VISIBLE,
                    sheet.findViewById<View>(R.id.unlockError).visibility
                )
                assertNull(
                    "no consent sheet is offered for a wrong password",
                    ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetPrimary)
                )
                assertTrue("the password sheet is still open", sheet.isShowing)
                assertStillProtectedAndUnpicked(activity, bytesBefore, "after a wrong password")
            }
        }
    }

    // ---- 6. the explicit Unlock PDF tool is not changed ------------------

    @Test(timeout = 60_000)
    fun `the Unlock PDF tool still removes the password with one password and no consent`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val flow = LockFlow(
                    activity,
                    repo,
                    activity,
                    { repo.listDocuments() },
                    { _ -> }
                )
                flow.unlockDocument(requireNotNull(repo.getDocument(protectedId)))
                idle()
                val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "no unlock sheet" }
                assertNotNull(
                    "the Unlock PDF tool opens the password sheet",
                    sheet.findViewById<View>(R.id.unlockPassword)
                )
                submitPassword(sheet, "1234")
            }
            assertTrue(
                "the Unlock PDF tool unlocks with the password alone",
                waitUntil { repo.getDocument(protectedId)?.isLocked == false }
            )
            assertNull(
                "the Unlock PDF tool shows no removal-consent sheet",
                ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.swSheetPrimary)
            )
        }
    }

    // ---- 7. copy, English and Hindi --------------------------------------

    @Test(timeout = 60_000)
    fun `English copy states the change in plain words`() {
        assertEquals("This PDF is protected", app.getString(R.string.tools_setup_unlock_consent_title))
        assertEquals(
            "To use it in Compress, ScanWhirl removes its password from the copy in your library. " +
                "You can protect it again afterwards.",
            app.getString(
                R.string.tools_setup_unlock_consent_body,
                app.getString(R.string.tool_compress)
            )
        )
        assertEquals(
            "Remove password and continue",
            app.getString(R.string.tools_setup_unlock_consent_primary)
        )
        assertEquals("Cancel", app.getString(R.string.sheet_cancel))
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `Hindi copy uses the judged glossary and names the tool`() {
        assertEquals("यह PDF सुरक्षित है", app.getString(R.string.tools_setup_unlock_consent_title))
        val tool = app.getString(R.string.tool_compress)
        assertEquals("फ़ाइल छोटी करें", tool)
        val body = app.getString(R.string.tools_setup_unlock_consent_body, tool)
        assertTrue("the body names the tool: $body", body.contains(tool))
        assertTrue("the body says the password is removed: $body", body.contains("पासवर्ड"))
        assertTrue("the body says where the copy lives: $body", body.contains("कागज़ात"))
        assertTrue("the body promises protection again: $body", body.contains("सुरक्षित"))
        // JUDGE_HINDI.md rejected loanwords must not come back in this new copy.
        for (rejected in listOf("कॉपी", "लाइब्रेरी", "टूल", "कंप्रेस", "क्वालिटी", "कस्टम")) {
            assertFalse("rejected glossary term \"$rejected\" in \"$body\"", body.contains(rejected))
        }
        assertEquals(
            "पासवर्ड हटाएँ और जारी रखें",
            app.getString(R.string.tools_setup_unlock_consent_primary)
        )
        assertEquals("रद्द करें", app.getString(R.string.sheet_cancel))
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `the Hindi sheet shows the Hindi copy and the Hindi tool name`() {
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                val consent = consentSheet()
                assertEquals(app.getString(R.string.tools_setup_unlock_consent_title), consent.text(R.id.swSheetTitle))
                assertTrue(
                    "the Hindi body names the Hindi tool: ${consent.text(R.id.swSheetMessage)}",
                    consent.text(R.id.swSheetMessage).contains(app.getString(R.string.tool_compress))
                )
                assertEquals(
                    app.getString(R.string.tools_setup_unlock_consent_primary),
                    consent.text(R.id.swSheetPrimary)
                )
            }
        }
    }

    // ---- 8. accessibility at 130% and 200% -------------------------------

    private fun assertSheetAccessible(activity: Activity, dialog: Dialog, label: String) {
        val root = requireNotNull(dialog.findViewById<View>(R.id.swSheetRoot)) { "$label: no sheet" }
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

        val pane = ViewCompat.getAccessibilityPaneTitle(requireNotNull(dialog.window).decorView)
        assertEquals("$label starts TalkBack on the title", dialog.text(R.id.swSheetTitle), pane)
    }

    private fun consentAtScale(scale: Float, toolId: String) {
        RuntimeEnvironment.setFontScale(scale)
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, toolId)).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                val consent = consentSheet()
                assertEquals(
                    "the font scale reached the sheet",
                    scale.toDouble(),
                    activity.resources.configuration.fontScale.toDouble(),
                    0.001
                )
                assertSheetAccessible(activity, consent, "$toolId @ ${scale}x")
            }
        }
    }

    @Test(timeout = 60_000)
    fun `the consent sheet is accessible at 130 and 200 percent`() {
        for (scale in floatArrayOf(1.3f, 2.0f)) {
            // Fresh fixtures per scale so a rewrite never leaks between runs.
            protectedId = DocFixtures.locked(repo, app, password = "1234", pages = 1)
            plainId = DocFixtures.saved(repo, app, pages = 1, name = "Rent receipt")
            consentAtScale(scale, "compress")
        }
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @Test(timeout = 60_000)
    @Config(qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `the Hindi consent sheet is accessible at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch<ToolSetupActivity>(ToolSetupActivity.intent(app, "compress")).use { scenario ->
            scenario.onActivity { activity ->
                readyWithPlainPick(activity)
                submitPassword(openPasswordSheet(activity), "1234")
                assertSheetAccessible(activity, consentSheet(), "Hindi compress @ 2.0x")
            }
        }
    }
}
