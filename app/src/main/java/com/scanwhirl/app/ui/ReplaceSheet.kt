package com.scanwhirl.app.ui

import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwSheet

/**
 * Lane CR — the single consent sheet for "Replace original".
 *
 * The consequence is stated before consent: the original goes to Trash for
 * 30 days and can be undone. This sheet never deletes anything itself.
 */
object ReplaceSheet {

    fun show(activity: BaseActivity, onConfirm: () -> Unit) {
        SwSheet.builder(activity)
            .setTitle(R.string.cr_replace_title)
            .setMessage(R.string.cr_replace_message)
            .setPrimary(R.string.cr_replace_confirm) { onConfirm() }
            .setSecondary(R.string.cr_replace_cancel)
            .build()
            .show()
    }
}
