package com.scanwhirl.app.sh2
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.res.ColorStateList
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.kit.ToolRegistry
import com.scanwhirl.app.ui.kit.ToolStatus
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * SH2 (screenshot critique run5/6): a non-destructive sheet closes with X only, never with a
 * second Cancel/Close footer button; a destructive confirmation keeps an explicit Cancel.
 * These assertions use only base-SHA resource ids so the RED run fails on named assertions,
 * not on a compile error.
 */
abstract class Sh2SheetPolicyBase {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    protected lateinit var app: Application
    protected val hi: Boolean get() = app.resources.configuration.locales[0].language == "hi"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
    }

    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }

    protected fun texts(view: View): List<TextView> = allViews(view).filterIsInstance<TextView>().toList()

    private fun waitUntil(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        idle()
        assertTrue("condition not met before timeout", condition())
    }

    @Test(timeout = 30_000)
    fun `compress sheet has X only plain title label placeholder helper and a 2x2 chip grid`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "SH2 compress doc")
        val controller: ActivityController<DocumentViewerActivity> = Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        )
        val activity = controller.setup().get()
        try {
            waitUntil { activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
            activity.findViewById<View>(R.id.btnMore).performClick()
            idle()
            ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.rowMoreCompress).performClick()
            waitUntil { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.editCompressTarget) != null }
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val decor = dialog.window!!.decorView

            assertEquals(
                "the sheet title names the job, not its level",
                if (hi) "फ़ाइल छोटी करें" else "Compress",
                decor.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            assertEquals(
                "non-destructive: no duplicate Cancel footer",
                View.GONE,
                decor.findViewById<View>(R.id.swSheetSecondary).visibility
            )
            assertTrue("the X close stays", decor.findViewById<View>(R.id.swSheetClose).isVisible)

            // COPY_DIET HI wording: the radio is the one persistent "Maximum file size" label.
            val label = if (hi) "फ़ाइल का अधिकतम आकार" else "Maximum file size"
            assertTrue(
                "a persistent '$label' label is on screen",
                texts(decor).any { it.text.toString() == label && it.visibility == View.VISIBLE }
            )
            assertEquals(
                "the field placeholder is the 100 KB example",
                "100 KB",
                decor.findViewById<EditText>(R.id.editCompressTarget).hint.toString()
            )
            assertEquals(
                "the helper stays short and truthful",
                if (hi) "KB या MB में लिखें। अकेले अंकों का मतलब KB है।"
                else "Enter KB or MB. Numbers alone mean KB.",
                decor.findViewById<TextView>(R.id.compressTargetHint).text.toString()
            )

            val c20 = decor.findViewById<Chip>(R.id.v2t_quick_20)
            val c50 = decor.findViewById<Chip>(R.id.v2t_quick_50)
            val c100 = decor.findViewById<Chip>(R.id.v2t_quick_100)
            val c200 = decor.findViewById<Chip>(R.id.v2t_quick_200)
            listOf(c20, c50, c100, c200).forEach { assertNotNull("all four KB chips exist", it) }
            val row1 = c20.parent
            val row2 = c100.parent
            assertTrue("first row is a horizontal row of two", row1 is ViewGroup && row1.childCount == 2)
            assertTrue("second row is a horizontal row of two", row2 is ViewGroup && row2.childCount == 2)
            assertTrue("20 and 50 KB share the first row", c50.parent === row1)
            assertTrue("100 and 200 KB share the second row", c200.parent === row2)
            assertFalse("the chips form 2 rows, not one", row1 === row2)
            val density = app.resources.displayMetrics.density
            listOf(c20, c50, c100, c200).forEach {
                assertTrue("chip is a >=48dp target", it.minimumHeight >= 48 * density - 1)
            }

            c100.performClick()
            idle()
            assertTrue("a tapped chip is selected", c100.isChecked)
            assertFalse("only one chip is selected", c50.isChecked)
            assertTrue("the selected chip keeps a visible outline", c100.chipStrokeWidth > 0f)
            assertTrue("chip fill is a state list", c100.chipBackgroundColor is ColorStateList)
            assertTrue("chip stroke is a state list", c100.chipStrokeColor is ColorStateList)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `quick tools editor has one instruction line save label X only and stays expanded`() {
        // Six saved tools expose the old duplicate cap line; one line must replace both.
        Prefs.setQuickTools(
            app, ToolRegistry.all.filter { it.status == ToolStatus.READY }.map { it.id }.distinct().take(6)
        )
        val controller: ActivityController<ShellActivity> = Robolectric.buildActivity(ShellActivity::class.java)
        val activity = controller.setup().get()
        try {
            idle()
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) { "the editor opens" }
            val decor = dialog.window!!.decorView

            assertEquals(
                "the primary is the named save action",
                if (hi) "झटपट काम सहेजें" else "Save quick tools",
                decor.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
            assertEquals(
                "non-destructive: no duplicate Cancel footer",
                View.GONE,
                decor.findViewById<View>(R.id.swSheetSecondary).visibility
            )
            assertTrue("the X close stays", decor.findViewById<View>(R.id.swSheetClose).isVisible)

            val instruction = if (hi) "6 तक औज़ार चुनें। क्रम बदलने के लिए तीर इस्तेमाल करें।"
            else "Choose up to 6 tools. Use the arrows to reorder."
            assertTrue(
                "the single instruction line is shown",
                texts(decor).any { it.text.toString() == instruction && it.visibility == View.VISIBLE }
            )
            val oldCap = if (hi) "ज़्यादा से ज़्यादा 6 झटपट काम रख सकते हैं।"
            else "You can keep up to 6 quick tools."
            assertFalse("the duplicate cap line is gone", texts(decor).any { it.text.toString() == oldCap })

            val content = dialog.findViewById<View>(R.id.swSheetContent)
            assertNotNull("the editor has content", content)
            val first = allViews(content).filterIsInstance<CheckBox>().first()
            first.isChecked = false
            idle()
            assertTrue("the sheet stays expanded while toggling", dialog.isShowing)
            assertTrue("Save stays pinned while toggling", decor.findViewById<View>(R.id.swSheetPrimary).isVisible)
            assertTrue("the editor still scrolls", allViews(content).any { it is ScrollView })

            val before = Prefs.quickTools(app)
            decor.findViewById<View>(R.id.swSheetClose).performClick()
            idle()
            assertFalse("X closes the editor", dialog.isShowing)
            assertEquals("X discards the draft", before, Prefs.quickTools(app))
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en")
class Sh2SheetPolicyEnTest : Sh2SheetPolicyBase()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class Sh2SheetPolicyHiTest : Sh2SheetPolicyBase()
