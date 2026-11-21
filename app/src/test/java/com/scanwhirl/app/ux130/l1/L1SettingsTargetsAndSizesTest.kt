package com.scanwhirl.app.ux130.l1

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Switch
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.SettingsActivity
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.SizeUnits
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L5-03 + the shell's remaining binary size labels.
 *
 * RED on base: only the searchable-PDF switch was pinned to 48dp; the greeting and
 * Block screenshots switches were wrap_content, and ShellActivity formatted KB/MB
 * with binary divisors.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class L1SettingsTargetsAndSizesTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        RuntimeEnvironment.setFontScale(1.0f)
        Prefs.setShowGreeting(app, true)
        Prefs.setBlockDocScreenshots(app, false)
    }

    private fun measure(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val dm = activity.resources.displayMetrics
            repeat(2) {
                val decor = activity.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                decor.layout(0, 0, dm.widthPixels, dm.heightPixels)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val target = SwStyle.dp(activity, 48f)
            for (id in intArrayOf(R.id.switchGreeting, R.id.switchBlockScreenshots)) {
                val toggle = activity.findViewById<View>(id)
                assertNotNull("switch $id exists", toggle)
                assertTrue(
                    "at ${scale}x switch $id has a ${target}px minimum, got ${toggle!!.minimumHeight}px",
                    toggle.minimumHeight >= target
                )
                if (toggle.height > 0) {
                    assertTrue(
                        "at ${scale}x switch $id is ${toggle.height}px, needs ${target}px",
                        toggle.height >= target
                    )
                }
                assertTrue("switch $id is a focus stop", toggle.isFocusable)
                assertFalse("switch $id has a name", toggle.contentDescription.isNullOrBlank())
            }
            // One coherent stop: the switch itself is the single toggle for its row.
            val before = Prefs.blockDocScreenshots(app)
            activity.findViewById<View>(R.id.switchBlockScreenshots).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(!before, Prefs.blockDocScreenshots(app))
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    @Test(timeout = 30_000)
    fun `the greeting and screenshot switches are full targets at 100 percent`() = measure(1.0f)

    @Test(timeout = 30_000)
    fun `the greeting and screenshot switches are full targets at 200 percent`() = measure(2.0f)

    @Test(timeout = 30_000)
    fun `a switch choice persists across recreation`() {
        val first = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            first.get().findViewById<View>(R.id.switchGreeting).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("greeting toggled", Prefs.showGreeting(app))
        } finally {
            first.pause().stop().destroy()
        }
        val second = Robolectric.buildActivity(SettingsActivity::class.java).setup(Bundle())
        try {
            assertFalse("persisted", Prefs.showGreeting(app))
            assertFalse(
                second.get().findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchGreeting)?.isChecked
                    ?: true
            )
        } finally {
            second.pause().stop().destroy()
        }
        Prefs.setShowGreeting(app, true)
    }

    @Test(timeout = 30_000)
    fun `the shell formats sizes with the shared decimal formatter`() {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Size label doc")
        val doc = repo.getDocument(id)!!
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val method = ShellActivity::class.java.getDeclaredMethod(
                "sizeLabel", com.scanwhirl.app.model.ScanDocument::class.java
            )
            method.isAccessible = true
            val bytes = if (doc.pdfFile.exists()) doc.pdfFile.length() else doc.pageFiles.sumOf { it.length() }
            val shown = method.invoke(controller.get(), doc) as String
            assertEquals(SizeUnits.format(bytes), shown)
            val src = java.io.File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
            assertTrue("no binary MB divisor", !src.contains("bytes / 1048576f"))
            assertTrue("no binary KB divisor", !src.contains("bytes / 1024} KB"))
            assertTrue("decimal formatter used", src.contains("SizeUnits.format(bytes)"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
