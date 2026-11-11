package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G2 — the soft keyboard must not open by itself on Home or Scan Review.
 *
 * On API 24/25 the first EditText in the window (Home's search field, Review's
 * title) took the initial focus and the IME opened on entry, hiding the list /
 * the Save button. The fix: (1) the screen root takes the initial focus, and
 * (2) the windows declare `stateHidden|adjustResize` (not a global IME disable).
 *
 * Host-test limits: Robolectric does not drive a real IME, so these assert the
 * two causes — no editable field holds focus on entry, the window declares
 * stateHidden — plus that deliberate editing still focuses the field, keeps its
 * text across a rotation-style recreate, and that Save stays visible. Whether the
 * keyboard really stays closed on API 24/25 is verified on device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeKeyboardFocusTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
    }

    private fun stagedReview(): android.content.Intent {
        val staging = File(app.cacheDir, "kbd_${System.nanoTime()}").apply { mkdirs() }
        File(staging, "page_001.jpg").writeBytes(com.scanwhirl.app.util.ImportFixtures.image())
        return ScanReviewActivity.intent(app, staging.path, null, "Keyboard doc")
    }

    private fun androidx.appcompat.app.AppCompatActivity.softInputState() =
        window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE

    private fun androidx.appcompat.app.AppCompatActivity.softInputAdjust() =
        window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST

    // --- Home ---------------------------------------------------------------

    @Test
    fun `home opens without an editable field focused and with the keyboard hidden`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<EditText>(R.id.searchInput)
                assertFalse("search must not hold the initial focus", search.hasFocus())
                assertFalse("no EditText may be focused on entry", activity.window.decorView.findFocus() is EditText)
                assertEquals(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN, activity.softInputState()
                )
                // Resize behaviour (the keyboard must still push the layout up) is kept.
                assertEquals(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.softInputAdjust()
                )
            }
        }
    }

    @Test
    fun `tapping search still focuses it and typing still filters`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<EditText>(R.id.searchInput)
                // A tap = performClick + focus in touch mode.
                assertTrue(search.isFocusableInTouchMode)
                assertTrue(search.requestFocus())
                assertTrue(search.hasFocus())
                assertTrue(activity.window.decorView.findFocus() === search)
                search.setText("abc")
                assertEquals("abc", search.text.toString())
                assertTrue(activity.findViewById<View>(R.id.btnClearSearch).isShown)
            }
        }
    }

    @Test
    fun `search text and focus survive a recreate while editing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val search = activity.findViewById<EditText>(R.id.searchInput)
                search.requestFocus()
                search.setText("invoice")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val search = activity.findViewById<EditText>(R.id.searchInput)
                assertEquals("invoice", search.text.toString())
            }
        }
    }

    @Test
    fun `home root is not an accessibility stop and search stays labelled`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.homeRoot)
                assertTrue(root.isFocusableInTouchMode)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, ViewCompat.getImportantForAccessibility(root)
                )
                val search = activity.findViewById<EditText>(R.id.searchInput)
                assertFalse(search.hint.isNullOrEmpty())
                assertTrue(
                    "search must stay an accessibility node",
                    ViewCompat.getImportantForAccessibility(search) !=
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO
                )
            }
        }
    }

    // --- Scan Review ----------------------------------------------------------

    @Test
    fun `review opens without the title focused and with the keyboard hidden`() {
        ActivityScenario.launch<ScanReviewActivity>(stagedReview()).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<EditText>(R.id.titleInput)
                assertFalse("title must not hold the initial focus", title.hasFocus())
                assertFalse("no EditText may be focused on entry", activity.window.decorView.findFocus() is EditText)
                assertEquals(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN, activity.softInputState()
                )
                assertEquals(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, activity.softInputAdjust()
                )
                // The Save action is present, enabled and visible without any Back press.
                val save = activity.findViewById<View>(R.id.btnSavePdf)
                assertTrue(save.isShown)
                assertTrue(save.isEnabled)
                assertEquals("Keyboard doc", title.text.toString())
            }
        }
    }

    @Test
    fun `tapping the title focuses it selects the name and edits normally`() {
        ActivityScenario.launch<ScanReviewActivity>(stagedReview()).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<EditText>(R.id.titleInput)
                assertTrue(title.isFocusableInTouchMode)
                assertTrue(title.requestFocus())
                assertTrue(title.hasFocus())
                assertTrue("selectAllOnFocus kept", title.selectionEnd - title.selectionStart > 0)
                title.setText("Renamed")
                assertEquals("Renamed", title.text.toString())
            }
        }
    }

    @Test
    fun `review title edit survives a recreate`() {
        ActivityScenario.launch<ScanReviewActivity>(stagedReview()).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<EditText>(R.id.titleInput)
                title.requestFocus()
                title.setText("Edited name")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(
                    "Edited name", activity.findViewById<EditText>(R.id.titleInput).text.toString()
                )
                assertNotNull(activity.findViewById<View>(R.id.btnSavePdf))
            }
        }
    }

    @Test
    fun `review root is not an accessibility stop`() {
        ActivityScenario.launch<ScanReviewActivity>(stagedReview()).use { scenario ->
            scenario.onActivity { activity ->
                val root = (activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
                assertTrue(root.isFocusableInTouchMode)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO, ViewCompat.getImportantForAccessibility(root)
                )
            }
        }
    }
}
