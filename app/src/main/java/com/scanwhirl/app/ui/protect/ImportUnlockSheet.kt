package com.scanwhirl.app.ui.protect

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
 *  - [Result.UNSUPPORTED] explains a password format this offline decoder cannot open,
 *  - [Result.CANNOT_REWRITE] explains a valid PDF shape this offline rewriter deliberately refuses,
 *  - [Result.DAMAGED] explains a file whose structure is broken,
 *  - [Result.FAILED] shows the import failure.
 */
internal object ImportUnlockSheet {

    enum class Result { OK, WRONG, UNSUPPORTED, CANNOT_REWRITE, DAMAGED, FAILED }

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

        // X1 P2: the kit suppresses caller onDismiss when the owner is destroyed, so a rotation
        // leaves the password in the field the app-scoped import callback still holds. Wipe it on
        // ON_DESTROY too, exactly as kit/CredentialSubmit.kt does for its secret fields.
        val destroyObserver = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                field.editText.setText("")
            }
        }
        activity.lifecycle.addObserver(destroyObserver)

        // Closed for any reason (Open, back, outside tap): the typed password leaves the view.
        sheet.setOnDismissListener {
            field.editText.setText("")
            activity.lifecycle.removeObserver(destroyObserver)
        }

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
            // An empty password is a real password: owner-only PDFs (empty user password, owner
            // password set) need it. The decryptor verifies "" like any other value and answers
            // WRONG when the file needs a typed user password — the sheet must not decide first.
            val text = field.text()
            busy = true
            sheet.primaryButton.isEnabled = false
            field.setError(null)
            onSubmit(text) { result ->
                if (!sheet.isShowing) {
                    // The sheet is gone (e.g. rotated away), but the app-scoped callback still
                    // owns this field: never let a late result leave the secret behind.
                    busy = false
                    field.editText.setText("")
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
                                activity.getString(
                                R.string.unlock_wrong_tries, cooldown.triesLeft, cooldown.nextPauseSeconds()
                            )
                            )
                        }
                    }
                    Result.UNSUPPORTED -> field.setError(
                        activity.getString(R.string.import_unlock_unsupported)
                    )
                    Result.CANNOT_REWRITE -> field.setError(
                        activity.getString(R.string.import_unlock_cannot_rewrite)
                    )
                    Result.DAMAGED -> field.setError(
                        activity.getString(R.string.import_unlock_damaged)
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
        sheet.secure()
        field.editText.requestFocus()
    }
}
