package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX-008 regression guard for the SHORT phone (360x640dp), English and Hindi, normal text size.
 *
 * The 800dp variant in [LibraryFirstScreenTest] proves a document row fits with room to spare.
 * This one exists because a "compact" Quick tools strip that only fits a tall phone is not
 * compact: at 640dp the first real document card must still end ABOVE the floating dock. The
 * Quick tools block is one horizontal row, so this stays true while the editor still holds 12.
 */
abstract class LibraryFirstScreen640Base {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
        val repo = ScanRepository(app)
        DocFixtures.saved(repo, app, pages = 2, name = "Rent receipt October")
        DocFixtures.saved(repo, app, pages = 6, name = "Physics notes chapter 4")
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.resetQuickTools(app)
        ProGate.setTestOverride(app, null)
        Prefs.setLocaleTag(app, "")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun layoutRoot(activity: ShellActivity): View {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val dm = activity.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        repeat(3) {
            root.measure(w, h)
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return root
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

    @Test
    fun `a real document is fully above the dock on a 360 by 640dp phone`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val root = layoutRoot(activity)
                val list = activity.findViewById<ViewGroup>(R.id.swDocList)
                assertTrue("the seeded library renders rows", list.childCount > 0)
                val card = list.getChildAt(0)
                assertTrue("the first row is a document card", card is DocCard)
                val dockTop = minOf(
                    topIn(activity.findViewById<View>(R.id.swNavScan), root),
                    topIn(activity.findViewById<View>(R.id.swShellNav), root)
                )
                val cardTop = topIn(card, root)
                val cardBottom = cardTop + card.height
                val outer = activity.findViewById<ViewGroup>(R.id.swTabContent).getChildAt(0)
                val screen = (outer as? ViewGroup)?.getChildAt(0) ?: outer
                val sections = (screen as? ViewGroup)?.let { parent ->
                    (0 until parent.childCount).joinToString(", ") { i ->
                        val v = parent.getChildAt(i)
                        "${v.javaClass.simpleName}#${v.id}:h${v.height}"
                    }
                } ?: "no screen"
                assertTrue(
                    "360x640dp: first DocCard bottom $cardBottom must be <= dock top $dockTop " +
                        "(top=$cardTop). Sections: $sections",
                    cardBottom <= dockTop
                )
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LibraryFirstScreen640EnTest : LibraryFirstScreen640Base()

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
class LibraryFirstScreen640HiTest : LibraryFirstScreen640Base()
