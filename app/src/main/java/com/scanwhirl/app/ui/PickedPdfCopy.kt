package com.scanwhirl.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scanwhirl.app.util.BoundedCopy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.File
import java.util.UUID

/** One private copy per reader, retained only for this reader's configuration changes. */
class PickedPdfCopy : ViewModel() {
    private var task: Deferred<File>? = null
    private var file: File? = null

    fun open(context: Context, uri: Uri): Deferred<File> {
        task?.let { return it }
        val app = context.applicationContext
        return viewModelScope.async {
            val target = File(app.cacheDir, "pdf_open/view_${UUID.randomUUID()}.pdf")
            file = target
            BoundedCopy.requireOk(BoundedCopy.copy(app, uri, target), app)
            target
        }.also { task = it }
    }

    override fun onCleared() {
        task?.cancel()
        file?.let(BoundedCopy::release)
    }
}
