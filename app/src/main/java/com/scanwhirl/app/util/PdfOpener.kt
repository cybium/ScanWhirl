package com.scanwhirl.app.util

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.scanwhirl.app.R
import java.io.File

/** T4.7 "Open": show a saved export in the device's PDF viewer. */
object PdfOpener {

    fun open(activity: Activity, saved: Uri) {
        try {
            val target = if (saved.scheme == ContentResolver.SCHEME_FILE) {
                FileProvider.getUriForFile(
                    activity, activity.packageName + ".fileprovider", File(saved.path!!)
                )
            } else {
                saved
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(target, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.open)))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.no_pdf_app, Toast.LENGTH_SHORT).show()
        }
    }
}
