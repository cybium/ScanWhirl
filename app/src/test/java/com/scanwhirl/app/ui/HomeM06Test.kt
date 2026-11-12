package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.AppBarLayout
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.home.HomeReveal
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * M06 P1 Home: on a phone at 200% text (411x731 dp here; 1080x2400 checked on the API 37 emulator), the header scrolls away
 * with the list (and comes back), while the labelled Scan/Import dock stays put.
 * At 100% nothing changes. A just-saved document is scrolled into view without
 * touching the user's search or filter. A filter that hides it is reported, not
 * silently cleared. The "no match" message sits under the header, not under
 * the keyboard.
 *
 * Host-test limit: Robolectric proves the flags, geometry and flow, not the look.
 * The real 200% appearance is checked on the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h731dp-xxhdpi")
class HomeM06Test {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        Prefs.setShowGreeting(app, false)
        RuntimeEnvironment.setFontScale(1.0f)
        HomeReveal.take()
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        HomeReveal.take()
    }

    private fun seed(name: String): String = runBlocking {
        val uri = Uri.parse("content://m06home/p_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        repo.saveScan(listOf(uri), null, name).id
    }

    private fun pollUntil(timeoutMs: Long = 10000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(40)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun flagsOf(a: MainActivity): Int {
        val bar = a.findViewById<AppBarLayout>(R.id.homeAppBar)
        return (bar.getChildAt(0).layoutParams as AppBarLayout.LayoutParams).scrollFlags
    }

    private fun rv(a: MainActivity) = a.findViewById<RecyclerView>(R.id.scanList)

    @Test
    fun `header stays fixed at 100 percent`() {
        repeat(6) { seed("Doc $it") }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> a.searchDebounceMs = 0 }
            var flags = -1
            assertTrue(pollUntil {
                s.onActivity { a -> if (rv(a).adapter!!.itemCount == 6) flags = flagsOf(a) }
                flags == 0
            })
        }
    }

    @Test
    fun `header scrolls away at 200 percent and the dock stays`() {
        repeat(6) { seed("Quarterly tax return and supporting receipts $it") }
        RuntimeEnvironment.setFontScale(2.0f)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> a.searchDebounceMs = 0 }
            var flags = 0
            var dims = ""
            val collapsed = pollUntil {
                    s.onActivity { a ->
                        flags = flagsOf(a)
                        dims = "header=${a.findViewById<View>(R.id.homeAppBar).height} " +
                            "area=${a.findViewById<View>(R.id.homeContent).height} " +
                            "root=${a.window.decorView.height} fs=${a.resources.configuration.fontScale}"
                    }
                    flags and AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL != 0
                }
            assertTrue("200% header must become collapsible ($dims)", collapsed)
            assertTrue("comes back on any upward scroll", flags and AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS != 0)
            s.onActivity { a ->
                val bar = a.findViewById<AppBarLayout>(R.id.homeAppBar)
                bar.setExpanded(false, false)
                a.findViewById<View>(R.id.homeContent).requestLayout()
            }
            pollUntil { true }
            s.onActivity { a ->
                val bar = a.findViewById<AppBarLayout>(R.id.homeAppBar)
                assertTrue("collapsed header is off-screen (bottom=${bar.bottom})", bar.bottom <= 1)
                for (id in intArrayOf(R.id.btnScan, R.id.btnImport, R.id.scanLabel)) {
                    assertTrue("dock $id still shown", a.findViewById<View>(id).isShown)
                }
                bar.setExpanded(true, false)
            }
            pollUntil { true }
            s.onActivity { a ->
                assertTrue(a.findViewById<AppBarLayout>(R.id.homeAppBar).top >= 0)
                assertTrue(a.findViewById<EditText>(R.id.searchInput).isShown)
            }
        }
    }

    @Test
    fun `a just saved document is scrolled into view`() {
        repeat(24) { seed("Older $it") }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> a.searchDebounceMs = 0 }
            assertTrue(pollUntil { var n = 0; s.onActivity { a -> n = rv(a).adapter!!.itemCount }; n == 24 })
            s.onActivity { a -> rv(a).scrollToPosition(23) }
            assertTrue(pollUntil {
                var first = 0
                s.onActivity { a -> first = (rv(a).layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() }
                first > 0
            })
            val id = seed("ZEPHYR new scan")
            HomeReveal.request(id)
            s.onActivity { a -> a.refreshList() }
            assertTrue(
                "the new document's row is scrolled into view",
                pollUntil {
                    var ok = false
                    s.onActivity { a ->
                        val lm = rv(a).layoutManager as LinearLayoutManager
                        val ad = rv(a).adapter as ScanListAdapter
                        val at = ad.currentList.indexOfFirst { it.id == id }
                        ok = at >= 0 && at in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()
                    }
                    ok
                }
            )
            s.onActivity { a -> assertEquals(25, rv(a).adapter!!.itemCount) }
        }
    }

    @Test
    fun `a filter that hides the new document is reported and kept until Show all`() {
        repeat(3) { seed("Bill $it") }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> a.searchDebounceMs = 0 }
            assertTrue(pollUntil { var n = 0; s.onActivity { a -> n = rv(a).adapter!!.itemCount }; n == 3 })
            s.onActivity { a -> a.findViewById<EditText>(R.id.searchInput).setText("bill") }
            assertTrue(pollUntil { var n = -1; s.onActivity { a -> n = rv(a).adapter!!.itemCount }; n == 3 })

            val id = seed("Passport")
            HomeReveal.request(id)
            s.onActivity { a -> a.refreshList() }
            var snack: TextView? = null
            assertTrue(
                "the hidden new document is reported",
                pollUntil {
                    s.onActivity { a ->
                        snack = a.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                            ?.takeIf { it.text.contains("hides it") }
                    }
                    snack != null
                }
            )
            s.onActivity { a ->
                assertEquals("the search is not cleared behind the user's back", "bill",
                    a.findViewById<EditText>(R.id.searchInput).text.toString())
                a.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            assertTrue(pollUntil {
                var ok = false
                s.onActivity { a ->
                    val ad = rv(a).adapter as ScanListAdapter
                    ok = a.findViewById<EditText>(R.id.searchInput).text.isEmpty() &&
                        ad.currentList.any { it.id == id }
                }
                ok
            })
        }
    }

    @Test
    fun `no match message sits at the top of the list area`() {
        repeat(2) { seed("Receipt $it") }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a -> a.searchDebounceMs = 0 }
            assertTrue(pollUntil { var n = 0; s.onActivity { a -> n = rv(a).adapter!!.itemCount }; n == 2 })
            s.onActivity { a -> a.findViewById<EditText>(R.id.searchInput).setText("qqqqzzzz") }
            assertTrue(pollUntil {
                var shown = false
                s.onActivity { a -> shown = a.findViewById<View>(R.id.emptyState).visibility == View.VISIBLE }
                shown
            })
            s.onActivity { a ->
                val content = a.findViewById<LinearLayout>(R.id.emptyContent)
                assertEquals(Gravity.TOP, content.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                assertEquals("No documents match your search", a.findViewById<TextView>(R.id.emptyTitle).text.toString())
            }
        }
    }
}
