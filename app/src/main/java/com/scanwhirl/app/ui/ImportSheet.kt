package com.scanwhirl.app.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.scanwhirl.app.R

/**
 * Slice 4: the Import chooser. Two described options — Photos and PDF — on the
 * Index's ink sheet. It only *chooses*: each row dismisses the sheet and hands
 * control to the caller's existing launcher (the gallery picker → Review, or
 * the device-PDF picker → rasterize confirmation → saved copy), so no import,
 * Save, OCR or storage logic lives here. A tap is accepted once per sheet, so a
 * double tap can never launch a picker (or, later, a document) twice.
 */
object ImportSheet {

    fun show(activity: Activity, onPhotos: () -> Unit, onPdf: () -> Unit): BottomSheetDialog {
        val sheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.sheet_import, null)
        sheet.setContentView(view)

        var chosen = false
        fun choose(action: () -> Unit) {
            if (chosen) return
            chosen = true
            sheet.dismiss()
            action()
        }

        ViewCompat.setAccessibilityHeading(view.findViewById<TextView>(R.id.importTitle), true)
        view.findViewById<View>(R.id.importPhotos).apply {
            asButton()
            setOnClickListener { choose(onPhotos) }
        }
        view.findViewById<View>(R.id.importPdf).apply {
            asButton()
            setOnClickListener { choose(onPdf) }
        }
        view.findViewById<View>(R.id.importClose).setOnClickListener { sheet.dismiss() }

        sheet.show()
        // Open expanded (as Tools does): at large font scales the content is taller
        // than the collapsed peek; expanded + scrolling keeps every row reachable.
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return sheet
    }

    /** A clickable row reads as "<title>, <description>, Button" in one focus stop. */
    private fun View.asButton() {
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }
}
