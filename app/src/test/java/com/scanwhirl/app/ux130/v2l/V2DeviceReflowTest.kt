package com.scanwhirl.app.ux130.v2l

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
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

/**
 * V2L device reflow regression. The Infinix is 1080x2460 at density ~2.625 — 411dp wide —
 * and the 393dp class is the narrow neighbour. Robolectric's default 360dp hid the real
 * failure: "Newest" wrapped into three lines, "All" was clipped and "Library" broke
 * mid-word. These checks run at BOTH device widths, 2.0 font, EN and HI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class V2DeviceReflowTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        LibraryScreen.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        LibraryScreen.searchDebounceMs = 0L
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layout(activity: ShellActivity): View {
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
        return root
    }

    /** No ellipsis, no line wider than its box, and no break inside a single word. */
    private fun assertWholeText(view: TextView, where: String) {
        val laid = view.layout
        assertNotNull("$where was laid out", laid)
        val text = view.text.toString()
        val avail = view.width - view.totalPaddingLeft - view.totalPaddingRight
        for (line in 0 until laid!!.lineCount) {
            assertEquals("$where line $line is ellipsized", 0, laid.getEllipsisCount(line))
            assertTrue(
                "$where line ${laid.getLineMax(line).toInt()}px > ${avail}px",
                laid.getLineMax(line) <= avail + 0.5f
            )
            val start = laid.getLineStart(line)
            if (line > 0 && start in 1 until text.length) {
                assertTrue(
                    "$where breaks mid-word at $start: $text",
                    text[start - 1].isWhitespace() || text[start].isWhitespace()
                )
            }
        }
    }

    private fun run(width: Int, height: Int, lang: String) {
        RuntimeEnvironment.setFontScale(2.0f)
        val langTag = if (lang == "hi") "hi-rIN" else "en-rUS"
        RuntimeEnvironment.setQualifiers("$langTag-w${width}dp-h${height}dp-xhdpi")
        Prefs.setLocaleTag(app, if (lang == "hi") "hi" else "")
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 1, name = "Reflow doc")
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            idle()
            layout(activity)
            val where = "$lang ${width}dp @2.0"
            for (id in intArrayOf(
                R.id.uxL1FolderLabel, R.id.uxL1FilterLabel, R.id.uxL1SortLabel,
                R.id.swSort, R.id.swDocCount, R.id.swAllFolders
            )) {
                val tv = activity.findViewById<View>(id) as? TextView ?: continue
                assertWholeText(tv, "$where control $id")
            }
            // The single sort value must stay one whole line, never New/est/▼.
            assertEquals("$where sort value stays one line", 1,
                activity.findViewById<TextView>(R.id.swSort).layout.lineCount)

            for (id in intArrayOf(R.id.swNavLibrary, R.id.swNavTools, R.id.swNavVault, R.id.swNavMe)) {
                val cell = activity.findViewById<View>(id) as ViewGroup
                val label = cell.findViewWithTag<TextView>("label")
                assertWholeText(label, "$where nav $id")
                assertTrue(
                    "$where single-word nav must stay one line: ${label.text}",
                    label.layout.lineCount == 1
                )
            }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
            Prefs.setLocaleTag(app, "")
        }
    }

    @Test(timeout = 30_000)
    fun `411dp English 200 percent keeps whole controls and nav words`() = run(411, 937, "en")

    @Test(timeout = 30_000)
    fun `411dp Hindi 200 percent keeps whole controls and nav words`() = run(411, 937, "hi")

    @Test(timeout = 30_000)
    fun `393dp English 200 percent keeps whole controls and nav words`() = run(393, 852, "en")

    @Test(timeout = 30_000)
    fun `393dp Hindi 200 percent keeps whole controls and nav words`() = run(393, 852, "hi")
}
