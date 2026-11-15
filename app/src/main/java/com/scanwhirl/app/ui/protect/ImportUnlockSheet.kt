package com.scanwhirl.app.ui.protect

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import kotlin.math.ceil

/**
 * The Unlock sheet for **importing** a password-protected PDF (the old "This PDF is
 * password-protected. [Got it]" dead end). It is the same 09_unlock_sheet field and tries-left
 * pacing as [UnlockSheet], but the submit is asynchronous: decrypting and rendering the pages
 * happens off the main thread while the sheet disables Open.
 *
 * [onSubmit] must call its callback on the main thread with [Result]:
 *  - [Result.OK] dismisses the sheet (the caller has created the document),
 *  - [Result.WRONG] counts a try and shows the tries-left / pause line,
 *  - [Result.UNSUPPORTED] explains a format this offline decoder cannot open,
 *  - [Result.FAILED] shows the import failure.
 */
internal object ImportUnlockSheet {

    enum class Result { OK, WRONG, UNSUPPORTED, FAILED }

    fun show(
        activity: AppCompatActivity,
        documentName: String,
        vaultAvailable: Boolean = false,
        onUseVault: ((done: (String?) -> Unit) -> Unit)? = null,
        onSubmit: (password: String, done: (Result) -> Unit) -> Unit
    ) {
        val field = PasswordField(activity).apply {
            showStrength = false
            editText.id = R.id.unlockPassword
            editText.hint = activity.getString(R.string.protect_password_hint)
            editText.contentDescription = activity.getString(R.string.protect_label_password)
        }
        val sheet = SwSheet.builder(activity)
            .setTitle(activity.getString(R.string.unlock_sheet_title, documentName))
            .setMessage(R.string.import_unlock_note)
            .setContent(field)
            .setPrimary(R.string.unlock_open)
            .build()

        if (vaultAvailable && onUseVault != null) {
            sheet.setSecondary(R.string.unlock_use_vault)
            sheet.secondaryButton.setOnClickListener {
                sheet.secondaryButton.isEnabled = false
                onUseVault { password ->
                    if (!sheet.isShowing) return@onUseVault
                    sheet.secondaryButton.isEnabled = true
                    if (password == null) {
                        field.setError(activity.getString(R.string.vault_password_missing))
                    } else {
                        field.editText.setText(password)
                        sheet.primaryButton.performClick()
                    }
                }
            }
        }

        val cooldown = TryCooldown()
        val handler = Handler(Looper.getMainLooper())
        var busy = false

        fun tickPause() {
            if (!sheet.isShowing) return
            val left = cooldown.remainingPauseMs()
            if (left <= 0L && cooldown.canTry()) {
                sheet.primaryButton.isEnabled = !busy
                field.setError(null)
                return
            }
            sheet.primaryButton.isEnabled = false
            field.setError(
                activity.getString(R.string.unlock_wrong_paused, ceil(left / 1000.0).toInt())
            )
            handler.postDelayed({ tickPause() }, 1000L)
        }

        fun attempt() {
            if (busy) return
            if (!cooldown.canTry()) {
                tickPause()
                return
            }
            val text = field.text()
            if (text.isEmpty()) {
                field.setError(
                    activity.getString(R.string.unlock_wrong_tries, cooldown.triesLeft, 30)
                )
                return
            }
            busy = true
            sheet.primaryButton.isEnabled = false
            field.setError(null)
            onSubmit(text) { result ->
                if (!sheet.isShowing) {
                    busy = false
                    return@onSubmit
                }
                busy = false
                sheet.primaryButton.isEnabled = true
                when (result) {
                    Result.OK -> {
                        cooldown.reset()
                        handler.removeCallbacksAndMessages(null)
                        sheet.dismiss()
                    }
                    Result.WRONG -> {
                        val pause = cooldown.registerFailure()
                        if (pause > 0L) {
                            field.setError(
                                activity.getString(R.string.unlock_wrong_paused, pause / 1000)
                            )
                            tickPause()
                        } else {
                            field.setError(
                                activity.getString(R.string.unlock_wrong_tries, cooldown.triesLeft, 30)
                            )
                        }
                    }
                    Result.UNSUPPORTED -> field.setError(
                        activity.getString(R.string.import_unlock_unsupported)
                    )
                    Result.FAILED -> field.setError(
                        activity.getString(R.string.import_unlock_failed)
                    )
                }
            }
        }

        field.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = field.setError(null)
        })
        sheet.primaryButton.setOnClickListener { attempt() }
        field.editText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                attempt()
                true
            } else {
                false
            }
        }

        sheet.show()
        // A typed PDF password must never be screenshot-able or appear in recents.
        // The flag lives on the sheet's own window and goes away with it.
        com.scanwhirl.app.util.SecureView.apply(sheet.window)
        sheet.setOnDismissListener {
            com.scanwhirl.app.util.SecureView.clear(sheet.window)
        }
        field.editText.requestFocus()
    }
}
