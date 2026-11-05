package com.scanwhirl.app.model

import android.net.Uri

/** A PDF found on the device (MediaStore or user-picked). */
data class DevicePdf(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long
)
