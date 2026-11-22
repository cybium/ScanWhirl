package com.scanwhirl.app.ux130.sb

import android.app.Application
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwMotion
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.File

/**
 * Lane SB — design-contest winner 01 "Calm capture" is a layout/state contract, not a
 * screenshot claim. Bounds come from a real Robolectric layout at 100% font on 360dp and
 * 411dp in English and Hindi; the caption fit check repeats at 130%.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class ScanDiscWinnerTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    private fun dp(value: Float): Int = (value * app.resources.displayMetrics.density).toInt()

    private fun measure(
        qualifiers: String,
        fontScale: Float,
        block: (ShellActivity, View, TextView, ImageView, LinearLayout) -> Unit
    ) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(ShellActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val dm = activity.resources.displayMetrics
            repeat(3) {
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                )
                root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val disc = activity.findViewById<View>(R.id.swNavScan)
            val caption = activity.findViewById<TextView>(R.id.uxL1ScanCaption)
            val column = caption.parent as LinearLayout
            val icon = column.getChildAt(0) as ImageView
            block(activity, disc, caption, icon, column)
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
        }
    }

    @Test(timeout = 60_000)
    fun `84dp host gives a 76dp visible disc and a 48dp plus touch target`() {
        measure("en-w360dp-h800dp-xhdpi", 1f) { _, disc, _, _, _ ->
            assertEquals("84dp host width", dp(84f), disc.width)
            assertEquals("84dp host height", dp(84f), disc.height)
            assertTrue("touch target >= 48dp", disc.width >= dp(48f) && disc.height >= dp(48f))
            assertTrue("clickable", disc.isClickable)
            assertTrue("focusable", disc.isFocusable)
            assertTrue("focusable in touch mode", disc.isFocusableInTouchMode)
            assertTrue("state-list background", disc.background is StateListDrawable)

            val rest = File("src/main/res/drawable/bg_scan_disc_rest.xml").readText()
            assertTrue("4dp moat on the left", rest.contains("android:left=\"4dp\""))
            assertTrue("4dp moat on the right", rest.contains("android:right=\"4dp\""))
            assertTrue("cyan 76dp face", rest.contains("@color/sw_scan"))
            assertFalse(
                "no gradient in the disc",
                File("src/main/res/drawable/bg_scan_disc.xml").readText()
                    .contains("gradient", ignoreCase = true)
            )
        }
    }

    @Test(timeout = 120_000)
    fun `icon and caption are one centred stack with a 2dp layout gap at 100 percent`() {
        for (qualifiers in listOf(
            "en-w360dp-h800dp-xhdpi", "hi-w360dp-h800dp-xhdpi",
            "en-w411dp-h800dp-xhdpi", "hi-w411dp-h800dp-xhdpi"
        )) {
            val word = if (qualifiers.startsWith("hi")) "स्कैन" else "Scan"
            measure(qualifiers, 1f) { _, disc, caption, icon, column ->
                assertEquals("$qualifiers caption", word, caption.text.toString())
                assertEquals("$qualifiers 2dp measured gap", dp(2f), caption.top - icon.bottom)
                val centre = column.width / 2f
                assertEquals(
                    "$qualifiers icon centred", centre, (icon.left + icon.right) / 2f, 1f
                )
                assertEquals(
                    "$qualifiers caption centred", centre, (caption.left + caption.right) / 2f, 1f
                )
                assertEquals("$qualifiers 32dp icon", dp(32f), icon.width)
                assertTrue("$qualifiers stack inside 76dp face", column.height <= dp(76f))
                assertTrue("$qualifiers caption inside disc", caption.width <= disc.width)
            }
        }
    }

    @Test(timeout = 120_000)
    fun `caption is not clipped at 130 percent in English or Hindi`() {
        for (qualifiers in listOf(
            "en-w360dp-h800dp-xhdpi", "hi-w360dp-h800dp-xhdpi",
            "en-w411dp-h800dp-xhdpi", "hi-w411dp-h800dp-xhdpi"
        )) {
            measure(qualifiers, 1.3f) { _, disc, caption, icon, column ->
                assertEquals("$qualifiers single line", 1, caption.layout?.lineCount ?: -1)
                assertNull("$qualifiers no ellipsis", caption.ellipsize)
                assertTrue(
                    "$qualifiers caption fits the 76dp face",
                    caption.width <= dp(76f) && caption.bottom <= dp(76f)
                )
                assertTrue("$qualifiers stack fits the 76dp face", column.height <= dp(76f))
                assertEquals("$qualifiers icon is not shrunk", dp(32f), icon.width)
                assertTrue("$qualifiers caption inside disc", caption.width <= disc.width)
            }
        }
    }

    @Test(timeout = 120_000)
    fun `TalkBack hears one Button named for opening Create in both languages`() {
        measure("en-w360dp-h800dp-xhdpi", 1f) { activity, disc, caption, icon, column ->
            assertEquals(
                activity.getString(R.string.scan_capture_desc),
                disc.contentDescription?.toString()
            )
            assertEquals("Scan. Open scan and import options.", disc.contentDescription.toString())
            val info = AccessibilityNodeInfo.obtain()
            disc.onInitializeAccessibilityNodeInfo(info)
            assertEquals(Button::class.java.name, info.className.toString())
            assertTrue("the one node is clickable", info.isClickable)
            assertEquals(
                "caption is silent to TalkBack",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, caption.importantForAccessibility
            )
            assertEquals(
                "icon is silent to TalkBack",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility
            )
            assertEquals(
                "stack is silent to TalkBack",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, column.importantForAccessibility
            )
            assertEquals(
                "English caption 12sp",
                12f, caption.textSize / activity.resources.displayMetrics.scaledDensity, 0.1f
            )
        }
        measure("hi-w360dp-h800dp-xhdpi", 1f) { activity, disc, caption, _, _ ->
            assertEquals(
                "स्कैन। स्कैन और फ़ाइल जोड़ने के विकल्प खोलें।",
                disc.contentDescription.toString()
            )
            assertEquals("स्कैन", caption.text.toString())
            assertEquals(
                "Hindi caption 14sp",
                14f, caption.textSize / activity.resources.displayMetrics.scaledDensity, 0.1f
            )
        }
    }

    @Test(timeout = 60_000)
    fun `state list has pressed and disabled faces and the disc never breathes`() {
        measure("en-w360dp-h800dp-xhdpi", 1f) { _, disc, _, _, _ ->
            val selector = File("src/main/res/drawable/bg_scan_disc.xml").readText()
            assertTrue("disabled state", selector.contains("android:state_enabled=\"false\""))
            assertTrue("pressed state", selector.contains("android:state_pressed=\"true\""))
            assertTrue("focused state", selector.contains("android:state_focused=\"true\""))

            val pressed = File("src/main/res/drawable/bg_scan_disc_pressed.xml").readText()
            assertTrue("immediate pressed face", pressed.contains("@color/rv_accent"))
            val disabled = File("src/main/res/drawable/bg_scan_disc_disabled.xml").readText()
            assertTrue("muted disabled face", disabled.contains("#29363C"))
            val foreground = File("src/main/res/color/scan_disc_foreground.xml").readText()
            assertTrue(
                "disabled glyph and label",
                foreground.contains("android:state_enabled=\"false\"") &&
                    foreground.contains("@color/rv_ink_3")
            )

            assertNull("no ambient animator on the disc", SwMotion.ambientAnimator(disc))
            val shell = File("src/main/java/com/scanwhirl/app/ui/shell/ShellActivity.kt").readText()
            assertFalse("no perpetual breathing in the shell", shell.contains("SwMotion.breathe"))
        }
    }
}
