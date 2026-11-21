package com.scanwhirl.app.ui.kit

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.CompressFlow
import com.scanwhirl.app.ui.DocumentViewerActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the real kit root against the available window, including a resized IME viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetFooterU3Test {
    private fun withActivity(lang: String = "en", scale: Float = 2f, block: (AppCompatActivity) -> Unit) {
        RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_ScanWhirl)
        controller.setup()
        try { block(activity) } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun layout(root: View, width: Int, height: Int) {
        val d = root.resources.displayMetrics.density
        val w = (width * d).toInt()
        val h = (height * d).toInt()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, w, root.measuredHeight)
        }
        assertTrue("sheet fits available window $height dp", root.height <= h)
    }

    private fun visibleFooter(sheet: SwSheet) {
        for (button in listOf(sheet.primaryButton, sheet.secondaryButton)) {
            val rect = android.graphics.Rect()
            button.getDrawingRect(rect)
            (sheet.view as ViewGroup).offsetDescendantRectToMyCoords(button, rect)
            assertTrue("footer top is inside sheet: $rect", rect.top >= 0)
            assertTrue("footer bottom is inside window: $rect / ${sheet.view.height}",
                rect.bottom <= sheet.view.height - sheet.view.paddingBottom)
            assertTrue("48dp target", button.height >= SwStyle.dp(button.context, 48f))
            assertTrue("all label lines fit", button.layout.height <=
                button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
        }
    }

    @Test fun compressFooterVisibleEnHiAtBothWidthsAndWithIme() {
        for (lang in listOf("en", "hi")) withActivity(lang) { activity ->
            val content = LayoutInflater.from(activity).inflate(R.layout.dialog_compress, null) as NestedScrollView
            content.findViewById<TextView>(R.id.compressSourceName).text = "Fictional application pages"
            content.findViewById<TextView>(R.id.compressSourceMeta).text = "3 · 336 KB"
            val group = content.findViewById<LinearLayout>(R.id.levelGroup)
            for (res in listOf(R.string.compress_level_low, R.string.compress_level_medium,
                R.string.compress_level_high, R.string.compress_level_ultra)) {
                group.addView(RadioButton(activity).apply {
                    text = activity.getString(R.string.compress_est_pending, activity.getString(res))
                    minHeight = SwStyle.dp(activity, 48f)
                })
            }
            val sheet = SwSheet.builder(activity).setTitle(R.string.compress_level_title)
                .setScrollableContent(content).setPrimary(R.string.compress_action)
                .setSecondary(android.R.string.cancel).build().show()
            try {
                for (width in listOf(360, 411)) for (height in listOf(752, 420)) {
                    layout(sheet.view, width, height)
                    visibleFooter(sheet)
                    if (height == 420) {
                        assertTrue("overflowing options scroll above fixed footer", content.canScrollVertically(1))
                    }
                    content.scrollTo(0, 100000)
                    layout(sheet.view, width, height)
                    visibleFooter(sheet)
                    content.scrollTo(0, 0)
                }
            } finally { sheet.dismiss() }
        }
    }

    @Test fun genericTallSheetKeepsFooterOutsideScroll() = withActivity { activity ->
        val sheet = SwSheet.builder(activity).setTitle("Tall form")
            .setContent(TextView(activity).apply { text = ("Tall content\n").repeat(80) })
            .setPrimary("Continue").setSecondary("Cancel").build().show()
        try {
            layout(sheet.view, 360, 420)
            visibleFooter(sheet)
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                sheet.window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        } finally { sheet.dismiss() }
    }

    @Test fun secondaryWrapsLongHindiWithoutClipping() = withActivity("hi") { activity ->
        val sheet = SwSheet.builder(activity).setPrimary("आगे बढ़ें")
            .setSecondary("पासवर्ड हटाए बिना वापस दस्तावेज़ पर जाएँ").build()
        layout(sheet.view, 360, 752)
        visibleFooter(sheet)
        assertTrue("long secondary wraps", sheet.secondaryButton.lineCount > 1)
    }

    @Test fun manyOptionsUseRemainingViewportRatherThanFixedScreenFraction() = withActivity("hi") { activity ->
        val sheet = SwSheet.builder(activity).setTitle("विकल्प")
            .setOptions(List(30) { "विकल्प $it" }) { _, _ -> }
            .setPrimary("आगे बढ़ें").setSecondary("रद्द करें").build()
        layout(sheet.view, 360, 320)
        visibleFooter(sheet)
        assertTrue(sheet.contentContainer.findViewById<View>(R.id.swSheetItems).height > 0)
    }

    @Test fun lockedDrawingSheetDoesNotInterceptStrokes() = withActivity { activity ->
        val sheet = SwSheet.builder(activity).setContent(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(-1, SwStyle.dp(activity, 1000f))
        }).setPrimary("Done").build().lockDrag()
        layout(sheet.view, 360, 420)
        val scroll = (sheet.view as ViewGroup).getChildAt(0) as NestedScrollView
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 100f, 300f, 0)
        val move = android.view.MotionEvent.obtain(0, 100, android.view.MotionEvent.ACTION_MOVE, 100f, 30f, 0)
        try {
            assertFalse(scroll.onInterceptTouchEvent(down))
            assertFalse("drawing is not stolen by the new viewport", scroll.onInterceptTouchEvent(move))
        } finally { down.recycle(); move.recycle() }
    }

    @Test fun bothActionsExposeButtonRole() = withActivity { activity ->
        val sheet = SwSheet.builder(activity).setPrimary("Continue").setSecondary("Cancel").build()
        for (button in listOf(sheet.primaryButton, sheet.secondaryButton)) {
            val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
            button.onInitializeAccessibilityNodeInfo(info)
            assertEquals("android.widget.Button", info.className.toString())
        }
    }

    @Test fun decorativeIconOverloadsAndLegacyCallbacks() = withActivity { activity ->
        val sheet = SwSheet.builder(activity).setPrimary("Continue").setSecondary("Cancel").build()
        // Reflection keeps the RED build compiling before the additive overload exists.
        val method = SwSheet::class.java.methods.firstOrNull {
            it.name == "setPrimary" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == CharSequence::class.java
        }
        assertNotNull("additive primary icon overload", method)
        var clicked = 0
        method!!.invoke(sheet, "Compress", R.drawable.ic_compress, { clicked++ })
        assertNotNull(sheet.primaryButton.compoundDrawablesRelative[0])
        assertNull("decorative glyph is not spoken", sheet.primaryButton.contentDescription)
        val info = android.view.accessibility.AccessibilityNodeInfo.obtain()
        sheet.primaryButton.onInitializeAccessibilityNodeInfo(info)
        assertEquals("Compress", info.text.toString())
        val secondary = SwSheet::class.java.methods.firstOrNull {
            it.name == "setSecondary" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == CharSequence::class.java
        }
        assertNotNull("additive secondary icon overload", secondary)
        secondary!!.invoke(sheet, "Cancel", R.drawable.ic_sym_close, null)
        assertNotNull(sheet.secondaryButton.compoundDrawablesRelative[0])
        assertNull(sheet.secondaryButton.contentDescription)
        sheet.show()
        sheet.primaryButton.performClick()
        assertEquals(1, clicked)
        assertFalse(sheet.isShowing)
        sheet.setPrimary("Legacy")
        assertNull("no-icon call resets decorative icon", sheet.primaryButton.compoundDrawablesRelative[0])
    }

    @Test fun progressUnlockAndDefaultIconTargetsAre48dp() = withActivity { activity ->
        val icon = SwStyle.iconButton(activity, R.drawable.ic_sym_close, android.graphics.Color.WHITE)
        assertTrue("default icon width >= 48dp", icon.layoutParams.width >= SwStyle.dp(activity, 48f))
        icon.measure(View.MeasureSpec.makeMeasureSpec(icon.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(icon.layoutParams.height, View.MeasureSpec.EXACTLY))
        icon.layout(0, 0, icon.measuredWidth, icon.measuredHeight)
        val glyph = android.graphics.RectF(icon.drawable.bounds)
        icon.imageMatrix.mapRect(glyph)
        assertEquals("glyph stays 24dp", SwStyle.dp(activity, 24f).toFloat(), glyph.width(), 1f)
        val progress = SwProgress.show(activity, "Working", onCancel = {})
        try {
            layout(progress.view, 360, 752)
            assertTrue("progress Cancel >= 48dp",
                progress.view.findViewById<View>(R.id.swProgressCancel).height >= SwStyle.dp(activity, 48f))
        } finally { progress.dismiss() }
        val unlock = LayoutInflater.from(activity).inflate(R.layout.sheet_unlock, null)
        layout(unlock, 360, 752)
        for (id in listOf(R.id.unlockCancel, R.id.unlockRemove)) {
            assertTrue("Unlock target >= 48dp: $id",
                unlock.findViewById<View>(id).height >= SwStyle.dp(activity, 48f))
        }
    }

    @Test fun realCompressFlowHasPinnedIconFooterAndRendersEnHi() {
        for (lang in listOf("en", "hi")) for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setQualifiers("$lang-w360dp-h800dp-xhdpi")
            RuntimeEnvironment.setFontScale(scale)
            val app = ApplicationProvider.getApplicationContext<android.app.Application>()
            app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
            val repo = ScanRepository(app)
            val id = DocFixtures.saved(repo, app, pages = 3, name = "Fictional application")
            CompressFlow.estimateForTest = { _, _ -> 120000L }
            try {
                ActivityScenario.launch<DocumentViewerActivity>(
                    DocumentViewerActivity.intent(app, id)
                ).use { scenario -> scenario.onActivity { activity ->
                    assertEquals(lang, activity.resources.configuration.locales[0].language)
                    assertEquals(scale, activity.resources.configuration.fontScale, 0.01f)
                    fun await(condition: () -> Boolean) {
                        val deadline = System.currentTimeMillis() + 20000
                        while (System.currentTimeMillis() < deadline) {
                            shadowOf(android.os.Looper.getMainLooper()).idle()
                            if (condition()) return
                            Thread.sleep(20)
                        }
                        assertTrue("real Compress sheet ready", condition())
                    }
                    await { activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
                    activity.findViewById<View>(R.id.btnMore).performClick()
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.rowMoreCompress).performClick()
                    await { ShadowDialog.getLatestDialog()?.findViewById<View>(R.id.compressScroll) != null }
                    val dialog = ShadowDialog.getLatestDialog()!!
                    val root = dialog.findViewById<ViewGroup>(R.id.swSheetRoot)
                    val levels = root.findViewById<LinearLayout>(R.id.levelGroup)
                    val names = listOf(R.string.compress_level_low, R.string.compress_level_medium,
                        R.string.compress_level_high, R.string.compress_level_ultra)
                    await { (0 until levels.childCount).all { index ->
                        (levels.getChildAt(index) as TextView).text.toString() != activity.getString(
                            R.string.compress_est_pending, activity.getString(names[index]))
                    } }
                    val primary = root.findViewById<TextView>(R.id.swSheetPrimary)
                    assertNotNull("Compress glyph", primary.compoundDrawablesRelative[0])
                    assertNull(primary.contentDescription)
                    for (width in listOf(360, 411)) for (height in listOf(752, 420)) {
                        layout(root, width, height)
                        for (buttonId in listOf(R.id.swSheetPrimary, R.id.swSheetSecondary)) {
                            val button = root.findViewById<TextView>(buttonId)
                            val rect = android.graphics.Rect()
                            button.getDrawingRect(rect)
                            root.offsetDescendantRectToMyCoords(button, rect)
                            assertTrue("real action fully inside window", rect.bottom <= root.height - root.paddingBottom)
                            assertTrue("real label fits", button.layout.height <=
                                button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        }
                    }
                    // Same native Canvas technique as ScreenAtlasTest, with only fictional fixtures.
                    // Store encoded PNGs in the JUnit XML artifact so cloud gates can return evidence.
                    layout(root, 360, 752)
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height,
                        android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    val bytes = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
                    bitmap.recycle()
                    println("U3PNG:compress_${lang}_${scale}:" +
                        java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()))
                    dialog.dismiss()
                } }
            } finally {
                CompressFlow.estimateForTest = null
                RuntimeEnvironment.setFontScale(1f)
            }
        }
    }
}
