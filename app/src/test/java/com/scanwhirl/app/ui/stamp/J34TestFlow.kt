package com.scanwhirl.app.ui.stamp

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.scanwhirl.app.R
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowDialog

/** Drives the real privacy review and filename controls; never grants a test-only save bypass. */
internal object J34TestFlow {
    const val COPY_NAME = "Reviewed fixture copy"

    fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    fun completeReview() {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val root = dialog.window!!.decorView
        val next = all(root).filterIsInstance<TextView>().first {
            it.text.toString() == it.context.getString(R.string.stamp_next_page)
        }
        while (next.isEnabled) next.performClick()
        val check = all(root).first { it.tag == "review-checked" } as CheckBox
        assertTrue("acknowledgement requires every affected page", check.isEnabled)
        check.isChecked = true
        val save = dialog.findViewById<View>(R.id.swSheetPrimary)
        assertTrue("only a completed Hidden review enables Save", save.isEnabled)
        save.performClick()
    }

    fun confirmName(name: String = COPY_NAME) {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        val field = all(dialog.window!!.decorView).first { it.tag == "editor-copy-name" } as EditText
        field.setText(name)
        dialog.findViewById<View>(R.id.swSheetPrimary).performClick()
    }
}
