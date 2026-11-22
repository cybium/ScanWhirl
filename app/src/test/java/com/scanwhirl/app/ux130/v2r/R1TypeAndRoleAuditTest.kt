package com.scanwhirl.app.ux130.v2r

import android.app.Activity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwChip
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** V2R R1: finish the 12sp / state / focus / contrast audit for the kit components we own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1TypeAndRoleAuditTest {

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun source(name: String): String {
        val file = File("src/main/res/values/$name")
        assertTrue("missing $file", file.isFile)
        return file.readText()
    }

    private fun styleBlock(xml: String, name: String): String {
        val start = xml.indexOf("name=\"$name\"")
        assertTrue("$name style is present", start >= 0)
        return xml.substring(start).substringBefore("</style>")
    }

    @Test
    fun `HUD label and section styles use the 12sp floor`() {
        val xml = source("styles_fonts.xml")
        for (name in listOf("TextAppearance.SW.Hud.Label", "TextAppearance.SW.Section")) {
            val block = styleBlock(xml, name)
            val match = Regex("""<item name="android:textSize">([0-9.]+)sp</item>""").find(block)
            assertNotNull("$name declares a text size", match)
            val sp = match!!.groupValues[1].toFloat()
            assertTrue("$name is ${sp}sp; the readable floor is 12sp (RED on base: 11sp)", sp >= 11.99f)
        }
    }

    @Test
    fun `chip PRO pill is at least 12sp while the chip keeps its 48dp target`() {
        val activity = activity()
        val chip = SwChip(activity).bind(null, "Split").withProTag()
        assertTrue("chip touch target is 48dp", chip.minimumHeight >= SwStyle.dp(activity, 48f))
        val pill = chip.getChildAt(chip.childCount - 1) as TextView
        val sp = pill.textSize / activity.resources.displayMetrics.scaledDensity
        assertTrue(
            "PRO pill is ${sp}sp; the readable floor is 12sp (RED on base: 8sp)",
            sp >= 11.99f
        )
    }

    @Test
    fun `chip exposes a Button role and a focus stop`() {
        val activity = activity()
        val chip = SwChip(activity).bind(null, "Split")
        assertTrue("chip is focusable", chip.isFocusable)
        val info = AccessibilityNodeInfo.obtain()
        chip.onInitializeAccessibilityNodeInfo(info)
        assertEquals(
            "TalkBack reads the chip as a Button",
            android.widget.Button::class.java.name, info.className.toString()
        )
    }

    @Test
    fun `touched functional boundaries and text clear their contrast bars`() {
        val activity = activity()
        fun luminance(argb: Int): Double {
            val c = intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
                .map { v -> (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) } }
            return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        }
        fun ratio(a: Int, b: Int): Double {
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }
        val bg = activity.getColor(R.color.rv_bg)
        assertTrue(
            "functional boundary >= 3:1",
            ratio(activity.getColor(R.color.sw_boundary_functional), bg) >= 3.0
        )
        assertTrue(
            "body text >= 4.5:1",
            ratio(activity.getColor(R.color.text_hologram_mid), bg) >= 4.5
        )
    }

    @Test
    fun `preserved sheet and progress roles still work`() {
        val activity = activity()
        val sheet = SwSheet.builder(activity).setPrimary("Continue").setSecondary("Cancel").build()
        for (button in listOf(sheet.primaryButton, sheet.secondaryButton)) {
            val info = AccessibilityNodeInfo.obtain()
            button.onInitializeAccessibilityNodeInfo(info)
            assertEquals(android.widget.Button::class.java.name, info.className.toString())
        }
        val progress = SwProgress.show(activity, "Working", total = 3, onCancel = {})
        try {
            val cancel = progress.view.findViewById<View>(R.id.swProgressCancel)
            val info = AccessibilityNodeInfo.obtain()
            cancel.onInitializeAccessibilityNodeInfo(info)
            assertEquals(android.widget.Button::class.java.name, info.className.toString())
            progress.setProgress(1, 3, "Page 1 of 3")
            val barInfo = AccessibilityNodeInfo.obtain()
            progress.view.findViewById<View>(R.id.swProgressBar).onInitializeAccessibilityNodeInfo(barInfo)
            assertEquals("Page 1 of 3", barInfo.stateDescription?.toString())
        } finally {
            progress.dismiss()
        }
    }
}
