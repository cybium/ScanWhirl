package com.scanwhirl.app.ui.kit

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.appcompat.widget.AppCompatTextView

/**
 * UX lane UXG (G5): a TextView that is genuinely an action to accessibility.
 *
 * The app's painted actions already report the Android Button class from code;
 * the two viewer controls are inflated straight from XML, so their role has to
 * live in the view itself. Clickable/focusable preserve the existing touch and
 * keyboard behaviour and no visual styling is changed here.
 */
class UxgActionTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}
