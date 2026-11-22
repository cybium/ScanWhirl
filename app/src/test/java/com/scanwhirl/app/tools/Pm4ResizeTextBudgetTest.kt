package com.scanwhirl.app.tools

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.DialogResizeBinding
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.SizeUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * GPT post-merge PM-4: the resize screen must obey the binding text budget
 * (qa/audit-1.3.0/uiux/UX_RULES_SCANNER.md): at most 40 visible app words, one
 * helper of at most 50 characters, details behind an ⓘ, 48dp touch targets.
 */
private enum class ResizeState { CROP, BORDERS, EMPTY_TARGET, TARGET_MISS }

/** Count visible words in TextViews/hints; numbers and user file content do not count. */
private fun visibleAppWords(root: View): Int {
    var words = 0
    fun walk(v: View) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView && v.id != R.id.fileInfo) {
            // fileInfo is a file name (user content) plus numbers: excluded by the rule.
            listOfNotNull(v.text?.toString(), v.hint?.toString()).forEach { text ->
                words += text.split(Regex("\\s+")).count { token ->
                    token.any { it.isLetter() }
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
    return words
}

private class RenderedResize(val binding: DialogResizeBinding, val words: Int) {
    val helper: String get() = binding.helperText.text.toString()
}

private fun renderResize(state: ResizeState): RenderedResize {
    val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
    val b = DialogResizeBinding.inflate(activity.layoutInflater)
    b.fileInfo.text = activity.getString(
        R.string.resize_file_info, "Ravi signature.jpg", SizeUnits.format(120_000L), 200, 230
    )
    b.editWidth.setText("200")
    b.editHeight.setText("230")
    when (state) {
        ResizeState.BORDERS -> {
            b.resizeModeBorders.isChecked = true
            b.helperText.text = activity.getString(R.string.resize_borders_helper)
        }
        ResizeState.TARGET_MISS -> {
            b.helperText.text = activity.getString(
                R.string.resize_target_missed, 200, 230, "500 KB"
            )
        }
        else -> b.helperText.text = activity.getString(R.string.resize_crop_helper)
    }
    if (state == ResizeState.EMPTY_TARGET) b.editMaxSize.text?.clear()
    else b.editMaxSize.setText("500 KB")

    val sheet = SwSheet.builder(activity)
        .setTitle(R.string.resize_title)
        .setContent(b.root)
        .setSecondary(android.R.string.cancel)
        .build()
    sheet.setPrimaryStayOpen(R.string.resize_action) {}
    sheet.show()
    shadowOf(Looper.getMainLooper()).idle()
    return RenderedResize(b, visibleAppWords(sheet.view))
}

private fun assertBudget(state: ResizeState) {
    val rendered = renderResize(state)
    assertTrue("$state: ${rendered.words} visible app words (max 40)", rendered.words <= 40)
    if (state == ResizeState.CROP || state == ResizeState.BORDERS) {
        assertTrue("$state: helper too long (${rendered.helper.length} chars): ${rendered.helper}",
            rendered.helper.length <= 50)
        assertFalse("$state: helper has more than one sentence",
            rendered.helper.count { it == '.' || it == '।' } > 1)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xhdpi")
class Pm4ResizeEnglishTextBudgetTest {

    @Test fun `crop state stays within the text budget`() = assertBudget(ResizeState.CROP)

    @Test fun `borders state stays within the text budget`() = assertBudget(ResizeState.BORDERS)

    @Test fun `empty target state stays within the text budget`() = assertBudget(ResizeState.EMPTY_TARGET)

    @Test fun `target miss state stays within the text budget`() = assertBudget(ResizeState.TARGET_MISS)

    @Test fun `the byte explanation sits behind a 48dp labelled info button`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val b = DialogResizeBinding.inflate(activity.layoutInflater)
        val dp48 = SwStyle.dp(activity, 48f)
        assertEquals("width field touch target", dp48, b.editWidth.layoutParams.height)
        assertEquals("height field touch target", dp48, b.editHeight.layoutParams.height)
        assertEquals("info button touch target", dp48, b.btnResizeInfo.layoutParams.height)
        assertNotNull("info button needs a TalkBack label", b.btnResizeInfo.contentDescription)
        val detail = activity.getString(R.string.resize_info_detail)
        assertFalse("the detail is not visible on the face of the screen",
            detail == b.helperText.text.toString())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi-w360dp-h800dp-xhdpi")
class Pm4ResizeHindiTextBudgetTest {

    @Test fun `crop state stays within the text budget`() = assertBudget(ResizeState.CROP)

    @Test fun `borders state stays within the text budget`() = assertBudget(ResizeState.BORDERS)

    @Test fun `empty target state stays within the text budget`() = assertBudget(ResizeState.EMPTY_TARGET)

    @Test fun `target miss state stays within the text budget`() = assertBudget(ResizeState.TARGET_MISS)
}
