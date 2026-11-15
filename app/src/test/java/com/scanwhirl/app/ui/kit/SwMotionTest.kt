package com.scanwhirl.app.ui.kit

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v2 UI: 74_motion — the kit's motion entry points respect "Remove animations"
 * (animator duration scale 0 -> duration 0), press applies the kit animator,
 * the progress sheet is the kit one and no SwDialog spinner is left in app code.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SwMotionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        android.provider.Settings.Global.putFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }

    private fun setScale(value: Float) {
        android.provider.Settings.Global.putFloat(
            app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            value
        )
    }

    @Test
    fun `motion tokens are 0 when animations are off and scaled when on`() {
        setScale(1f)
        assertTrue(SwMotion.enabled(app))
        assertEquals(SwMotion.MEDIUM, SwMotion.duration(app, SwMotion.MEDIUM))

        setScale(0f)
        assertFalse("Remove animations turns motion off", SwMotion.enabled(app))
        assertEquals(0L, SwMotion.duration(app, SwMotion.LONG))
        assertEquals(0L, SwMotion.duration(app, SwMotion.SHORT))
    }

    @Test
    fun `press applies the kit press animator only while animations are on`() {
        val view = View(app)
        setScale(1f)
        SwMotion.press(view)
        assertTrue("tap target", view.isClickable)
        assertNotNull("the 0.97 press animator", view.stateListAnimator)

        val plain = View(app)
        setScale(0f)
        SwMotion.press(plain)
        assertNotNull("still clickable without motion", plain)
        assertEquals("no scale animation when motion is off", null, plain.stateListAnimator)
    }

    @Test
    fun `tab fade-through swaps immediately when animations are off`() {
        val host = FrameLayout(app)
        val old = TextView(app)
        host.addView(old)
        setScale(0f)
        var swapped = false
        SwMotion.fadeSwitch(host) {
            swapped = true
            host.removeAllViews()
            host.addView(TextView(app))
        }
        assertTrue("the new tab is in place immediately", swapped)
        assertEquals(1, host.childCount)
    }

    @Test
    fun `the progress sheet shows pages and cancel`() {
        var cancelled = false
        setScale(1f)
        val progress = SwProgress.show(app, "Saving", total = 5, onCancel = { cancelled = true })
        progress.setProgress(2, 5)
        val label = progress.view.findViewById<TextView>(R.id.swProgressLabel)
        assertEquals(app.getString(R.string.progress_page_of, 2, 5), label.text.toString())
        val bar = progress.view.findViewById<android.widget.ProgressBar>(R.id.swProgressBar)
        assertFalse("determinate once the count is known", bar.isIndeterminate)
        assertEquals(5, bar.max)
        assertEquals(2, bar.progress)
        progress.view.findViewById<View>(R.id.swProgressCancel).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("cancel is real", cancelled)
        assertFalse(progress.isShowing)
    }

    @Test
    fun `the empty state is brand art, one line and an optional action`() {
        val plain = SwEmptyState(app).bind("Nothing here", "Scans stay on this phone.")
        assertEquals("art, title, body", 3, plain.childCount)

        var acted = false
        val withAction = SwEmptyState(app)
            .bind("No folders yet", null, "New folder") { acted = true }
        assertEquals("art, title, body, action", 4, withAction.childCount)
        val action = withAction.findViewById<View>(R.id.swEmptyAction)
        assertNotNull(action)
        action!!.performClick()
        assertTrue(acted)
    }

    @Test
    fun `no SwDialog progress spinner is left in the app source`() {
        val roots = listOf(File("src/main/java"), File("../app/src/main/java"))
        val src = roots.firstOrNull { it.isDirectory }
        assertNotNull("the app source tree is reachable from the test", src)
        val offenders = src!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("SwDialog.progress") }
            .map { it.name }
            .toList()
        assertTrue("replace every SwDialog.progress with the kit sheet: $offenders", offenders.isEmpty())
    }
}
