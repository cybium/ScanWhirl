package com.scanwhirl.app.ux130.l5

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ProgressBar
import android.widget.TextView
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.kit.SwSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * L5-04 — shared visual/accessible foundation.
 *
 * RED rules: the baseline corrections in UX_FIX_PLAN (Button role + icons in SwSheet,
 * determinate SwProgress) already exist and must only be preserved. What is missing is the
 * role token set (body/meta sizes incl. Devanagari padding), a visible functional boundary,
 * a Button role for progress Cancel and an accessible progress state description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class L5KitFoundationTest {

    private fun withActivity(lang: String = "en", scale: Float = 1f, block: (Activity) -> Unit) {
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
        }
    }

    private fun res(activity: Activity) = activity.resources

    private fun source(name: String): String {
        val file = File("src/main/res/values/$name")
        assertTrue("missing $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `L5 role tokens exist with the decided sizes`() = withActivity { activity ->
        val r = res(activity)
        fun dimen(name: String) = r.getIdentifier(name, "dimen", activity.packageName)
        val body = dimen("ux_l5_text_body")
        val meta = dimen("ux_l5_text_meta")
        val metaHi = dimen("ux_l5_text_meta_hi")
        val proLabel = dimen("ux_l5_text_pro_label")
        assertTrue("body 16sp token", body != 0)
        assertTrue("metadata 12sp token", meta != 0)
        assertTrue("Hindi metadata 14sp token", metaHi != 0)
        assertTrue("scalable Pro label 13.5sp token", proLabel != 0)
        assertEquals(16f, r.getDimension(body) / r.displayMetrics.scaledDensity, 0.05f)
        assertEquals(12f, r.getDimension(meta) / r.displayMetrics.scaledDensity, 0.05f)
        assertEquals(14f, r.getDimension(metaHi) / r.displayMetrics.scaledDensity, 0.05f)
        assertEquals(13.5f, r.getDimension(proLabel) / r.displayMetrics.scaledDensity, 0.05f)
        assertTrue(
            "functional boundary token >= 3:1 vs ink",
            r.getIdentifier("sw_boundary_functional", "color", activity.packageName) != 0
        )
    }

    @Test
    fun `metadata text raises the floor and enables Devanagari font padding`() {
        val method = com.scanwhirl.app.ui.kit.SwStyle::class.java.methods.firstOrNull {
            it.name == "text" && it.parameterCount == 6 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[5] == java.lang.Boolean.TYPE
        }
        assertNotNull("SwStyle.text metadata role overload (RED on base)", method)
        for ((lang, floor) in listOf("en" to 12f, "hi" to 14f)) {
            withActivity(lang) { activity ->
                @Suppress("UNCHECKED_CAST")
                val tv = method!!.invoke(
                    com.scanwhirl.app.ui.kit.SwStyle, activity, 10f,
                    com.scanwhirl.app.ui.kit.SwStyle.color(activity, R.color.text_hologram_mid),
                    false, 0f, true
                ) as TextView
                assertTrue(
                    "$lang metadata floor is ${floor}sp, got ${tv.textSize / activity.resources.displayMetrics.scaledDensity}sp",
                    tv.textSize / activity.resources.displayMetrics.scaledDensity >= floor - 0.05f
                )
                assertEquals(
                    "$lang metadata keeps matras clear of their base letters",
                    lang == "hi", tv.includeFontPadding
                )
            }
        }
    }

    @Test
    fun `progress cancel exposes a Button role and focus`() = withActivity { activity ->
        var cancelled = 0
        val progress = SwProgress.show(activity, "Working", onCancel = { cancelled++ })
        try {
            val cancel = progress.view.findViewById<View>(R.id.swProgressCancel)
            assertNotNull(cancel)
            assertTrue("Cancel is a focus stop", cancel!!.isFocusable)
            val info = AccessibilityNodeInfo.obtain()
            cancel.onInitializeAccessibilityNodeInfo(info)
            assertEquals(
                "TalkBack names a Button, not an unlabelled TextView",
                android.widget.Button::class.java.name, info.className.toString()
            )
            cancel.performClick()
            assertEquals(1, cancelled)
        } finally {
            progress.dismiss()
        }
    }

    @Test
    fun `determinate progress exposes its state to accessibility`() = withActivity { activity ->
        val progress = SwProgress.show(activity, "Working", total = 5)
        try {
            progress.setProgress(2, 5, "Page 2 of 5")
            val bar = progress.view.findViewById<ProgressBar>(R.id.swProgressBar)
            val info = AccessibilityNodeInfo.obtain()
            bar.onInitializeAccessibilityNodeInfo(info)
            assertEquals("state matches the visible page text", "Page 2 of 5", info.stateDescription?.toString())
            assertNotNull("range state is exposed", info.rangeInfo)
            assertEquals(2f, info.rangeInfo.current, 0.01f)
        } finally {
            progress.dismiss()
        }
    }

    @Test
    fun `preserved sheet buttons keep Button role and icon overloads`() = withActivity { activity ->
        val sheet = SwSheet.builder(activity).setPrimary("Continue").setSecondary("Cancel").build()
        for (button in listOf(sheet.primaryButton, sheet.secondaryButton)) {
            val info = AccessibilityNodeInfo.obtain()
            button.onInitializeAccessibilityNodeInfo(info)
            assertEquals(android.widget.Button::class.java.name, info.className.toString())
        }
        val method = SwSheet::class.java.methods.firstOrNull {
            it.name == "setPrimary" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == CharSequence::class.java
        }
        assertNotNull("decorative icon overload still present", method)
        method!!.invoke(sheet, "Compress", R.drawable.ic_compress, null)
        assertNotNull(sheet.primaryButton.compoundDrawablesRelative[0])
        val progress = SwProgress.show(activity, "Working")
        progress.setProgress(1, 4)
        progress.dismiss()
    }

    @Test
    fun `theme uses calm working accent and visible boundaries`() {
        val themes = source("themes.xml")
        assertTrue(
            "primary action uses sw_scan, not the capture-only #00F0FF",
            Regex("""<item name="backgroundTint">@color/sw_scan</item>""").containsMatchIn(themes)
        )
        assertTrue(
            "secondary button boundary is a functional 3:1 token",
            Regex("""<item name="strokeColor">@color/(sw_boundary_functional|rv_ink_3)</item>""")
                .containsMatchIn(themes)
        )
        val dock = themes.substringAfter("Widget.ScanWhirl.Button.Dock\"").substringBefore("</style>")
        assertTrue(
            "dock metadata is at least 12sp (was 11sp)",
            Regex("""<item name="android:textSize">1[246]sp</item>""").containsMatchIn(dock)
        )
        val colors = source("colors.xml")
        // W5a: the boundary is a semantic token alias, not a literal. values/ (light)
        // and values-night/ (dark) supply the theme-aware value.
        assertTrue("functional boundary resolves through tok_boundary",
            colors.contains("<color name=\"sw_boundary_functional\">@color/tok_boundary</color>"))
        val dimens = source("dimens_ux130.xml")
        assertTrue("body token is 16sp", dimens.contains("ux_l5_text_body\">16sp"))
        assertTrue("Hindi meta token is 14sp", dimens.contains("ux_l5_text_meta_hi\">14sp"))
        val fonts = source("styles_fonts.xml")
        assertTrue("L5 body style uses the token", fonts.contains("TextAppearance.ScanWhirl.L5.Body") &&
            fonts.contains("@dimen/ux_l5_text_body"))
        assertTrue("L5 Hindi meta style uses the token", fonts.contains("TextAppearance.ScanWhirl.L5.MetaHi") &&
            fonts.contains("@dimen/ux_l5_text_meta_hi"))
    }
}
