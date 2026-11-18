package com.scanwhirl.app.ui.shell

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
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

/**
 * Owner, on the phone: on Home the shell's cyan-tinted background showed only on the
 * top half; the document list painted an opaque `rv_bg` over it, so the tint looked
 * cut. Tools, Vault and Me leave their page transparent — Home must do the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class HomeBackgroundFixHomeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
        DocFixtures.saved(repo, app, pages = 1, name = "Tint doc")
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

    /** The page of the current tab: the ScrollView's content, or the host's child. */
    private fun page(activity: ShellActivity): View {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        assertNotNull("the tab host is attached", host)
        // The host also holds the small dock fade at the bottom; the page is the scroll.
        val scroll = (0 until host.childCount)
            .map { host.getChildAt(it) }
            .firstOrNull { it is ScrollView }
        assertNotNull("the tab page is in a scroll", scroll)
        val body = scroll as ScrollView
        assertEquals("the page is in the shell's scroll", 1, body.childCount)
        return body.getChildAt(0)
    }

    /**
     * How much of the shell background a view's own background hides: 0 = nothing.
     * A flat fill reports its alpha; a gradient the strongest stop (the shell's own
     * background is such a gradient, so reading only `color` would call it clear).
     */
    private fun opaqueAlpha(view: View): Int {
        val bg = view.background ?: return 0
        return when (bg) {
            is GradientDrawable -> {
                val flat = bg.color
                val stops = bg.colors
                when {
                    flat != null -> Color.alpha(flat.defaultColor)
                    stops != null -> stops.maxOf { Color.alpha(it) }
                    else -> 0
                }
            }
            is ColorDrawable -> Color.alpha(bg.color)
            else -> bg.alpha
        }
    }

    private fun assertClear(what: String, view: View) {
        assertEquals("$what must not paint over the shell background", 0, opaqueAlpha(view))
    }

    @Test
    fun `home leaves the shell background visible behind the library`() {
        launch { activity ->
            val library = page(activity)
            assertTrue("the Home page is the LibraryScreen", library is LibraryScreen)
            assertClear("the Home page", library)
            assertClear("the document list", activity.findViewById(R.id.swDocList))
            assertClear("the list host", activity.findViewById(R.id.swListHost))
            var up: View? = activity.findViewById<View>(R.id.swDocList)
            while (up != null && up !is ScrollView) up = up.parent as? View
            assertNotNull("the Home list sits in the shell's scroll", up)
            assertClear("the Home scroll", up!!)
        }
    }

    @Test
    fun `every tab page leaves the shell background visible`() {
        launch { activity ->
            val nav = listOf(
                "Home" to R.id.swNavLibrary,
                "Tools" to R.id.swNavTools,
                "Vault" to R.id.swNavVault,
                "Me" to R.id.swNavMe
            )
            for ((name, id) in nav) {
                if (id != R.id.swNavLibrary) {
                    activity.findViewById<View>(id).performClick()
                    idle()
                }
                assertClear("the $name page", page(activity))
            }
        }
    }
}
