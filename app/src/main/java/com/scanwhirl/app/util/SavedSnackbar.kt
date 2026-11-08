package com.scanwhirl.app.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R

/**
 * T4.7: "Saved to Documents/ScanWhirl" confirmation with **two** actions —
 * Open and Undo. Material's Snackbar supports a single action button, so both
 * buttons live in a custom layout added to the snackbar view (the built-in
 * message text is hidden).
 */
object SavedSnackbar {

    fun show(
        root: View,
        message: CharSequence,
        onOpen: () -> Unit,
        onUndo: () -> Unit
    ): Snackbar {
        val snackbar = Snackbar.make(root, "", Snackbar.LENGTH_LONG)
        val layout = snackbar.view
        layout.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            ?.visibility = View.GONE
        val custom = LayoutInflater.from(root.context)
            .inflate(R.layout.snackbar_saved, layout as ViewGroup, false)
        custom.findViewById<TextView>(R.id.snackMessage).text = message
        custom.findViewById<View>(R.id.snackOpen).setOnClickListener {
            onOpen()
            snackbar.dismiss()
        }
        custom.findViewById<View>(R.id.snackUndo).setOnClickListener {
            onUndo()
            snackbar.dismiss()
        }
        layout.addView(custom)
        snackbar.show()
        return snackbar
    }
}
