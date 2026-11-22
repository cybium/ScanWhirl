package com.scanwhirl.app.ux130.cb
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.shell.LibraryScreen
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Council lane B acceptance: design the one-row band and the checkbox editor at 100 %
 * first (360dp and 411dp), then prove 130 % and 200 % keep the same whole-word, no-clip
 * behaviour in EN and HI. The V2L large-text reflow path stays intact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CbTextSizeTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        Prefs.resetQuickTools(app)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun whole(view: TextView, where: String) {
        val lay = view.layout ?: return
        for (line in 0 until lay.lineCount) {
            assertEquals("$where line $line is ellipsized", 0, lay.getEllipsisCount(line))
            assertTrue(
                "$where line $line exceeds its box",
                lay.getLineMax(line) <= view.width - view.totalPaddingLeft - view.totalPaddingRight + 1
            )
        }
    }

    private fun runAt(width: Int, scale: Float, lang: String) {
        val langTag = if (lang == "hi") "hi-rIN" else "en-rUS"
        RuntimeEnvironment.setFontScale(scale)
        RuntimeEnvironment.setQualifiers("$langTag-w${width}dp-h800dp-xhdpi")
        Prefs.setLocaleTag(app, if (lang == "hi") "hi" else "")
        val repo = ScanRepository(app)
        repeat(3) { DocFixtures.saved(repo, app, pages = 1, name = "Council doc $it") }
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val dm = activity.resources.displayMetrics
            repeat(3) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            idle()
            val where = "$lang ${width}dp @${scale}x"
            val density = dm.density
            for (id in intArrayOf(R.id.swAllFolders, R.id.swSort, R.id.swDocCount, R.id.cbDocHeading)) {
                val control = activity.findViewById<TextView>(id)
                assertNotNull("$where control $id exists", control)
                if (id != R.id.cbDocHeading) {
                    assertTrue("$where control $id is >=48dp", control.height >= 48 * density - 1)
                }
                whole(control, "$where control $id")
            }
            // One row only: the folder/category rails are no longer on the face.
            assertEquals("$where one control row, no category rail", null,
                activity.findViewById<View>(R.id.swChipScroll))
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            assertTrue("$where real cards render", list.childCount >= 2)

            // The Customize editor stays operable and whole at every text size.
            activity.findViewById<View>(R.id.swQuickEdit).performClick()
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("$where Customize opens the editor", dialog)
            val content = dialog!!.findViewById<ViewGroup>(R.id.swSheetContent)
            val boxes = mutableListOf<CheckBox>()
            val ups = mutableListOf<View>()
            fun walk(v: View) {
                if (v is CheckBox) boxes += v
                if (v.id == R.id.cbQuickUp) ups += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(content)
            assertTrue("$where editor has checkbox rows", boxes.size >= 6)
            for (box in boxes) whole(box, "$where checkbox ${box.text}")
            val checked = boxes.filter { it.isChecked }
            assertTrue("$where saved tools are checked", checked.isNotEmpty())
            assertTrue("$where named up controls exist", ups.isNotEmpty())
            assertTrue("$where the up control is >=48dp", ups.first().height >= 48 * density - 1)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
            Prefs.setLocaleTag(app, "")
            Prefs.resetQuickTools(app)
        }
    }

    @Test(timeout = 30_000) fun `100 percent 360dp English`() = runAt(360, 1.0f, "en")
    @Test(timeout = 30_000) fun `100 percent 360dp Hindi`() = runAt(360, 1.0f, "hi")
    @Test(timeout = 30_000) fun `100 percent 411dp English`() = runAt(411, 1.0f, "en")
    @Test(timeout = 30_000) fun `100 percent 411dp Hindi`() = runAt(411, 1.0f, "hi")
    @Test(timeout = 30_000) fun `130 percent 360dp English`() = runAt(360, 1.3f, "en")
    @Test(timeout = 30_000) fun `130 percent 360dp Hindi`() = runAt(360, 1.3f, "hi")
    @Test(timeout = 30_000) fun `130 percent 411dp English`() = runAt(411, 1.3f, "en")
    @Test(timeout = 30_000) fun `130 percent 411dp Hindi`() = runAt(411, 1.3f, "hi")
    @Test(timeout = 30_000) fun `200 percent 360dp English`() = runAt(360, 2.0f, "en")
    @Test(timeout = 30_000) fun `200 percent 411dp Hindi`() = runAt(411, 2.0f, "hi")

    /**
     * Real-phone finding (build 2a711443, 100 %): the old sort row squeezed "Recent"
     * into a mid-word break. Council §7's one row keeps every sort VALUE on one line and
     * gives the sort-dependent heading its own full-width row. Check all five choices at
     * 100 % in EN and HI on 360dp and on the Infinix-class 411dp width.
     */
    private fun checkSorts(width: Int, lang: String) {
        val langTag = if (lang == "hi") "hi-rIN" else "en-rUS"
        RuntimeEnvironment.setFontScale(1.0f)
        RuntimeEnvironment.setQualifiers("$langTag-w${width}dp-h800dp-xhdpi")
        Prefs.setLocaleTag(app, if (lang == "hi") "hi" else "")
        Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        DocFixtures.saved(ScanRepository(app), app, pages = 1, name = "Sort check")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val dm = activity.resources.displayMetrics
            repeat(3) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            idle()
            val sort = activity.findViewById<TextView>(R.id.swSort)
            val heading = activity.findViewById<TextView>(R.id.cbDocHeading)
            val where = "$lang ${width}dp @1.0"
            for (option in 0 until 5) {
                sort.performClick()
                idle()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("$where option $option opens the sort sheet", sheet)
                val items = sheet!!.findViewById<ViewGroup>(R.id.swSheetItems)
                assertNotNull("$where option $option lists five sorts", items)
                assertEquals("$where the sort sheet still has five choices", 5, items.childCount)
                items.getChildAt(option).performClick()
                idle()
                for ((view, label) in listOf(sort to "sort value", heading to "heading")) {
                    val lay = view.layout ?: continue
                    val text = view.text.toString()
                    val avail = view.width - view.totalPaddingLeft - view.totalPaddingRight
                    assertEquals("$where $label '$text' is one whole line", 1, lay.lineCount)
                    assertEquals("$where $label '$text' is not ellipsized", 0, lay.getEllipsisCount(0))
                    assertTrue("$where $label '$text' fits ${lay.getLineMax(0).toInt()}px in ${avail}px",
                        lay.getLineMax(0) <= avail + 1)
                    // A single laid-out line cannot break mid-word.
                    assertTrue("$where $label '$text' paints on its only line",
                        lay.getLineEnd(0) == text.length)
                }
            }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
            Prefs.setLocaleTag(app, "")
            Prefs.setLibrarySort(app, Prefs.LIB_NEWEST)
        }
    }

    @Test(timeout = 30_000) fun `every sort option is one line at 100 percent 360dp English`() = checkSorts(360, "en")
    @Test(timeout = 30_000) fun `every sort option is one line at 100 percent 360dp Hindi`() = checkSorts(360, "hi")
    @Test(timeout = 30_000) fun `every sort option is one line at 100 percent 411dp English`() = checkSorts(411, "en")
    @Test(timeout = 30_000) fun `every sort option is one line at 100 percent 411dp Hindi`() = checkSorts(411, "hi")
}
