package com.scanwhirl.app.ui.tools

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import org.junit.Assert.assertEquals
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

/**
 * UI-B3 (#7): the Compress setup sheet must offer two explicit aims, "Choose quality" and
 * "Aim for a file size". On the base build the quality list stayed selected while a typed
 * size silently won, an unparseable size was only a toast, and the primary said "OK".
 *
 * The phone path is the document More row -> Compress -> the level sheet, measured here in
 * English and Hindi at 360dp.
 */
abstract class CompressModeSheetBase {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private var sourceId = ""

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
        sourceId = DocFixtures.saved(repo, app, pages = 2, name = "Mode sheet")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    /** Document More -> Compress; returns the sheet's decor once the estimates have landed. */
    private fun openSheet(activity: android.app.Activity): View {
        idle()
        assertTrue(
            "the document loads before the sheet opens",
            pollUntil {
                activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
            }
        )
        activity.findViewById<View>(R.id.btnMore).performClick()
        idle()
        val more = requireNotNull(ShadowDialog.getLatestDialog()) { "the More sheet opens" }
        more.findViewById<View>(R.id.rowMoreCompress).performClick()
        assertTrue(
            "the compress setup sheet opens",
            pollUntil {
                ShadowDialog.getLatestDialog()?.window?.decorView
                    ?.findViewById<View>(R.id.editCompressTarget) != null
            }
        )
        idle()
        return requireNotNull(ShadowDialog.getLatestDialog()?.window?.decorView)
    }

    private fun launch(action: (android.app.Activity) -> Unit) {
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, sourceId)
        ).use { scenario ->
            scenario.onActivity { activity -> action(activity) }
        }
    }

    /** No result may have been started and no new document may exist. */
    private fun assertNothingRan(activity: android.app.Activity, before: Int) {
        val started = pollStartedResult(activity, timeoutMs = 1500)
        assertNull("an invalid or unchosen mode must run nothing: $started", started)
        assertEquals(
            "no compressed copy may appear for a run that did not start",
            before,
            repo.listDocuments().size
        )
    }

    private fun pollStartedResult(
        activity: android.app.Activity,
        timeoutMs: Long = 20_000
    ): Intent? {
        var started: Intent? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            started = shadowOf(activity).peekNextStartedActivity()
            if (started != null) break
            Thread.sleep(20)
        }
        idle()
        return started ?: shadowOf(activity).peekNextStartedActivity()
    }

    // ---- the two modes ----------------------------------------------------

    @Test
    fun `the sheet offers both aims and the primary says Compress`() {
        launch { activity ->
            val decor = openSheet(activity)
            val quality = decor.findViewById<RadioButton>(R.id.compressModeQuality)
            val target = decor.findViewById<RadioButton>(R.id.compressModeTarget)
            assertNotNull("the quality aim is offered", quality)
            assertNotNull("the aim-for-a-size choice is offered", target)
            assertEquals(
                "the quality aim reads plainly",
                app.getString(R.string.compress_mode_quality),
                quality.text.toString()
            )
            assertEquals(
                "the size aim reads plainly",
                app.getString(R.string.compress_mode_target),
                target.text.toString()
            )
            assertTrue("quality is the starting aim", quality.isChecked)
            val primary = decor.findViewById<TextView>(R.id.swSheetPrimary)
            assertEquals(
                "the primary action names the job",
                app.getString(R.string.compress_action),
                primary.text.toString()
            )
            assertTrue(
                "B3: the primary is not the bare 'OK' it used to be",
                primary.text.toString() != app.getString(android.R.string.ok)
            )
        }
    }

    @Test
    fun `choosing one aim visibly deselects the other`() {
        launch { activity ->
            val decor = openSheet(activity)
            val quality = decor.findViewById<RadioButton>(R.id.compressModeQuality)
            val target = decor.findViewById<RadioButton>(R.id.compressModeTarget)
            target.performClick()
            idle()
            assertTrue("selecting the size aim checks it", target.isChecked)
            assertTrue("and unchecks the quality aim", !quality.isChecked)
            val qualityVisible = decor.findViewById<View>(R.id.levelGroup)
            val targetVisible = decor.findViewById<View>(R.id.compressTargetBlock)
            assertEquals(
                "the quality rows leave the sheet when the size aim is chosen",
                View.GONE,
                qualityVisible.visibility
            )
            assertEquals(
                "the size field appears when the size aim is chosen",
                View.VISIBLE,
                targetVisible.visibility
            )
            quality.performClick()
            idle()
            assertTrue("selecting quality checks it", quality.isChecked)
            assertTrue("and unchecks the size aim", !target.isChecked)
            assertEquals(View.VISIBLE, qualityVisible.visibility)
            assertEquals(View.GONE, targetVisible.visibility)
        }
    }

    @Test
    fun `the KB or MB hint is always visible under the field`() {
        launch { activity ->
            val decor = openSheet(activity)
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            val hint = decor.findViewById<TextView>(R.id.compressTargetHint)
            assertNotNull("the size field keeps a KB/MB hint", hint)
            assertEquals(View.VISIBLE, hint.visibility)
            assertEquals(
                app.getString(R.string.compress_target_help),
                hint.text.toString()
            )
            // It is a hint that stays put, not the EditText hint that vanishes on typing.
            val field = decor.findViewById<EditText>(R.id.editCompressTarget)
            field.setText("700 KB")
            idle()
            assertEquals("the hint survives typed text", View.VISIBLE, hint.visibility)
        }
    }

    // ---- nothing runs on a wrong input ------------------------------------

    @Test
    fun `20KBg shows an inline error and runs nothing`() {
        val before = repo.listDocuments().size
        launch { activity ->
            val decor = openSheet(activity)
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            decor.findViewById<EditText>(R.id.editCompressTarget).setText("20KBg")
            idle()
            decor.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()

            val error = decor.findViewById<TextView>(R.id.compressTargetError)
            assertNotNull("the sheet shows the error inline", error)
            assertEquals(
                "the inline error explains the expected size: '${error.text}'",
                app.getString(R.string.resize_invalid_target),
                error.text.toString()
            )
            assertEquals("the error is visible", View.VISIBLE, error.visibility)
            assertTrue(
                "the sheet stays open so the size can be fixed",
                ShadowDialog.getLatestDialog()?.isShowing == true
            )
            assertNothingRan(activity, before)
        }
        assertNull("B3: no toast may carry the only explanation", latestToast())
    }

    @Test
    fun `a blank size in size mode asks for one and runs nothing`() {
        val before = repo.listDocuments().size
        launch { activity ->
            val decor = openSheet(activity)
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            decor.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            val error = decor.findViewById<TextView>(R.id.compressTargetError)
            assertEquals(
                "a missing size is named, not guessed",
                app.getString(R.string.compress_target_required),
                error.text.toString()
            )
            assertEquals(View.VISIBLE, error.visibility)
            assertNothingRan(activity, before)
        }
    }

    // ---- the mode decides, not leftover text ------------------------------

    @Test
    fun `quality mode ignores a stale typed target`() {
        launch { activity ->
            val decor = openSheet(activity)
            // Type a size, then deliberately go back to quality.
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            decor.findViewById<EditText>(R.id.editCompressTarget).setText("5 KB")
            idle()
            decor.findViewById<RadioButton>(R.id.compressModeQuality).performClick()
            idle()
            decor.findViewById<View>(R.id.swSheetPrimary).performClick()

            val started = pollStartedResult(activity)
            assertNotNull("quality mode still starts the compress", started)
            assertEquals(
                "B3: the stale typed size must not silently win in quality mode",
                0L,
                started!!.getLongExtra("target_bytes", 0L)
            )
        }
    }

    @Test
    fun `size mode runs the typed size`() {
        launch { activity ->
            val decor = openSheet(activity)
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            decor.findViewById<EditText>(R.id.editCompressTarget).setText("7 KB")
            idle()
            decor.findViewById<View>(R.id.swSheetPrimary).performClick()

            val started = pollStartedResult(activity)
            assertNotNull("size mode starts the compress", started)
            assertEquals(
                "the typed limit is the one that runs",
                7L * 1024L,
                started!!.getLongExtra("target_bytes", 0L)
            )
        }
    }

    /** Fires the keyboard's Done action exactly as the IME does. */
    private fun pressImeDone(field: EditText): Boolean {
        var c: Class<*>? = field.javaClass
        while (c != null) {
            try {
                val m = c.getDeclaredMethod("onEditorAction", Int::class.javaPrimitiveType)
                m.isAccessible = true
                return m.invoke(field, EditorInfo.IME_ACTION_DONE) as? Boolean ?: true
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw AssertionError("EditText has no editor-action hook")
    }

    @Test
    fun `IME Done runs the compress once`() {
        launch { activity ->
            val decor = openSheet(activity)
            decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
            idle()
            val field = decor.findViewById<EditText>(R.id.editCompressTarget)
            field.setText("7 KB")
            idle()
            pressImeDone(field)
            // A second Done (or a race with the button) must not start the job again.
            pressImeDone(field)
            idle()

            val started = pollStartedResult(activity)
            assertNotNull("Done starts the compress", started)
            assertEquals("with the typed limit", 7L * 1024L, started!!.getLongExtra("target_bytes", 0L))
            // Consume the first result the run legitimately started.
            shadowOf(activity).getNextStartedActivity()
            idle()
            assertNull(
                "B3: Done must never start the compress twice",
                shadowOf(activity).peekNextStartedActivity()
            )
        }
    }

    // ---- 200% and the keyboard -------------------------------------------

    @Test
    fun `the sheet scrolls above the keyboard at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        try {
            launch { activity ->
                val decor = openSheet(activity)
                assertEquals(
                    "B3: the sheet asks the window to resize for the keyboard",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    ShadowDialog.getLatestDialog()!!.window!!.attributes.softInputMode and
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
                )
                val sheetRoot = requireNotNull(decor.findViewById<View>(R.id.swSheetRoot)) {
                    "the sheet root is on screen"
                }
                val scroll = decor.findViewById<NestedScrollView>(R.id.compressScroll)
                assertNotNull("the sheet content sits in a scroll view", scroll)

                // The phone with the keyboard up: a short viewport, 200% type. The six rows of
                // the quality aim already do not fit, and must scroll rather than be cut off.
                layoutAt(activity, sheetRoot, heightDp = 520)
                assertTrue(
                    "B3: at 200% the quality list does not fit above the keyboard",
                    scroll!!.canScrollVertically(1)
                )

                // The size aim on a small phone: the field must still be reachable by scrolling.
                decor.findViewById<RadioButton>(R.id.compressModeTarget).performClick()
                idle()
                layoutAt(activity, sheetRoot, heightDp = 320)
                assertTrue(
                    "B3: the size field needs the sheet to scroll on a small phone",
                    scroll.canScrollVertically(1)
                )
                scroll.scrollTo(0, 100_000)
                idle()
                val field = decor.findViewById<View>(R.id.editCompressTarget)
                val visible = android.graphics.Rect()
                assertTrue(
                    "the size field can be scrolled into the visible part of the sheet",
                    field.getGlobalVisibleRect(visible) && visible.height() > 0
                )
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    private fun layoutAt(activity: android.app.Activity, root: View, heightDp: Int) {
        val d = activity.resources.displayMetrics.density
        val w = Math.round(360f * d)
        val h = Math.round(heightDp * d)
        val passW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val passH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        repeat(2) {
            root.measure(passW, passH)
            root.layout(0, 0, w, h)
        }
        idle()
    }

    /** The latest toast text, or null when none was shown. */
    private fun latestToast(): String? = org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
}

/** The phone the sweep used: 360dp, English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompressModeSheetEnTest : CompressModeSheetBase()

/** The same phone in Hindi, where both aims and the hint are longer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class CompressModeSheetHiTest : CompressModeSheetBase()
