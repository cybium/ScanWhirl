package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * uihome: the Home library LIST itself.
 *
 * VISUAL_AUDIT_A X2: a document row must prove identity - a real first-page
 * thumbnail at >= 72dp (7% of the screen read as blank white), the name twice
 * at most, and a scrollbar + count so a 16-document library is navigable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryListCraftTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Robolectric: page thumbs decode on the loader's pool; let it land, then idle the post. */
    private fun settle() {
        repeat(4) {
            idle()
            Thread.sleep(120)
        }
        idle()
    }

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                settle()
                block(activity)
            }
        }
    }

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        val scroll = host.getChildAt(0) as ScrollView
        return scroll.getChildAt(0) as LibraryScreen
    }

    private fun scrollOf(activity: ShellActivity): ScrollView {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        return host.getChildAt(0) as ScrollView
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    private fun dp(value: Float): Int = SwStyle.dp(app, value)

    // ---- 1. real paper thumbnails ----------------------------------------

    @Test
    fun `every row carries a real first-page thumbnail at least 72dp wide and 88dp tall`() {
        DocFixtures.saved(repo, app, pages = 2, name = "Studio lease")
        launch { activity ->
            val card = cards(activity).single()
            val frame = card.thumbView.parent as View

            assertTrue(
                "the paper thumbnail must be at least 72dp wide, was ${frame.layoutParams.width}px " +
                    "(72dp = ${dp(72f)}px)",
                frame.layoutParams.width >= dp(72f)
            )
            val ratio = frame.layoutParams.height.toFloat() / frame.layoutParams.width
            assertEquals("the tile keeps the page aspect (1:1.414)", 1.414f, ratio, 0.06f)

            val drawable = card.thumbView.drawable
            assertNotNull("a real page scan must show its decoded first page, not a blank tile", drawable)
            assertTrue(
                "the row thumbnail is the decoded page bitmap",
                drawable is android.graphics.drawable.BitmapDrawable
            )

            card.measure(
                View.MeasureSpec.makeMeasureSpec(dp(360f) - dp(36f), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            assertTrue("a row is at least 88dp tall", card.measuredHeight >= dp(88f))

            assertEquals("the name wraps at most twice", 2, card.nameView.maxLines)
        }
    }

    @Test
    fun `a document without a page image shows its first letter and the page icon, never a blank tile`() {
        DocFixtures.locked(repo, app, password = "1234", pages = 2) // pages unreadable while locked
        launch { activity ->
            val card = cards(activity).single()
            val placeholder = card.thumbView.parent as ViewGroup
            val letter = placeholder.findViewById<TextView>(R.id.swDocThumbLetter)
            val icon = placeholder.findViewById<ImageView>(R.id.swDocThumbPlaceholder)
            assertNotNull("the placeholder letter exists", letter)
            assertTrue("the placeholder letter is shown", letter.isVisible)
            assertEquals("letter", "D", letter.text.toString())
            assertNotNull("the placeholder page icon exists", icon)
            assertTrue("the placeholder page icon is shown", icon.isVisible)
        }
    }

    @Test
    fun `the thumbnail reuses the page thumbnail cache`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Invoice")
        launch { activity ->
            val doc = requireNotNull(repo.getDocument(id))
            val list = activity.findViewById<ViewGroup>(R.id.swDocList)
            val first = list.getChildAt(0) as DocCard
            settle()
            val firstBitmap = (first.thumbView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            assertNotNull("the first row decodes its page", firstBitmap)

            // A second row for the same page must reuse the cached bitmap, not decode again.
            val second = DocCard(activity).apply { bind(doc, "1 page", {}, {}) }
            settle()
            val cached = (second.thumbView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            assertTrue("the same page bitmap is served from the shared cache", firstBitmap === cached)
        }
    }

    // ---- 2. fast scroll + sticky count ------------------------------------

    @Test
    fun `sixteen documents get a draggable fast-scroll bar that scrolls the library`() {
        repeat(16) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Scan $i") }
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertNotNull("a 16-document library needs a visible fast-scroll bar", bar)
            assertTrue("the fast-scroll bar is shown", bar.isShown)
            assertTrue(
                "the bar is a wide-enough drag target",
                bar.layoutParams.width >= dp(12f)
            )
            assertTrue("the fast-scroll bar is enabled", bar.isEnabled)

            val barView = bar as LibraryFastScrollBar
            barView.scrollToFraction(0.5f)
            idle()
            assertTrue(
                "dragging the fast-scroll bar moves the library",
                scrollOf(activity).scrollY > 0
            )
        }
    }

    @Test
    fun `a short library has no fast-scroll bar`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Only one")
        launch { activity ->
            val bar = activity.findViewById<View>(R.id.swLibraryFastScroll)
            assertTrue("two documents do not need a fast-scroll bar", bar == null || !bar.isShown)
        }
    }

    @Test
    fun `the count is sticky and reads sixteen documents`() {
        repeat(16) { i -> DocFixtures.saved(repo, app, pages = 1, name = "Scan $i") }
        launch { activity ->
            val count = activity.findViewById<TextView>(R.id.swDocCount)
            assertNotNull(count)
            assertEquals("16 documents", count.text.toString())

            val scroll = scrollOf(activity)
            val sticky = activity.findViewById<TextView>(R.id.swDocCountSticky)
            assertNotNull("the list has a sticky count", sticky)
            assertTrue("the sticky count is hidden at the top", !sticky.isShown)

            scroll.scrollTo(0, 100_000)
            idle()
            assertTrue("the sticky count shows once the list header scrolls away", sticky.isShown)
            assertEquals("16 documents", sticky.text.toString())
        }
    }

    // ---- 3. calm labels ---------------------------------------------------

    @Test
    fun `home labels are sentence case, never all caps`() {
        DocFixtures.saved(repo, app, pages = 1, name = "Tax return")
        launch { activity ->
            val home = library(activity)
            val shouting = mutableListOf<String>()
            fun visit(view: View) {
                if (view is TextView && view.isShown) {
                    val text = view.text?.toString().orEmpty()
                    if (text.any { it.isLetter() } && text.length > 3 &&
                        text == text.uppercase(Locale.getDefault())
                    ) {
                        shouting.add(text)
                    }
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
            }
            visit(home)
            assertTrue("ALL CAPS labels on Home: $shouting", shouting.isEmpty())
        }
    }

    @Test
    fun `the row names the folder in sentence case`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Bank letter")
        repo.setFolder(listOf(id), "Holidays")
        launch { activity ->
            val card = cards(activity).single()
            val folder = card.findViewById<TextView>(R.id.swDocFolder)
            assertNotNull(folder)
            assertTrue(folder.isVisible)
            assertEquals("Holidays", folder.text.toString())
        }
    }
}
