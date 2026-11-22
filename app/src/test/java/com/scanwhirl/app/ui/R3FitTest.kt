package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class R3FitTest(private val language: String, private val widthDp: Int, private val scale: Float) {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, {1}dp, font {2}")
        fun configurations(): List<Array<Any>> = listOf("en", "hi").flatMap { language ->
            listOf(320, 411).flatMap { width ->
                listOf(1f, 2f).map { scale -> arrayOf<Any>(language, width, scale) }
            }
        }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo: ScanRepository get() = ScanWhirlApp.from(app).repository

    @After
    fun reset() {
        CompressFlow.estimateForTest = null
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("")
    }

    private fun configure() {
        RuntimeEnvironment.setQualifiers("$language-w${widthDp}dp-h640dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
    }

    @Test
    fun compressControlsFitAndCanBeReached() {
        configure()
        CompressFlow.estimateForTest = { _, _ -> 120_000L }
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional electricity bill November")
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(language, activity.resources.configuration.locales[0].language)
                assertEquals(scale, activity.resources.configuration.fontScale, 0.01f)
                CompressFlow(activity, repo, activity, {}, { repo.listDocuments() })
                    .startWith(requireNotNull(repo.getDocument(id)))
                val dialog = requireNotNull(ShadowDialog.getLatestDialog())
                try {
                    val root = dialog.findViewById<ViewGroup>(R.id.swSheetRoot)!!
                    val levels = root.findViewById<RadioGroup>(R.id.levelGroup)
                    waitFor("all four measured labels") {
                        (0 until levels.childCount).all {
                            (levels.getChildAt(it) as TextView).text.contains("KB")
                        }
                    }
                    val primary = root.findViewById<View>(R.id.swSheetPrimary)
                    // SH2: the compress sheet is non-destructive; the X is the only dismissal.
                    // OLD: the hidden swSheetSecondary was measured as a reachable Cancel.
                    // NEW: require it hidden and measure the X instead.
                    assertEquals(
                        "no duplicate Cancel footer",
                        View.GONE,
                        root.findViewById<View>(R.id.swSheetSecondary).visibility
                    )
                    val close = root.findViewById<View>(R.id.swSheetClose)
                    val quality = root.findViewById<View>(R.id.compressModeQuality)
                    val target = root.findViewById<View>(R.id.compressModeTarget)
                    layout(root, 600)
                    listOf(primary, close, quality, target).forEach { assertReachable(root, it) }
                    (0 until levels.childCount).forEach { assertReachable(root, levels.getChildAt(it)) }

                    target.performClick()
                    layout(root, 600)
                    assertReachable(root, root.findViewById(R.id.editCompressTarget))
                    assertReachable(root, primary)
                    assertEquals(
                        "no duplicate Cancel footer after choosing the size aim",
                        View.GONE,
                        root.findViewById<View>(R.id.swSheetSecondary).visibility
                    )
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }

    @Test
    fun trashCountIsFullyVisible() {
        configure()
        repeat(3) { index ->
            val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional receipt $index")
            requireNotNull(repo.moveToTrash(id))
        }
        ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(language, activity.resources.configuration.locales[0].language)
                assertEquals(scale, activity.resources.configuration.fontScale, 0.01f)
                val root = activity.findViewById<ViewGroup>(R.id.trashRoot)
                val label = activity.resources.getQuantityString(R.plurals.trash_count, 3, 3)
                waitFor("the count for three trashed documents") {
                    descendants(root).filterIsInstance<TextView>().any { it.text.toString() == label }
                }
                layout(root, 640, exactHeight = true)
                val count = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == label }
                assertUnclipped(root, count)
                val textLayout = requireNotNull(count.layout)
                assertTrue(
                    "the entire count line fits: ${textLayout.height}px text, ${count.height}px view",
                    textLayout.height + count.compoundPaddingTop + count.compoundPaddingBottom <= count.height
                )
                assertEquals(label.length, textLayout.getLineEnd(textLayout.lineCount - 1))
                assertTrue(
                    "no count text is ellipsized",
                    (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 }
                )
            }
        }
    }

    private fun waitFor(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ready()) return
            Thread.sleep(10)
        }
        assertTrue(label, ready())
    }

    private fun layout(root: View, heightDp: Int, exactHeight: Boolean = false) {
        val width = px(root, widthDp)
        val height = px(root, heightDp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                height, if (exactHeight) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            )
        )
        root.layout(0, 0, width, root.measuredHeight)
    }

    private fun px(view: View, dp: Int) = (dp * view.resources.displayMetrics.density).toInt()

    private fun assertReachable(root: ViewGroup, view: View) {
        val label = label(view)
        assertTrue("$label is at least 48dp high (was ${view.height}px)", view.height >= px(view, 48))
        assertTrue("$label is at least 48dp wide", view.width >= px(view, 48))
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        assertUnclipped(root, view)
    }

    private fun assertUnclipped(root: ViewGroup, view: View) {
        val rect = Rect(0, 0, view.width, view.height)
        var child = view
        assertTrue("${label(view)} was laid out ($rect)", !rect.isEmpty)
        while (child !== root) {
            val parent = child.parent as ViewGroup
            rect.offset(child.left - parent.scrollX, child.top - parent.scrollY)
            assertTrue(
                "${label(view)} fits inside ${parent.javaClass.simpleName}: " +
                    "$rect vs ${parent.width}x${parent.height}",
                Rect(0, 0, parent.width, parent.height).contains(rect)
            )
            child = parent
        }
    }

    private fun label(view: View) = (view as? TextView)?.text ?: view.contentDescription

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
        }
    }
}
